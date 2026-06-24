import {
  EdgeMessageKind,
  makeEdgeMessage,
  type EdgeMessage,
} from '../../shared/edge-protocol'
import type { ActionExecutor } from './ActionExecutor'
import type { ActionRequest, ActionResult } from './types'
import type { TabRefResolver } from './tab-ref-resolver'
import type { TabGroupManager } from '../tab-group-manager'

export interface ActionRouterDeps {
  resolver: TabRefResolver
  executor: ActionExecutor
  /** Callback to send a reply envelope back via NativeBridge. */
  sendUp: (msg: EdgeMessage) => void
  /**
   * Map: action.execute msg_id → AbortController for the in-flight run.
   * handleExecute registers one per run; handleCancel aborts by the msg_id
   * carried on action.cancel's in_reply_to. The signal is threaded into the
   * executor + handlers for true mid-action cancellation.
   */
  inflight?: Map<string, AbortController>
  /** Optional visual owner for the Chrome tab group status title. */
  tabGroupManager?: TabGroupManager
  /** Subject whose controlled group should be marked Working/Done. */
  subject?: string
}

/**
 * Routes inbound action.* envelopes to the executor and sends results back.
 *
 *   action.execute     → resolve tab_ref → ActionExecutor.run → action.result
 *   action.cancel      → look up in-flight by in_reply_to → abort (best effort)
 *   indicator.stop_clicked → re-emit upstream (NH stamps session_id, CP routes
 *                            it to ActionExecutionService.handleStopClicked)
 *
 * On tab_ref resolution failure → send action.result Failure(NO_TARGET_TAB)
 * without ever invoking the executor.
 *
 * Forward-compat: unknown kinds are silently dropped (the protocol may add
 * new kinds in future versions; an older SW must not crash on them).
 */
export class ActionRouter {
  constructor(private readonly deps: ActionRouterDeps) {}

  async handle(msg: EdgeMessage): Promise<void> {
    switch (msg.kind) {
      case EdgeMessageKind.ActionExecute:
        await this.handleExecute(msg)
        return
      case EdgeMessageKind.ActionCancel:
        this.handleCancel(msg)
        return
      case EdgeMessageKind.IndicatorStopClicked:
        this.handleStopClicked(msg)
        return
      default:
        // Forward-compat invariant — ignore everything else silently.
        return
    }
  }

  // ---------------------------------------------------------------
  // action.execute
  // ---------------------------------------------------------------

  private async handleExecute(msg: EdgeMessage): Promise<void> {
    const req = parseActionRequest(msg.payload)
    if (!req) {
      // Malformed payload — emit Failure(HANDLER_ERROR) with the inbound
      // msg_id so the CP can correlate. We deliberately avoid throwing.
      this.sendResult(msg, {
        ok: false,
        code: 'HANDLER_ERROR',
        message: 'action.execute payload was malformed (missing kind/params/etc.)',
        retryable: false,
      })
      return
    }

    // Step 1: resolve tab_ref → tabId
    let tabId: number | null
    try {
      // Only navigate may provision the agent's tab. click/type/scroll/wait
      // need an already-loaded page; if "main" was closed they get
      // NO_TARGET_TAB (caller re-navigates) rather than acting on a blank tab.
      tabId = await this.deps.resolver.resolve(req.tab_ref, {
        createIfMissing: req.kind === 'navigate',
      })
    } catch (err) {
      this.sendResult(msg, {
        ok: false,
        code: 'HANDLER_ERROR',
        message: `tab_ref resolution threw: ${errorMessage(err)}`,
        retryable: true,
      })
      return
    }

    if (tabId === null) {
      this.sendResult(msg, {
        ok: false,
        code: 'NO_TARGET_TAB',
        message: `could not resolve tab_ref=${JSON.stringify(req.tab_ref)}`,
        retryable: false,
      })
      return
    }

    // Step 2: dispatch through ActionExecutor (it catches handler throws
    // and shapes them into ActionResult).
    //
    // Register an AbortController under req.msg_id BEFORE running so an inbound
    // action.cancel (which carries the action.execute msg_id in in_reply_to)
    // can abort this run mid-flight. The signal is threaded through the executor
    // into the handler; the controller is always removed in finally so a late
    // cancel after completion is a harmless no-op.
    const controller = new AbortController()
    this.deps.inflight?.set(req.msg_id, controller)
    let result: ActionResult
    try {
      result = await this.deps.executor.run(tabId, req, controller.signal)
    } catch (err) {
      // Defensive — ActionExecutor.run already catches handler throws,
      // but we don't trust the callback. Surface as HANDLER_ERROR.
      result = {
        ok: false,
        code: 'HANDLER_ERROR',
        message: errorMessage(err),
        retryable: true,
        resolvedTabId: tabId,
      }
    } finally {
      // Only drop OUR controller — a cancel that already aborted + deleted it
      // may have left a (rare) newer entry under the same id; never clobber it.
      if (this.deps.inflight?.get(req.msg_id) === controller) {
        this.deps.inflight.delete(req.msg_id)
      }
    }

    this.sendResult(msg, result)
  }

