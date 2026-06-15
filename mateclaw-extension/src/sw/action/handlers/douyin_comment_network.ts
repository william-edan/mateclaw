import { ActionFailureError, type ActionHandler } from '../ActionExecutor'
import type { DouyinCommentNetworkParams } from '../types'
import {
  SessionDetachedError,
  type DebuggerEvent,
  type DebuggerManager,
} from '../../debugger-manager'
import type {
  NetworkLoadingFailedEvent,
  NetworkLoadingFinishedEvent,
  NetworkRequestWillBeSentEvent,
  NetworkResponse,
  NetworkResponseReceivedEvent,
  TargetAttachedToTargetEvent,
  TargetDetachedFromTargetEvent,
} from '../../cdp-types'

const DEBUG_DOUYIN_COMMENT_NETWORK = false

export interface DouyinCommentNetworkHandlerDeps {
  debugger: DebuggerManager
  chrome?: typeof globalThis.chrome
  clock?: () => number
  setTimer?: (fn: () => void, ms: number) => unknown
  clearTimer?: (timer: unknown) => void
}

interface CaptureLimits {
  maxPages: number
  maxBodyBytes: number
  ttlMs: number
}

interface ResponseMeta {
  responseKey: string
  requestId: string
  sessionId?: string
  targetType?: string
  url: string
  status: number
  mimeType?: string
  encodedDataLength?: number
  urlSignal: boolean
  likelyJson: boolean
}

interface CapturedPage {
  url: string
  requestId: string
  sessionId?: string
  targetType?: string
  status: number
  body: string
  base64Encoded: boolean
  capturedAtMs: number
}

interface CaptureSession extends CaptureLimits {
  tabId: number
  pages: CapturedPage[]
  responses: Map<string, ResponseMeta>
  childSessions: Map<string, ChildTargetMeta>
  inflight: Set<Promise<void>>
  removeListener: () => void
  timer?: unknown
  expiresAtMs: number
  stopped: boolean
  eventsSeen: number
  responseEventsSeen: number
  loadingFinishedSeen: number
  loadingFailedSeen: number
  skippedDebugCount: number
  rawDebugCount: number
  eventMethods: Record<string, number>
  rawSamples: Array<Record<string, unknown>>
  pageHookInstalled: boolean
  pageHookMessage: string
  pageHookDrainCount: number
  pageHookErrorCount: number
}

interface ChildTargetMeta {
  sessionId: string
  targetId: string
  type: string
  url?: string
  title?: string
  networkEnabled?: boolean
}

const DEFAULT_MAX_PAGES = 8
const MAX_PAGES = 50
const DEFAULT_MAX_BODY_BYTES = 256 * 1024
const MAX_BODY_BYTES = 1024 * 1024
const DEFAULT_TTL_MS = 45_000
const MAX_TTL_MS = 180_000
const DRAIN_INFLIGHT_WAIT_MS = 750
const MAX_PENDING_RESPONSES = 200

