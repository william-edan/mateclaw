/**
 * edgeproto — TypeScript mirror of the MateClaw Edge Protocol envelope.
 *
 * See docs/specs/edge-protocol.md for the canonical spec.
 * Mirrors vip.mate.browser.edge.protocol on the Java/Control Plane side.
 *
 * Note: We use string literal union types so the wire format (JSON) maps
 * directly without a marshalling step. Unknown kinds collapse to '__unknown__'
 * at parse-time for forward-compatibility.
 */

/** All known EdgeMessage kind strings (wire format). */
export const Kind = {
  // v1.0 — handshake + liveness
  Hello: 'hello',
  HelloAck: 'hello.ack',
  Heartbeat: 'heartbeat',
  HeartbeatAck: 'heartbeat.ack',
  Ping: 'ping',
  Pong: 'pong',
  Error: 'error',
  // Connection control — CP → bridge → Ext:后端请求扩展断开(桌面"断开连接"经此下行)。
  // bridge 只透传;此处登记 kind 是为了让 parse() 不把它降级成 __unknown__、丢失给扩展的语义。
  ConnectionDisconnect: 'connection.disconnect',
  // v1.1 — atomic browser actions
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
  // v1.1 — unsolicited page-lifecycle events
  EventPageNavigated: 'event.page.navigated',
  EventTabClosed: 'event.tab.closed',
  // Sentinel
  Unknown: '__unknown__',
} as const

export type Kind = (typeof Kind)[keyof typeof Kind]

const knownKinds = new Set<string>(Object.values(Kind))

/**
 * Coerce an arbitrary string to a Kind, collapsing unknown strings to Unknown.
 */
function coerceKind(raw: string): Kind {
  if (knownKinds.has(raw)) {
    return raw as Kind
  }
  return Kind.Unknown
}

/** Canonical on-wire envelope. Field names match docs/specs/edge-protocol.md exactly. */
export interface Message {
  v: 1
  msg_id: string
  kind: Kind
  ts: number
  trace_id: string
  /** Native Host is sole owner; Extension always sends ''. */
  session_id: string
  in_reply_to?: string
  payload?: Record<string, unknown>
}

/**
 * Parse a raw JSON string into a Message.
 * Returns null on malformed JSON.
 * Unknown 'kind' values are normalised to Kind.Unknown.
 */
export function parse(raw: string): Message | null {
  let obj: unknown
  try {
    obj = JSON.parse(raw)
  } catch {
    return null
  }
  if (typeof obj !== 'object' || obj === null) return null
  const o = obj as Record<string, unknown>
  return {
    v: 1,
    msg_id: String(o['msg_id'] ?? ''),
    kind: coerceKind(String(o['kind'] ?? '')),
    ts: typeof o['ts'] === 'number' ? o['ts'] : 0,
    trace_id: String(o['trace_id'] ?? ''),
    session_id: String(o['session_id'] ?? ''),
    in_reply_to: o['in_reply_to'] != null ? String(o['in_reply_to']) : undefined,
    payload: typeof o['payload'] === 'object' && o['payload'] !== null
      ? (o['payload'] as Record<string, unknown>)
      : undefined,
  }
}

/** Parameters for make(). */
interface MakeParams {
  kind: Kind
  msgId?: string
  traceId?: string
  sessionId?: string
  inReplyTo?: string
  payload?: Record<string, unknown>
}

/**
 * Factory to create a well-formed Message with sensible defaults.
 * Uses crypto.randomUUID() for IDs when not provided.
 */
export function make(p: MakeParams): Message {
  return {
    v: 1,
    msg_id: p.msgId ?? randomUUID(),
    kind: p.kind,
    ts: Date.now(),
    trace_id: p.traceId ?? randomUUID(),
    session_id: p.sessionId ?? '',
    in_reply_to: p.inReplyTo,
    payload: p.payload,
  }
}

/** Cross-platform UUID v4 generator (Node 19+ has crypto.randomUUID globally). */
function randomUUID(): string {
  // crypto is available globally in Node 19+ and in all modern browsers.
  return crypto.randomUUID()
}
