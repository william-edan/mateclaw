/**
 * TypeScript mirror of the Java `ActionRequest` / `ActionResult` /
 * `ActionPayload` sealed type families. Wire format documented in
 * `docs/specs/edge-protocol.md` §"action.execute" and §"action.result".
 *
 * Discriminated unions on the outer `kind` field; the TS compiler can
 * narrow `params` based on `kind` everywhere we destructure.
 */

// -----------------------------------------------------------------
// TabRef — string-or-number wire shape (matches Java TabRef sealed type)
// -----------------------------------------------------------------

export type TabRef = 'main' | 'active' | number

// -----------------------------------------------------------------
// Per-kind parameter shapes (Java equivalents under
// vip.mate.browser.edge.action.*Payload)
// -----------------------------------------------------------------

export interface NavigateParams {
  url: string
  referer?: string
  wait_for?: 'load' | 'domcontentloaded' | 'network_idle' | 'none'
}

export interface ClickParams {
  x: number
  y: number
  button?: 'left' | 'right' | 'middle'
  click_count?: number
}

export interface TypeParams {
  text: string
  focus_target?: { x: number; y: number }
}

export interface PressKeyParams {
  key: string
}

export interface ScrollParams {
  direction: 'up' | 'down' | 'left' | 'right'
  distance_px: number
  segments?: number
  x?: number
  y?: number
}

export interface ScrollRegionParams {
  regionKey: string
  direction: 'up' | 'down' | 'left' | 'right'
  amount: number
  stopWhen?: {
    type: 'edge' | 'selector_visible' | 'text_visible'
    selector?: string
    text?: string
  }
  segments?: number
}

export interface RegisterRegionParams {
  regionKey: string
  rect: { x: number; y: number; width: number; height: number }
  source?: string
}

export interface DetectRegionParams {
  regionKey: string
  strategy?: 'auto' | 'dom'
}

export interface ExtractRegionParams {
  regionKey: string
  maxItems?: number
  startIndex?: number
}

export interface OpenAuthorFromCommentParams {
  commentText: string
  authorName?: string
  authorProfileUrl?: string
}

export interface ClickProfileActionParams {
  labels: string[]
}

export interface TypeDmDraftParams {
  text: string
  send?: boolean
  sendOnly?: boolean
}

export type CloseTabParams = Record<string, never>

export interface MoveMouseParams {
  x: number
  y: number
  profile?: 'natural' | 'linear'
}

export interface WaitParams {
  strategy: 'time' | 'network_idle' | 'load_state'
  duration_ms?: number
  idle_threshold_ms?: number
  load_state?: 'load' | 'domcontentloaded' | 'network_idle'
}

export interface DouyinCommentNetworkParams {
  op: 'start' | 'drain' | 'stop'
  maxPages?: number
  maxBodyBytes?: number
  ttlMs?: number
}

// -----------------------------------------------------------------
// ActionKind discriminated union
// -----------------------------------------------------------------

export type ActionKind = 'navigate' | 'click' | 'type' | 'press_key' | 'scroll' | 'scroll_region' | 'register_region' | 'detect_region' | 'extract_region' | 'open_author_from_comment' | 'click_profile_action' | 'type_dm_draft' | 'close_tab' | 'move_mouse' | 'wait' | 'douyin_comment_network'

export type ActionParams =
  | { kind: 'navigate';   params: NavigateParams }
  | { kind: 'click';      params: ClickParams }
  | { kind: 'type';       params: TypeParams }
  | { kind: 'press_key';  params: PressKeyParams }
  | { kind: 'scroll';     params: ScrollParams }
  | { kind: 'scroll_region'; params: ScrollRegionParams }
  | { kind: 'register_region'; params: RegisterRegionParams }
  | { kind: 'detect_region'; params: DetectRegionParams }
  | { kind: 'extract_region'; params: ExtractRegionParams }
  | { kind: 'open_author_from_comment'; params: OpenAuthorFromCommentParams }
  | { kind: 'click_profile_action'; params: ClickProfileActionParams }
  | { kind: 'type_dm_draft'; params: TypeDmDraftParams }
  | { kind: 'close_tab'; params: CloseTabParams }
  | { kind: 'move_mouse'; params: MoveMouseParams }
  | { kind: 'wait';       params: WaitParams }
  | { kind: 'douyin_comment_network'; params: DouyinCommentNetworkParams }

// -----------------------------------------------------------------
// ActionRequest envelope (matches Java `ActionRequest` record)
// -----------------------------------------------------------------

export type ActionRequest = {
  msg_id: string
  tab_ref: TabRef
  deadline_ms: number
} & ActionParams

// -----------------------------------------------------------------
// ActionResult sealed union (matches Java `ActionResult.Success/Failure`)
// -----------------------------------------------------------------

export interface ActionSuccess {
  ok: true
  elapsed_ms: number
  payload: Record<string, unknown>
}

export interface ActionFailure {
  ok: false
  code: ActionErrorCode
  message: string
  retryable: boolean
}

export type ActionResult = ActionSuccess | ActionFailure

/**
 * Canonical wire error codes. Match docs/specs/edge-protocol.md.
 * `HANDLER_ERROR` is a fallback when a handler throws something other
 * than an `ActionFailureError` — code wraps it but loses the typed
 * mapping (caller should treat as `retryable=true`).
 */
export type ActionErrorCode =
  | 'TIMEOUT_PAGE_LOAD'
  | 'GROUNDING_AMBIGUOUS'
  | 'NO_TARGET_TAB'
  | 'CANCELLED'
  | 'DEADLINE_EXCEEDED'
  | 'SESSION_DETACHED'
  | 'DEVTOOLS_OPEN'
  | 'UNKNOWN_KIND'
  | 'HANDLER_ERROR'