export const douyinCommentNetworkHandler = (
  deps: DouyinCommentNetworkHandlerDeps,
): ActionHandler<DouyinCommentNetworkParams> => {
  const chromeApi = deps.chrome ?? globalThis.chrome
  const clock = deps.clock ?? Date.now
  const setTimer = deps.setTimer ?? ((fn: () => void, ms: number) => setTimeout(fn, ms))
  const clearTimer = deps.clearTimer ?? (timer => clearTimeout(timer as ReturnType<typeof setTimeout>))
  const sessions = new Map<number, CaptureSession>()

  const cleanupLocal = (session: CaptureSession): void => {
    if (session.stopped) return
    session.stopped = true
    session.removeListener()
    session.responses.clear()
    session.childSessions.clear()
    session.inflight.clear()
    session.pages.length = 0
    if (session.timer !== undefined) {
      clearTimer(session.timer)
      session.timer = undefined
    }
    sessions.delete(session.tabId)
  }

  const stopCapture = async (
    tabId: number,
    options: { disableNetwork: boolean; throwOnDisableError: boolean },
  ): Promise<void> => {
    const session = sessions.get(tabId)
    let disableError: unknown

    if (options.disableNetwork && session) {
      const disableTargets: Array<{ sessionId?: string }> = [
        {},
        ...[...session.childSessions.keys()].map(sessionId => ({ sessionId })),
      ]
      for (const target of disableTargets) {
        try {
          await deps.debugger.send(tabId, 'Network.disable', {}, target.sessionId)
        } catch (err) {
          disableError = disableError ?? err
        }
      }
      try {
        await deps.debugger.send(tabId, 'Target.setAutoAttach', {
          autoAttach: false,
          waitForDebuggerOnStart: false,
          flatten: true,
        })
      } catch (err) {
        disableError = disableError ?? err
      }
      await stopPageHook(tabId)
    }

    if (session) cleanupLocal(session)

    if (options.throwOnDisableError && disableError) {
      throw mapDebuggerError(disableError)
    }
  }

  const enableNetworkForTarget = async (
    session: CaptureSession,
    sessionId?: string,
  ): Promise<void> => {
    await deps.debugger.send(session.tabId, 'Network.enable', {
      maxResourceBufferSize: session.maxBodyBytes,
      maxTotalBufferSize: Math.min(session.maxBodyBytes * session.maxPages, 4 * 1024 * 1024),
      maxPostDataSize: 0,
    }, sessionId)
    if (sessionId) {
      const child = session.childSessions.get(sessionId)
      if (child) child.networkEnabled = true
    }
    logNetworkDebug('network_enabled', {
      tabId: session.tabId,
      sessionId: sessionId ?? '',
      targetType: targetTypeForSession(session, sessionId) ?? 'root',
    })
  }

  const startCapture = async (
    tabId: number,
    params: DouyinCommentNetworkParams,
  ) => {
    const limits = normalizeLimits(params)
    await stopCapture(tabId, { disableNetwork: true, throwOnDisableError: false })

    let session: CaptureSession | undefined
    try {
      await deps.debugger.attach(tabId)

      session = {
        tabId,
        ...limits,
        pages: [],
        responses: new Map(),
        childSessions: new Map(),
        inflight: new Set(),
        removeListener: () => {},
        expiresAtMs: clock() + limits.ttlMs,
        stopped: false,
        eventsSeen: 0,
        responseEventsSeen: 0,
        loadingFinishedSeen: 0,
        loadingFailedSeen: 0,
        skippedDebugCount: 0,
        rawDebugCount: 0,
        eventMethods: {},
        rawSamples: [],
        pageHookInstalled: false,
        pageHookMessage: '',
        pageHookDrainCount: 0,
        pageHookErrorCount: 0,
      }
      session.removeListener = deps.debugger.addEventListener(tabId, event => {
        handleDebuggerEvent(session!, event)
      })
      session.timer = setTimer(() => {
        void stopCapture(tabId, { disableNetwork: true, throwOnDisableError: false })
      }, limits.ttlMs)
      sessions.set(tabId, session)

      await deps.debugger.send(tabId, 'Target.setAutoAttach', {
        autoAttach: true,
        waitForDebuggerOnStart: false,
        flatten: true,
      })
      await enableNetworkForTarget(session)
      const pageHook = await installPageHook(tabId, limits.maxBodyBytes)
      session.pageHookInstalled = pageHook.installed === true
      session.pageHookMessage = pageHook.message ?? ''
      logNetworkDebug('start', {
        tabId,
        maxPages: limits.maxPages,
        maxBodyBytes: limits.maxBodyBytes,
        ttlMs: limits.ttlMs,
        expiresAtMs: session.expiresAtMs,
        pageHookInstalled: session.pageHookInstalled,
        pageHookMessage: session.pageHookMessage,
      })

      return {
        ok: true as const,
        elapsed_ms: 0,
        payload: {
          op: 'start',
        capturing: true,
        maxPages: limits.maxPages,
        maxBodyBytes: limits.maxBodyBytes,
          ttlMs: limits.ttlMs,
          expiresAtMs: session.expiresAtMs,
          pageHookInstalled: session.pageHookInstalled,
          pageHookMessage: session.pageHookMessage,
        },
      }
    } catch (err) {
      if (session) cleanupLocal(session)
      throw mapDebuggerError(err)
    }
  }

  const drainCapture = async (tabId: number, deadlineMs: number) => {
    const session = sessions.get(tabId)
    if (!session) {
      return {
        ok: true as const,
        elapsed_ms: 0,
        payload: {
          op: 'drain',
          pages: [],
          drainedCount: 0,
          capturing: false,
        },
      }
    }

    await drainPageHookIntoSession(session)
    await settleInflight(session, deadlineMs)
    const pages = session.pages.splice(0, session.pages.length)
    logNetworkDebug('drain', {
      drainedCount: pages.length,
      capturing: !session.stopped,
      expiresAtMs: session.expiresAtMs,
      eventsSeen: session.eventsSeen,
      responseEventsSeen: session.responseEventsSeen,
      loadingFinishedSeen: session.loadingFinishedSeen,
      loadingFailedSeen: session.loadingFailedSeen,
      childSessions: summarizeChildSessions(session),
      pendingResponses: session.responses.size,
      inflight: session.inflight.size,
      eventMethods: session.eventMethods,
      rawSamples: session.rawSamples,
      pageHookInstalled: session.pageHookInstalled,
      pageHookMessage: session.pageHookMessage,
      pageHookDrainCount: session.pageHookDrainCount,
      pageHookErrorCount: session.pageHookErrorCount,
    })

    return {
      ok: true as const,
      elapsed_ms: 0,
      payload: {
        op: 'drain',
        pages,
        drainedCount: pages.length,
        capturing: !session.stopped,
        expiresAtMs: session.expiresAtMs,
        eventsSeen: session.eventsSeen,
        responseEventsSeen: session.responseEventsSeen,
        loadingFinishedSeen: session.loadingFinishedSeen,
        loadingFailedSeen: session.loadingFailedSeen,
        childSessions: summarizeChildSessions(session),
        pendingResponses: session.responses.size,
        inflight: session.inflight.size,
        eventMethods: session.eventMethods,
        rawSamples: session.rawSamples,
        pageHookInstalled: session.pageHookInstalled,
        pageHookMessage: session.pageHookMessage,
        pageHookDrainCount: session.pageHookDrainCount,
        pageHookErrorCount: session.pageHookErrorCount,
      },
    }
  }

  const stopCaptureAction = async (tabId: number) => {
    const session = sessions.get(tabId)
    const droppedCount = session?.pages.length ?? 0
    await stopCapture(tabId, { disableNetwork: true, throwOnDisableError: true })
    logNetworkDebug('stop', { tabId, droppedCount })

    return {
      ok: true as const,
      elapsed_ms: 0,
      payload: {
        op: 'stop',
        stopped: true,
        droppedCount,
      },
    }
  }

  async function installPageHook(tabId: number, maxBodyBytes: number): Promise<PageHookInstallResult> {
    if (!chromeApi?.scripting?.executeScript) {
      return { installed: false, message: 'scripting_unavailable' }
    }
    try {
      const results = await chromeApi.scripting.executeScript({
        target: { tabId, allFrames: false },
        world: 'MAIN',
        func: installDouyinCommentNetworkPageHook,
        args: [maxBodyBytes],
      })
      const payload = results?.[0]?.result as PageHookInstallResult | undefined
      logNetworkDebug('page_hook_install', {
        tabId,
        installed: payload?.installed === true,
        reused: payload?.reused === true,
        fetchWrapped: payload?.fetchWrapped === true,
        xhrWrapped: payload?.xhrWrapped === true,
        message: payload?.message ?? '',
      })
      return payload ?? { installed: false, message: 'empty_injection_result' }
    } catch (error) {
      logNetworkDebug('page_hook_install_failed', {
        tabId,
        message: error instanceof Error ? error.message : String(error),
      })
      return {
        installed: false,
        message: error instanceof Error ? error.message : String(error),
      }
    }
  }

  async function stopPageHook(tabId: number): Promise<void> {
    if (!chromeApi?.scripting?.executeScript) return
    try {
      await chromeApi.scripting.executeScript({
        target: { tabId, allFrames: false },
        world: 'MAIN',
        func: stopDouyinCommentNetworkPageHook,
      })
    } catch {
      // Best effort; debugger cleanup must not fail because the page changed.
    }
  }

  async function drainPageHookIntoSession(session: CaptureSession): Promise<void> {
    if (!session.pageHookInstalled || !chromeApi?.scripting?.executeScript) return
    try {
      const remaining = Math.max(0, session.maxPages - session.pages.length)
      if (remaining <= 0) return
      const results = await chromeApi.scripting.executeScript({
        target: { tabId: session.tabId, allFrames: false },
        world: 'MAIN',
        func: drainDouyinCommentNetworkPageHook,
        args: [remaining],
      })
      const payload = results?.[0]?.result as PageHookDrainResult | undefined
      const pages = Array.isArray(payload?.pages) ? payload.pages : []
      session.pageHookDrainCount += 1
      for (const page of pages) {
        if (session.pages.length >= session.maxPages) break
        if (!page || typeof page.body !== 'string' || typeof page.url !== 'string') continue
        session.pages.push({
          url: page.url,
          requestId: page.requestId || `pagehook:${session.pageHookDrainCount}:${session.pages.length}`,
          targetType: 'page-hook',
          status: finiteNumber(page.status) ?? 0,
          body: page.body,
          base64Encoded: false,
          capturedAtMs: finiteNumber(page.capturedAtMs) ?? clock(),
        })
      }
      logNetworkDebug('page_hook_drain', {
        tabId: session.tabId,
        installed: payload?.installed === true,
        drainedCount: pages.length,
        bufferedAfterMerge: session.pages.length,
        seen: payload?.seen ?? 0,
        skipped: payload?.skipped ?? 0,
        errors: payload?.errors ?? 0,
        sampleUrls: pages.slice(0, 5).map(page => page.url),
      })
    } catch (error) {
      session.pageHookErrorCount += 1
      logNetworkDebug('page_hook_drain_failed', {
        tabId: session.tabId,
        pageHookErrorCount: session.pageHookErrorCount,
        message: error instanceof Error ? error.message : String(error),
      })
    }
  }

  const handleDebuggerEvent = (session: CaptureSession, event: DebuggerEvent): void => {
    if (session.stopped || event.tabId !== session.tabId) return
    session.eventsSeen += 1
    session.eventMethods[event.method] = (session.eventMethods[event.method] ?? 0) + 1
    logRawNetworkEvent(session, event)
    if (clock() >= session.expiresAtMs) {
      void stopCapture(session.tabId, { disableNetwork: true, throwOnDisableError: false })
      return
    }

    if (event.method === 'Target.attachedToTarget') {
      rememberChildTarget(session, event.params as TargetAttachedToTargetEvent)
      return
    }
    if (event.method === 'Target.detachedFromTarget') {
      const params = event.params as TargetDetachedFromTargetEvent
      if (typeof params.sessionId === 'string') session.childSessions.delete(params.sessionId)
      return
    }

    if (event.method === 'Network.responseReceived') {
      session.responseEventsSeen += 1
      rememberResponse(session, event as DebuggerEvent<'Network.responseReceived'>)
      return
    }
    if (event.method === 'Network.loadingFinished') {
      session.loadingFinishedSeen += 1
      const work = captureFinishedResponse(session, event as DebuggerEvent<'Network.loadingFinished'>).catch(() => {})
      session.inflight.add(work)
      work.finally(() => session.inflight.delete(work))
      return
    }
    if (event.method === 'Network.loadingFailed') {
      session.loadingFailedSeen += 1
      const params = event.params as NetworkLoadingFailedEvent
      if (typeof params.requestId === 'string') session.responses.delete(responseKey(event.sessionId, params.requestId))
    }
  }

  const rememberChildTarget = (session: CaptureSession, event: TargetAttachedToTargetEvent): void => {
    if (typeof event?.sessionId !== 'string' || !event.sessionId) return
    const targetInfo = event.targetInfo
    session.childSessions.set(event.sessionId, {
      sessionId: event.sessionId,
      targetId: String(targetInfo?.targetId ?? ''),
      type: String(targetInfo?.type ?? ''),
      url: typeof targetInfo?.url === 'string' ? targetInfo.url : undefined,
      title: typeof targetInfo?.title === 'string' ? targetInfo.title : undefined,
      networkEnabled: false,
    })
    logNetworkDebug('target_attached', {
      sessionId: event.sessionId,
      targetId: targetInfo?.targetId ?? '',
      targetType: targetInfo?.type ?? '',
      url: targetInfo?.url ?? '',
      title: targetInfo?.title ?? '',
    })
    const work = enableNetworkForTarget(session, event.sessionId).catch(err => {
      logNetworkDebug('target_network_enable_failed', {
        sessionId: event.sessionId,
        message: err instanceof Error ? err.message : String(err),
      })
    })
    session.inflight.add(work)
    work.finally(() => session.inflight.delete(work))
  }

  const rememberResponse = (session: CaptureSession, event: DebuggerEvent<'Network.responseReceived'>): void => {
    const params = event?.params as NetworkResponseReceivedEvent
    const requestId = params?.requestId
    const response = params?.response
    if (typeof requestId !== 'string' || !isNetworkResponse(response)) {
      logSkippedResponse(session, 'malformed_response_event', {
        requestId: typeof requestId === 'string' ? requestId : '',
      })
      return
    }

    const key = responseKey(event.sessionId, requestId)
    const urlSignal = urlLooksLikeDouyinCommentResponse(response.url)
    const likelyJson = responseLooksJson(response)
    if (!urlSignal && (!likelyJson || !urlIsDouyinRelated(response.url))) {
      logSkippedResponse(session, 'not_comment_or_douyin_json', {
        requestId,
        url: response.url,
        status: normalizeStatus(response.status),
        mimeType: response.mimeType,
        urlSignal,
        likelyJson,
        douyinRelated: urlIsDouyinRelated(response.url),
      })
      return
    }

    session.responses.set(key, {
      responseKey: key,
      requestId,
      sessionId: event.sessionId,
      targetType: targetTypeForSession(session, event.sessionId),
      url: response.url,
      status: normalizeStatus(response.status),
      mimeType: response.mimeType,
      encodedDataLength: finiteNumber(response.encodedDataLength),
      urlSignal,
      likelyJson,
    })
    logNetworkDebug('response_candidate', {
      requestId,
      sessionId: event.sessionId ?? '',
      targetType: targetTypeForSession(session, event.sessionId) ?? '',
      url: response.url,
      status: normalizeStatus(response.status),
      mimeType: response.mimeType,
      encodedDataLength: finiteNumber(response.encodedDataLength),
      urlSignal,
      likelyJson,
      bufferedResponses: session.responses.size,
    })

    while (session.responses.size > MAX_PENDING_RESPONSES) {
      const oldest = session.responses.keys().next().value
      if (typeof oldest !== 'string') break
      session.responses.delete(oldest)
    }
  }

  const captureFinishedResponse = async (
    session: CaptureSession,
    event: DebuggerEvent<'Network.loadingFinished'>,
  ): Promise<void> => {
    const params = event?.params as NetworkLoadingFinishedEvent
    const requestId = params?.requestId
    if (typeof requestId !== 'string') return

    const key = responseKey(event.sessionId, requestId)
    const meta = session.responses.get(key)
    session.responses.delete(key)
    if (!meta || session.stopped || session.pages.length >= session.maxPages) return

    const encodedDataLength = finiteNumber(params.encodedDataLength) ?? meta.encodedDataLength
    if (encodedDataLength !== undefined && encodedDataLength > session.maxBodyBytes) {
      logNetworkDebug('body_skipped_encoded_too_large', {
        requestId,
        url: meta.url,
        encodedDataLength,
        maxBodyBytes: session.maxBodyBytes,
      })
      return
    }
    if (!meta.urlSignal && !meta.likelyJson) return

    let bodyResult: { body: string; base64Encoded: boolean }
    try {
      bodyResult = await deps.debugger.send(session.tabId, 'Network.getResponseBody', { requestId }, meta.sessionId)
    } catch {
      logNetworkDebug('body_read_failed', {
        requestId,
        sessionId: meta.sessionId ?? '',
        url: meta.url,
      })
      return
    }

    if (session.stopped || session.pages.length >= session.maxPages) return
    if (typeof bodyResult.body !== 'string') return
    const base64Encoded = bodyResult.base64Encoded === true
    const bodyBytes = bodyByteLength(bodyResult.body, base64Encoded)
    if (bodyBytes > session.maxBodyBytes) {
      logNetworkDebug('body_skipped_too_large', {
        requestId,
        url: meta.url,
        bodyBytes,
        maxBodyBytes: session.maxBodyBytes,
      })
      return
    }
    if (!meta.urlSignal && !bodyLooksLikeDouyinCommentJson(bodyResult.body, base64Encoded)) {
      logNetworkDebug('body_skipped_not_comment_json', {
        requestId,
        url: meta.url,
        status: meta.status,
        bodyBytes,
        base64Encoded,
      })
      return
    }

    session.pages.push({
      url: meta.url,
      requestId: meta.requestId,
      sessionId: meta.sessionId,
      targetType: meta.targetType,
      status: meta.status,
      body: bodyResult.body,
      base64Encoded,
      capturedAtMs: clock(),
    })
    logNetworkDebug('page_captured', {
      requestId,
      sessionId: meta.sessionId ?? '',
      targetType: meta.targetType ?? '',
      url: meta.url,
      status: meta.status,
      bodyBytes,
      base64Encoded,
      pagesBuffered: session.pages.length,
      maxPages: session.maxPages,
    })
  }

  return async (tabId, params, deadlineMs) => {
    switch (params?.op) {
      case 'start':
        return startCapture(tabId, params)
      case 'drain':
        return drainCapture(tabId, deadlineMs)
      case 'stop':
        return stopCaptureAction(tabId)
      default:
        throw new ActionFailureError(
          'HANDLER_ERROR',
          "douyin_comment_network op must be 'start', 'drain', or 'stop'",
          false,
        )
    }
  }
}

