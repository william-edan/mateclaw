// Canonical Edge protocol mirror for the Extension.
// See docs/specs/edge-protocol.md and docs/specs/phase-3.1-contract.md §1.
//
// AUDIT P0-1: The Extension always emits session_id="" on the HELLO frame; the
// server replies HELLO_ACK carrying the real session_id. Who stamps that id on
// subsequent OUTBOUND frames depends on the transport:
//   - NH (Native Messaging) mode: the Native Host owns the session_id and
//     overrides any value it receives from stdin.
//   - Direct WSS mode (Phase 3.1): there is no Native Host, so the service
//     worker (DirectBridgeClient) captures session_id from HELLO_ACK and stamps
//     it on every outbound frame whose session_id is still "" — i.e. the SW
//     takes over the role the Native Host played in NH mode.
// makeEdgeMessage therefore still mints session_id="" unconditionally; the
// active transport stamps the real id on the way out.

export const EdgeMessageKind = {
  // v1.0 — handshake + liveness
  Hello: 'hello',
  HelloAck: 'hello.ack',
  Heartbeat: 'heartbeat',
  HeartbeatAck: 'heartbeat.ack',
  Ping: 'ping',
  Pong: 'pong',
  Error: 'error',
  // Connection control — CP → Ext:后端请求扩展断开(桌面"断开连接"经 session 下行到此)。
  ConnectionDisconnect: 'connection.disconnect',
  // v1.1 — atomic browser actions (Phase 2 P1)
  ActionExecute: 'action.execute',
  ActionResult: 'action.result',
  ActionCancel: 'action.cancel',
  // v1.1 — visual indicators
  IndicatorShow: 'indicator.show',
  IndicatorHide: 'indicator.hide',
  IndicatorCursor: 'indicator.cursor',
  IndicatorToolUseHide: 'indicator.tool_use_hide',
  IndicatorToolUseShow: 'indicator.tool_use_show',
  IndicatorStopClicked: 'indicator.stop_clicked',
  // v1.1 — accessibility tree snapshot
  A11ySnapshotRequest: 'a11y.snapshot.request',
  A11ySnapshotResponse: 'a11y.snapshot.response',
  // v1.2 — screenshot capture (Phase 3 T3.2)
  ScreenshotCaptureRequest: 'screenshot.capture.request',
  ScreenshotCaptureResponse: 'screenshot.capture.response',
  // v2 — Browser Runtime structured envelopes
  BrowserActionRequest: 'browser.action.request',
  BrowserActionResult: 'browser.action.result',
  BrowserObservationCapture: 'browser.observation.capture',
  BrowserObservationResult: 'browser.observation.result',
  BrowserArtifactUploadChunk: 'browser.artifact.upload_chunk',
  BrowserTelemetryBatch: 'browser.telemetry.batch',
  BrowserHumanTakeover: 'browser.human.takeover',
  // v1.1 — unsolicited page-lifecycle events
  EventPageNavigated: 'event.page.navigated',
  EventTabClosed: 'event.tab.closed',
  // Sentinel
  Unknown: '__unknown__',
} as const
export type EdgeMessageKind = (typeof EdgeMessageKind)[keyof typeof EdgeMessageKind]

const knownKinds = new Set<string>(Object.values(EdgeMessageKind))

export interface EdgeMessage {
  v: 1
  msg_id: string
  kind: EdgeMessageKind
  ts: number
  trace_id: string
  /** Always empty string from the Extension — NH is sole owner. */
  session_id: string
  in_reply_to?: string
  payload?: Record<string, unknown>
}

export function makeEdgeMessage(p: {
  kind: EdgeMessageKind
  /**
   * Must be "" or omitted — makeEdgeMessage never sets a real session_id. The
   * active transport (NH host, or the SW in direct mode) stamps it post-ACK.
   */
  sessionId?: string
  traceId?: string
  inReplyTo?: string
  payload?: Record<string, unknown>
}): EdgeMessage {
  return {
    v: 1,
    msg_id: crypto.randomUUID(),
    kind: p.kind,
    ts: Date.now(),
    trace_id: p.traceId ?? crypto.randomUUID(),
    session_id: '',   // AUDIT P0-1: always empty; NH stamps the real id
    in_reply_to: p.inReplyTo,
    payload: p.payload ?? {},
  }
}

export function parseEdgeMessage(raw: string): EdgeMessage | null {
  let obj: unknown
  try {
    obj = JSON.parse(raw)
  } catch {
    return null
  }
  if (
    !obj || typeof obj !== 'object' ||
    (obj as Record<string, unknown>).v !== 1 ||
    typeof (obj as Record<string, unknown>).msg_id !== 'string' ||
    typeof (obj as Record<string, unknown>).kind !== 'string'
  ) {
    return null
  }
  const m = obj as EdgeMessage
  if (!knownKinds.has(m.kind)) {
    return { ...m, kind: EdgeMessageKind.Unknown }
  }
  return m
}
