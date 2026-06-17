import type {
  ActionRequest,
  ActionResult,
  ActionErrorCode,
  NavigateParams,
  ClickParams,
  TypeParams,
  PressKeyParams,
  ScrollParams,
  ScrollRegionParams,
  RegisterRegionParams,
  DetectRegionParams,
  ExtractRegionParams,
  OpenAuthorFromCommentParams,
  ClickProfileActionParams,
  TypeDmDraftParams,
  CloseTabParams,
  MoveMouseParams,
  WaitParams,
  DouyinCommentNetworkParams,
  DouyinSearchParams,
  DouyinOpenVideoParams,
  DouyinUiParams,
} from './types'

/**
 * Per-kind handler signature. Every handler receives the resolved
 * chrome tab id (the SW already resolved `tab_ref` before calling),
 * the strongly-typed params, and the deadline (for the handler's own
 * timeout race). Returns ActionResult — but in practice handlers
 * throw on failure (the ActionExecutor catches and wraps).
 */
export type ActionHandler<P> = (
  tabId: number,
  params: P,
  deadlineMs: number,
) => Promise<ActionResult>

export interface ActionHandlers {
  navigate:   ActionHandler<NavigateParams>
  click:      ActionHandler<ClickParams>
  type:       ActionHandler<TypeParams>
  press_key:  ActionHandler<PressKeyParams>
  scroll:     ActionHandler<ScrollParams>
  scroll_region: ActionHandler<ScrollRegionParams>
  register_region: ActionHandler<RegisterRegionParams>
  detect_region: ActionHandler<DetectRegionParams>
  extract_region: ActionHandler<ExtractRegionParams>
  open_author_from_comment: ActionHandler<OpenAuthorFromCommentParams>
  click_profile_action: ActionHandler<ClickProfileActionParams>
  type_dm_draft: ActionHandler<TypeDmDraftParams>
  close_tab: ActionHandler<CloseTabParams>
  move_mouse: ActionHandler<MoveMouseParams>
  wait:       ActionHandler<WaitParams>
  douyin_comment_network: ActionHandler<DouyinCommentNetworkParams>
  douyin_search: ActionHandler<DouyinSearchParams>
  douyin_open_video: ActionHandler<DouyinOpenVideoParams>
  douyin_ui: ActionHandler<DouyinUiParams>
}

/**
 * Typed wire-error exception. Handlers throw this when they want the
 * executor to surface a specific `code` + `retryable` on the wire
 * (e.g. `TIMEOUT_PAGE_LOAD`, `NO_TARGET_TAB`, `DEVTOOLS_OPEN`).
 *
 * Anything thrown that is NOT an `ActionFailureError` falls back to
 * `code='HANDLER_ERROR'`, `retryable=true`. The thrown error's
 * `.message` is preserved either way.
 */
export class ActionFailureError extends Error {
  constructor(
    public readonly code: ActionErrorCode,
    message: string,
    public readonly retryable: boolean = false,
  ) {
    super(message)
    this.name = 'ActionFailureError'
  }
}

/**
 * Routes an ActionRequest to its per-kind handler and shapes the
 * response into a wire-format ActionResult.
 *
 * Responsibilities (Phase 2 task B2):
 *   - Switch on `kind` to pick the handler
 *   - Pass (tabId, params, deadline_ms) — params is already narrowed
 *     by the discriminated-union TS type
 *   - Measure elapsed_ms wall-clock from run-start to handler-return
 *   - Catch exceptions:
 *       ActionFailureError  → Failure with its typed code+retryable
 *       anything else       → Failure code=HANDLER_ERROR retryable=true
 *   - Unknown kinds → Failure code=UNKNOWN_KIND retryable=false
 *
 * Deadline ENFORCEMENT is NOT done here — the Control Plane's
 * ActionExecutionService races the request with `Mono.timeout()` and
 * sends action.cancel on expiry. The executor just hands the deadline
 * value to the handler so it can run its own internal races
 * (chrome.webNavigation.onCompleted vs setTimeout, etc.).
 *
 * Sequential plan execution is also NOT done here — that's the CP's
 * PlanExecutionService (F4). One ActionExecutor.run() call ≙ one
 * atomic action.
 */
export class ActionExecutor {
  constructor(private readonly handlers: Partial<ActionHandlers>) {}

  async run(tabId: number, req: ActionRequest): Promise<ActionResult> {
    const startedAt = Date.now()

    const handler = this.handlers[req.kind] as ActionHandler<unknown> | undefined
    if (!handler) {
      return {
        ok: false,
        code: 'UNKNOWN_KIND',
        message: `no handler registered for action kind '${req.kind}'`,
        retryable: false,
      }
    }

    try {
      const result = await handler(tabId, req.params, req.deadline_ms)
      // Overwrite handler's elapsed_ms with wall-clock measurement.
      // Handlers may not have reliable clocks (e.g. WindMouse's
      // arrived_at_ms is a logical timestamp, not wall-clock); the
      // executor is the authoritative timing source for the wire.
      if (result.ok === true) {
        // Stamp the action kind as the success-payload discriminator. The
        // server's ActionSuccessPayload uses Jackson NAME dispatch on `kind`;
        // without it the server throws InvalidTypeIdException and closes the
        // socket (observed as SESSION_DETACHED on the next action).
        return {
          ...result,
          elapsed_ms: Date.now() - startedAt,
          payload: { kind: req.kind, ...(result.payload ?? {}) },
        }
      }
      return result
    } catch (err) {
      if (err instanceof ActionFailureError) {
        return {
          ok: false,
          code: err.code,
          message: err.message,
          retryable: err.retryable,
        }
      }
      const message = err instanceof Error ? err.message : String(err)
      return {
        ok: false,
        code: 'HANDLER_ERROR',
        message,
        retryable: true,
      }
    }
  }
}