interface PageHookInstallResult {
  installed: boolean
  reused?: boolean
  fetchWrapped?: boolean
  xhrWrapped?: boolean
  message?: string
}

interface PageHookPage {
  url: string
  requestId: string
  status: number
  body: string
  capturedAtMs: number
}

interface PageHookDrainResult {
  installed: boolean
  pages: PageHookPage[]
  seen: number
  skipped: number
  errors: number
}

function installDouyinCommentNetworkPageHook(maxBodyBytes: number): PageHookInstallResult {
  const key = '__MATECLAW_DOUYIN_COMMENT_NETWORK__'
  function normalizePageHookBytes(value: number): number {
    if (!Number.isFinite(value)) return 256 * 1024
    return Math.max(1, Math.min(1024 * 1024, Math.floor(value)))
  }
  function requestInputUrl(input: RequestInfo | URL): string {
    if (typeof input === 'string') return input
    if (input instanceof URL) return String(input)
    return input?.url || ''
  }
  function safeDecodeInPageHook(value: string): string {
    try {
      return decodeURIComponent(value)
    } catch {
      return value
    }
  }
  function safeHostInPageHook(value: string): string {
    try {
      return new URL(value, location.href).hostname.toLowerCase()
    } catch {
      return String(value || '').toLowerCase()
    }
  }
  function urlIsDouyinRelatedInPageHook(rawUrl: string): boolean {
    const host = safeHostInPageHook(rawUrl)
    return host.includes('douyin.com') ||
      host.includes('iesdouyin.com') ||
      host.includes('amemv.com')
  }
  function pageHookUrlLooksLikeComment(rawUrl: string): boolean {
    const lower = safeDecodeInPageHook(rawUrl).toLowerCase()
    const hasCommentList = lower.includes('comment/list') || lower.includes('comment%2flist')
    const hasComment = lower.includes('comment')
    const hasContext = lower.includes('cursor') ||
      lower.includes('has_more') ||
      lower.includes('aweme') ||
      lower.includes('reply')
    return hasCommentList || (hasComment && hasContext)
  }
  function pageHookShouldRead(url: string, contentType: string): boolean {
    if (!urlIsDouyinRelatedInPageHook(url)) return false
    if (pageHookUrlLooksLikeComment(url)) return true
    return String(contentType || '').toLowerCase().includes('json')
  }
  function pageHookBodyLooksCommentJson(body: string): boolean {
    const trimmed = body.trim()
    if (!trimmed || (trimmed[0] !== '{' && trimmed[0] !== '[')) return false
    let parsed: unknown
    try {
      parsed = JSON.parse(trimmed)
    } catch {
      return false
    }
    const signals = {
      comment: false,
      commentList: false,
      commentId: false,
      cursor: false,
      hasMore: false,
      aweme: false,
      list: false,
    }
    let visited = 0
    const visit = (value: unknown, depth: number): void => {
      if (visited > 300 || depth > 6 || value == null) return
      visited += 1
      if (Array.isArray(value)) {
        value.slice(0, 30).forEach(item => visit(item, depth + 1))
        return
      }
      if (typeof value !== 'object') return
      for (const [rawKey, child] of Object.entries(value as Record<string, unknown>).slice(0, 80)) {
        const childKey = rawKey.toLowerCase()
        if (childKey.includes('comment')) signals.comment = true
        if (childKey.includes('comment_list') || childKey.includes('comments')) signals.commentList = true
        if (childKey === 'cid' || childKey.includes('comment_id')) signals.commentId = true
        if (childKey.includes('cursor')) signals.cursor = true
        if (childKey === 'has_more' || childKey === 'hasmore' || childKey.includes('has_more')) signals.hasMore = true
        if (childKey.includes('aweme')) signals.aweme = true
        if (childKey === 'list' || childKey.endsWith('_list')) signals.list = true
        visit(child, depth + 1)
      }
    }
    visit(parsed, 0)
    return (signals.comment || signals.commentList || signals.commentId)
      && (signals.cursor || signals.hasMore || signals.aweme || signals.list)
  }
  function pageHookBodyByteLength(body: string): number {
    try {
      return new TextEncoder().encode(body).byteLength
    } catch {
      return body.length
    }
  }
  function pageHookMaybeCapture(
    state: PageHookState,
    item: { url: string; status: number; body: string; capturedAtMs: number; source: string },
  ): void {
    state.seen += 1
    if (!item.url || !urlIsDouyinRelatedInPageHook(item.url)) {
      state.skipped += 1
      return
    }
    if (typeof item.body !== 'string' || item.body.length <= 0) {
      state.skipped += 1
      return
    }
    const bodyBytes = pageHookBodyByteLength(item.body)
    if (bodyBytes > state.maxBodyBytes) {
      state.skipped += 1
      return
    }
    if (!pageHookUrlLooksLikeComment(item.url) && !pageHookBodyLooksCommentJson(item.body)) {
      state.skipped += 1
      return
    }
    state.pages.push({
      url: item.url,
      requestId: `pagehook:${item.source}:${state.nextId++}`,
      status: Number.isFinite(item.status) ? Math.round(item.status) : 0,
      body: item.body,
      capturedAtMs: item.capturedAtMs,
    })
    if (state.pages.length > 80) {
      state.pages.splice(0, state.pages.length - 80)
    }
  }
  const win = window as unknown as Record<string, PageHookState | undefined>
  const existing = win[key]
  if (existing?.installed) {
    existing.capturing = true
    existing.maxBodyBytes = normalizePageHookBytes(maxBodyBytes)
    existing.pages = []
    return {
      installed: true,
      reused: true,
      fetchWrapped: existing.fetchWrapped === true,
      xhrWrapped: existing.xhrWrapped === true,
    }
  }

  const state: PageHookState = {
    installed: true,
    capturing: true,
    maxBodyBytes: normalizePageHookBytes(maxBodyBytes),
    nextId: 1,
    pages: [],
    seen: 0,
    skipped: 0,
    errors: 0,
    fetchWrapped: false,
    xhrWrapped: false,
  }
  win[key] = state

  try {
    const originalFetch = window.fetch
    if (typeof originalFetch === 'function') {
      state.originalFetch = originalFetch
      const wrappedFetch = async function mateclawDouyinFetch(
        this: unknown,
        input: RequestInfo | URL,
        init?: RequestInit,
      ): Promise<Response> {
        const response = await originalFetch.apply(this, [input, init])
        try {
          if (state.capturing) {
            const url = response.url || requestInputUrl(input)
            const contentType = response.headers?.get?.('content-type') || ''
            if (pageHookShouldRead(url, contentType)) {
              void response.clone().text().then(body => {
                pageHookMaybeCapture(state, {
                  url,
                  status: response.status,
                  body,
                  capturedAtMs: Date.now(),
                  source: 'fetch',
                })
              }).catch(() => {
                state.errors += 1
              })
            }
          }
        } catch {
          state.errors += 1
        }
        return response
      }
      Object.defineProperty(wrappedFetch, '__mateclawWrapped', { value: true })
      window.fetch = wrappedFetch as typeof window.fetch
      state.fetchWrapped = true
    }
  } catch {
    state.errors += 1
  }

  try {
    const proto = XMLHttpRequest.prototype as PageHookXhrPrototype
    if (typeof proto.open === 'function' && typeof proto.send === 'function') {
      state.originalXhrOpen = proto.open
      state.originalXhrSend = proto.send
      proto.open = function mateclawDouyinXhrOpen(
        this: XMLHttpRequest & PageHookXhrInstance,
        method: string,
        url: string | URL,
        async?: boolean,
        username?: string | null,
        password?: string | null,
      ) {
        this.__mateclawUrl = typeof url === 'string' ? url : String(url)
        return state.originalXhrOpen!.apply(this, arguments as unknown as Parameters<XMLHttpRequest['open']>)
      } as XMLHttpRequest['open']
      proto.send = function mateclawDouyinXhrSend(
        this: XMLHttpRequest & PageHookXhrInstance,
        body?: Document | XMLHttpRequestBodyInit | null,
      ) {
        try {
          this.addEventListener('loadend', () => {
            try {
              if (!state.capturing) return
              const url = this.responseURL || this.__mateclawUrl || ''
              const contentType = this.getResponseHeader?.('content-type') || ''
              if (!pageHookShouldRead(url, contentType)) return
              if (this.responseType && this.responseType !== 'text') {
                state.skipped += 1
                return
              }
              pageHookMaybeCapture(state, {
                url,
                status: this.status,
                body: String(this.responseText || ''),
                capturedAtMs: Date.now(),
                source: 'xhr',
              })
            } catch {
              state.errors += 1
            }
          })
        } catch {
          state.errors += 1
        }
        return state.originalXhrSend!.apply(this, arguments as unknown as Parameters<XMLHttpRequest['send']>)
      } as XMLHttpRequest['send']
      state.xhrWrapped = true
    }
  } catch {
    state.errors += 1
  }

  return {
    installed: true,
    fetchWrapped: state.fetchWrapped,
    xhrWrapped: state.xhrWrapped,
  }
}