  /**
   * Build and send an action.result envelope linked to the inbound
   * action.execute by in_reply_to + trace_id. session_id is always ""
   * (P0-1 invariant — NH owns session_id).
   */
  private sendResult(inbound: EdgeMessage, result: ActionResult): void {
    const reply = makeEdgeMessage({
      kind: EdgeMessageKind.ActionResult,
      traceId: inbound.trace_id,
      inReplyTo: inbound.msg_id,
      payload: result as unknown as Record<string, unknown>,
    })
    this.deps.sendUp(reply)
  }

  // ---------------------------------------------------------------
  // action.cancel
  // ---------------------------------------------------------------

  private handleCancel(msg: EdgeMessage): void {
    const inflight = this.deps.inflight
    if (!inflight) return

    // Cross-end contract: the cancelled action.execute msg_id is carried on the
    // EdgeMessage TOP-LEVEL in_reply_to; the server ALSO mirrors it into
    // payload.in_reply_to as a double-safety. Prefer the top-level field, fall
    // back to the payload mirror.
    const payload = msg.payload ?? {}
    const targetId =
      typeof msg.in_reply_to === 'string' && msg.in_reply_to.length > 0
        ? msg.in_reply_to
        : typeof payload.in_reply_to === 'string'
          ? payload.in_reply_to
          : null
    if (!targetId) return

    const ctrl = inflight.get(targetId)
    if (!ctrl) return  // unknown msg_id is a no-op

    ctrl.abort()
    inflight.delete(targetId)
  }

  // ---------------------------------------------------------------
  // indicator.stop_clicked
  // ---------------------------------------------------------------

  private handleStopClicked(msg: EdgeMessage): void {
    // Re-emit upstream verbatim. NH/CP will stamp session_id and route
    // this to ActionExecutionService.handleStopClicked. We preserve the
    // inbound trace_id and payload, but emit through makeEdgeMessage so
    // session_id="" and a fresh msg_id are stamped.
    this.deps.sendUp(makeEdgeMessage({
      kind: EdgeMessageKind.IndicatorStopClicked,
      traceId: msg.trace_id,
      payload: msg.payload,
    }))
  }

}

// ---------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------

function errorMessage(err: unknown): string {
  if (err instanceof Error) return err.message
  return String(err)
}

/**
 * Validate that the inbound payload looks like an ActionRequest. We don't
 * fully validate per-kind params (the per-kind handlers will do that);
 * just confirm the top-level fields needed to dispatch.
 *
 * Returns null on any missing / wrong-typed field — caller surfaces a
 * generic HANDLER_ERROR rather than crashing on undefined.
 */
function parseActionRequest(payload: unknown): ActionRequest | null {
  if (!payload || typeof payload !== 'object') return null
  const p = payload as Record<string, unknown>

  if (typeof p.msg_id !== 'string') return null
  if (typeof p.kind !== 'string') return null
  if (typeof p.deadline_ms !== 'number') return null
  if (p.params == null || typeof p.params !== 'object') return null

  // tab_ref must be 'main' | 'active' | number
  if (
    p.tab_ref !== 'main' &&
    p.tab_ref !== 'active' &&
    typeof p.tab_ref !== 'number'
  ) {
    return null
  }

  return p as unknown as ActionRequest
}