function drainDouyinCommentNetworkPageHook(maxPages: number): PageHookDrainResult {
  const key = '__MATECLAW_DOUYIN_COMMENT_NETWORK__'
  const state = (window as unknown as Record<string, PageHookState | undefined>)[key]
  if (!state?.installed) {
    return { installed: false, pages: [], seen: 0, skipped: 0, errors: 0 }
  }
  const limit = Math.max(0, Math.min(50, Math.floor(Number(maxPages) || 0)))
  const pages = state.pages.splice(0, limit)
  return {
    installed: true,
    pages,
    seen: state.seen,
    skipped: state.skipped,
    errors: state.errors,
  }
}

function stopDouyinCommentNetworkPageHook(): { installed: boolean; stopped: boolean } {
  const key = '__MATECLAW_DOUYIN_COMMENT_NETWORK__'
  const state = (window as unknown as Record<string, PageHookState | undefined>)[key]
  if (!state?.installed) return { installed: false, stopped: false }
  state.capturing = false
  state.pages = []
  return { installed: true, stopped: true }
}

interface PageHookState {
  installed: boolean
  capturing: boolean
  maxBodyBytes: number
  nextId: number
  pages: PageHookPage[]
  seen: number
  skipped: number
  errors: number
  fetchWrapped: boolean
  xhrWrapped: boolean
  originalFetch?: typeof window.fetch
  originalXhrOpen?: XMLHttpRequest['open']
  originalXhrSend?: XMLHttpRequest['send']
}

type PageHookXhrPrototype = XMLHttpRequest['prototype'] & {
  open: XMLHttpRequest['open']
  send: XMLHttpRequest['send']
}

interface PageHookXhrInstance {
  __mateclawUrl?: string
}

function pageHookMaybeCapture(
  state: PageHookState,
  item: { url: string; status: number; body: string; capturedAtMs: number; source: string },
): void {
  state.seen += 1
  if (!item.url || !urlIsDouyinRelatedInPageHook(item.url)) {
    state.skipped += 1
    return
  }
  if (typeof item.body !== 'string' || item.body.length <= 0) {
    state.skipped += 1
    return
  }
  const bodyBytes = pageHookBodyByteLength(item.body)
  if (bodyBytes > state.maxBodyBytes) {
    state.skipped += 1
    return
  }
  if (!pageHookUrlLooksLikeComment(item.url) && !pageHookBodyLooksCommentJson(item.body)) {
    state.skipped += 1
    return
  }
  state.pages.push({
    url: item.url,
    requestId: `pagehook:${item.source}:${state.nextId++}`,
    status: Number.isFinite(item.status) ? Math.round(item.status) : 0,
    body: item.body,
    capturedAtMs: item.capturedAtMs,
  })
  if (state.pages.length > 80) {
    state.pages.splice(0, state.pages.length - 80)
  }
}

function pageHookShouldRead(url: string, contentType: string): boolean {
  if (!urlIsDouyinRelatedInPageHook(url)) return false
  if (pageHookUrlLooksLikeComment(url)) return true
  return String(contentType || '').toLowerCase().includes('json')
}

function pageHookUrlLooksLikeComment(rawUrl: string): boolean {
  const lower = safeDecodeInPageHook(rawUrl).toLowerCase()
  const hasCommentList = lower.includes('comment/list') || lower.includes('comment%2flist')
  const hasComment = lower.includes('comment')
  const hasContext = lower.includes('cursor') ||
    lower.includes('has_more') ||
    lower.includes('aweme') ||
    lower.includes('reply')
  return hasCommentList || (hasComment && hasContext)
}

function pageHookBodyLooksCommentJson(body: string): boolean {
  const trimmed = body.trim()
  if (!trimmed || (trimmed[0] !== '{' && trimmed[0] !== '[')) return false
  let parsed: unknown
  try {
    parsed = JSON.parse(trimmed)
  } catch {
    return false
  }
  const signals = {
    comment: false,
    commentList: false,
    commentId: false,
    cursor: false,
    hasMore: false,
    aweme: false,
    list: false,
  }
  let visited = 0
  const visit = (value: unknown, depth: number): void => {
    if (visited > 300 || depth > 6 || value == null) return
    visited += 1
    if (Array.isArray(value)) {
      value.slice(0, 30).forEach(item => visit(item, depth + 1))
      return
    }
    if (typeof value !== 'object') return
    for (const [rawKey, child] of Object.entries(value as Record<string, unknown>).slice(0, 80)) {
      const key = rawKey.toLowerCase()
      if (key.includes('comment')) signals.comment = true
      if (key.includes('comment_list') || key.includes('comments')) signals.commentList = true
      if (key === 'cid' || key.includes('comment_id')) signals.commentId = true
      if (key.includes('cursor')) signals.cursor = true
      if (key === 'has_more' || key === 'hasmore' || key.includes('has_more')) signals.hasMore = true
      if (key.includes('aweme')) signals.aweme = true
      if (key === 'list' || key.endsWith('_list')) signals.list = true
      visit(child, depth + 1)
    }
  }
  visit(parsed, 0)
  return (signals.comment || signals.commentList || signals.commentId)
    && (signals.cursor || signals.hasMore || signals.aweme || signals.list)
}

function requestInputUrl(input: RequestInfo | URL): string {
  if (typeof input === 'string') return input
  if (input instanceof URL) return String(input)
  return input?.url || ''
}

function urlIsDouyinRelatedInPageHook(rawUrl: string): boolean {
  const host = safeHostInPageHook(rawUrl)
  return host.includes('douyin.com') ||
    host.includes('iesdouyin.com') ||
    host.includes('amemv.com')
}

function safeDecodeInPageHook(value: string): string {
  try {
    return decodeURIComponent(value)
  } catch {
    return value
  }
}

function safeHostInPageHook(value: string): string {
  try {
    return new URL(value, location.href).hostname.toLowerCase()
  } catch {
    return String(value || '').toLowerCase()
  }
}

function normalizePageHookBytes(value: number): number {
  if (!Number.isFinite(value)) return 256 * 1024
  return Math.max(1, Math.min(1024 * 1024, Math.floor(value)))
}

function pageHookBodyByteLength(body: string): number {
  try {
    return new TextEncoder().encode(body).byteLength
  } catch {
    return body.length
  }
}

function logNetworkDebug(event: string, payload: Record<string, unknown>): void {
  if (!DEBUG_DOUYIN_COMMENT_NETWORK) return
  try {
    console.info('[mateclaw][douyin_comment_network]', JSON.stringify({
      event,
      ...payload,
    }))
  } catch {
    // Debug logging must never affect capture.
  }
}

function logSkippedResponse(
  session: CaptureSession,
  reason: string,
  payload: Record<string, unknown>,
): void {
  const url = String(payload.url ?? '')
  const lower = safeDecode(url).toLowerCase()
  const interesting = urlIsDouyinRelated(url) ||
    lower.includes('comment') ||
    lower.includes('aweme') ||
    lower.includes('cursor')
  if (!interesting || session.skippedDebugCount >= 80) return
  session.skippedDebugCount += 1
  logNetworkDebug('response_skipped', {
    reason,
    skippedDebugCount: session.skippedDebugCount,
    ...payload,
  })
}

function logRawNetworkEvent(session: CaptureSession, event: DebuggerEvent): void {
  if (session.rawDebugCount >= 80) return
  if (event.method === 'Network.requestWillBeSent') {
    const params = event.params as NetworkRequestWillBeSentEvent
    const url = params?.request?.url
    if (typeof url !== 'string' || !url) return
    session.rawDebugCount += 1
    logNetworkDebug('raw_request', {
      requestId: params.requestId ?? '',
      sessionId: event.sessionId ?? '',
      targetType: targetTypeForSession(session, event.sessionId) ?? '',
      type: params.type ?? '',
      method: params.request?.method ?? '',
      url,
      rawDebugCount: session.rawDebugCount,
    })
    pushRawSample(session, {
      event: 'request',
      requestId: params.requestId ?? '',
      sessionId: event.sessionId ?? '',
      targetType: targetTypeForSession(session, event.sessionId) ?? '',
      type: params.type ?? '',
      method: params.request?.method ?? '',
      url,
    })
    return
  }
  if (event.method === 'Network.responseReceived') {
    const params = event.params as {
      requestId?: string
      type?: string
      response?: { url?: string; status?: number; mimeType?: string; headers?: Record<string, unknown> }
    }
    const url = params?.response?.url
    if (typeof url !== 'string' || !url) return
    session.rawDebugCount += 1
    logNetworkDebug('raw_response', {
      requestId: params.requestId ?? '',
      sessionId: event.sessionId ?? '',
      targetType: targetTypeForSession(session, event.sessionId) ?? '',
      type: params.type ?? '',
      url,
      status: params.response?.status ?? 0,
      mimeType: params.response?.mimeType ?? '',
      rawDebugCount: session.rawDebugCount,
    })
    pushRawSample(session, {
      event: 'response',
      requestId: params.requestId ?? '',
      sessionId: event.sessionId ?? '',
      targetType: targetTypeForSession(session, event.sessionId) ?? '',
      type: params.type ?? '',
      url,
      status: params.response?.status ?? 0,
      mimeType: params.response?.mimeType ?? '',
    })
  }
}

function responseKey(sessionId: string | undefined, requestId: string): string {
  return `${sessionId || 'root'}:${requestId}`
}

function targetTypeForSession(session: CaptureSession, sessionId?: string): string | undefined {
  if (!sessionId) return undefined
  return session.childSessions.get(sessionId)?.type
}

function summarizeChildSessions(session: CaptureSession): Array<Record<string, unknown>> {
  return [...session.childSessions.values()].slice(0, 20).map(child => ({
    sessionId: child.sessionId,
    targetId: child.targetId,
    type: child.type,
    networkEnabled: child.networkEnabled === true,
    url: child.url ?? '',
    title: child.title ?? '',
  }))
}

function pushRawSample(session: CaptureSession, sample: Record<string, unknown>): void {
  if (session.rawSamples.length >= 40) return
  session.rawSamples.push(sample)
}

function normalizeLimits(params: DouyinCommentNetworkParams): CaptureLimits {
  return {
    maxPages: boundedInteger(params.maxPages, DEFAULT_MAX_PAGES, 1, MAX_PAGES),
    maxBodyBytes: boundedInteger(params.maxBodyBytes, DEFAULT_MAX_BODY_BYTES, 1, MAX_BODY_BYTES),
    ttlMs: boundedInteger(params.ttlMs, DEFAULT_TTL_MS, 1_000, MAX_TTL_MS),
  }
}

function boundedInteger(value: unknown, fallback: number, min: number, max: number): number {
  if (typeof value !== 'number' || !Number.isFinite(value)) return fallback
  return Math.min(max, Math.max(min, Math.floor(value)))
}

async function settleInflight(session: CaptureSession, deadlineMs: number): Promise<void> {
  const pending = [...session.inflight]
  if (pending.length === 0) return
  const waitMs = Math.min(
    DRAIN_INFLIGHT_WAIT_MS,
    Math.max(0, Math.floor(Number.isFinite(deadlineMs) ? deadlineMs : DRAIN_INFLIGHT_WAIT_MS)),
  )
  await Promise.race([
    Promise.allSettled(pending),
    new Promise(resolve => setTimeout(resolve, waitMs)),
  ])
}

function mapDebuggerError(err: unknown): Error {
  if (!(err instanceof SessionDetachedError)) {
    return err instanceof Error ? err : new Error(String(err))
  }

  if (err.reason === 'target_closed') {
    return new ActionFailureError('NO_TARGET_TAB', err.message, false)
  }
  if (err.reason === 'canceled_by_user' || err.reason === 'replaced_with_devtools') {
    return new ActionFailureError('DEVTOOLS_OPEN', err.message, true)
  }
  return new ActionFailureError('SESSION_DETACHED', err.message, true)
}

function isNetworkResponse(value: unknown): value is NetworkResponse {
  if (!value || typeof value !== 'object') return false
  const response = value as Partial<NetworkResponse>
  return typeof response.url === 'string' && typeof response.status === 'number'
}

function normalizeStatus(value: number): number {
  return Number.isFinite(value) ? Math.round(value) : 0
}

function finiteNumber(value: unknown): number | undefined {
  return typeof value === 'number' && Number.isFinite(value) ? value : undefined
}

function responseLooksJson(response: NetworkResponse): boolean {
  const mime = String(response.mimeType || '').toLowerCase()
  if (mime.includes('json')) return true
  const headers = response.headers ?? {}
  for (const [key, value] of Object.entries(headers)) {
    if (key.toLowerCase() === 'content-type' && String(value).toLowerCase().includes('json')) {
      return true
    }
  }
  return false
}

function urlLooksLikeDouyinCommentResponse(rawUrl: string): boolean {
  const lower = safeDecode(rawUrl).toLowerCase()
  if (!urlIsDouyinRelated(rawUrl)) return false

  const hasComment = lower.includes('comment')
  const hasCommentList = lower.includes('comment/list') ||
    lower.includes('comment%2flist') ||
    lower.includes('/comment/list')
  const hasContext = lower.includes('cursor') ||
    lower.includes('has_more') ||
    lower.includes('aweme') ||
    lower.includes('reply')

  return hasCommentList || (hasComment && hasContext)
}

function urlIsDouyinRelated(rawUrl: string): boolean {
  const host = safeHost(rawUrl)
  return host.includes('douyin.com') ||
    host.includes('iesdouyin.com') ||
    host.includes('amemv.com')
}

function bodyLooksLikeDouyinCommentJson(body: string, base64Encoded: boolean): boolean {
  if (base64Encoded) return false
  const trimmed = body.trim()
  if (!trimmed || (trimmed[0] !== '{' && trimmed[0] !== '[')) return false

  let parsed: unknown
  try {
    parsed = JSON.parse(trimmed)
  } catch {
    return false
  }

  const signals = collectJsonSignals(parsed)
  const commentish = signals.comment || signals.commentList || signals.commentId
  const paginationOrAweme = signals.cursor || signals.hasMore || signals.aweme || signals.list
  return commentish && paginationOrAweme
}

interface JsonSignals {
  comment: boolean
  commentList: boolean
  commentId: boolean
  cursor: boolean
  hasMore: boolean
  aweme: boolean
  list: boolean
}

function collectJsonSignals(root: unknown): JsonSignals {
  const signals: JsonSignals = {
    comment: false,
    commentList: false,
    commentId: false,
    cursor: false,
    hasMore: false,
    aweme: false,
    list: false,
  }
  let visited = 0

  const visit = (value: unknown, depth: number): void => {
    if (visited > 400 || depth > 6 || value == null) return
    visited += 1

    if (Array.isArray(value)) {
      for (const item of value.slice(0, 40)) visit(item, depth + 1)
      return
    }

    if (typeof value !== 'object') return
    for (const [key, child] of Object.entries(value as Record<string, unknown>).slice(0, 80)) {
      markKey(key, signals)
      visit(child, depth + 1)
    }
  }

  visit(root, 0)
  return signals
}

function markKey(rawKey: string, signals: JsonSignals): void {
  const key = rawKey.toLowerCase()
  if (key.includes('comment')) signals.comment = true
  if (key.includes('comment_list') || key.includes('comments')) signals.commentList = true
  if (key === 'cid' || key.includes('comment_id')) signals.commentId = true
  if (key === 'cursor' || key.endsWith('_cursor') || key.includes('cursor')) signals.cursor = true
  if (key === 'has_more' || key === 'hasmore' || key.includes('has_more')) signals.hasMore = true
  if (key.includes('aweme')) signals.aweme = true
  if (key === 'list' || key.endsWith('_list')) signals.list = true
}

function bodyByteLength(body: string, base64Encoded: boolean): number {
  if (base64Encoded) return Math.floor((body.replace(/=+$/u, '').length * 3) / 4)
  const encoder = typeof TextEncoder !== 'undefined' ? new TextEncoder() : undefined
  if (encoder) return encoder.encode(body).byteLength
  return [...body].reduce((total, char) => {
    const code = char.codePointAt(0) ?? 0
    if (code <= 0x7f) return total + 1
    if (code <= 0x7ff) return total + 2
    if (code <= 0xffff) return total + 3
    return total + 4
  }, 0)
}

function safeDecode(value: string): string {
  try {
    return decodeURIComponent(value)
  } catch {
    return value
  }
}

function safeHost(value: string): string {
  try {
    return new URL(value).hostname.toLowerCase()
  } catch {
    return value.toLowerCase()
  }
}
