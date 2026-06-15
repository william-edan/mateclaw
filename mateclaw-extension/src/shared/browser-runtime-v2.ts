/**
 * Browser Runtime v2 action/message shape shared by the service worker and
 * runtime helpers. The Edge envelope remains v=1; v2 is expressed inside the
 * action payload kind.
 */

export const BrowserRuntimeV2ActionKind = {
  ScrollRegion: 'scroll_region',
  DetectRegion: 'detect_region',
  ExtractRegion: 'extract_region',
} as const

export const BrowserRuntimeV2MessageKind = {
  RegionRegister: 'runtime.region.register',
  RegionClear: 'runtime.region.clear',
} as const

export type BrowserRuntimeV2ActionKind =
  (typeof BrowserRuntimeV2ActionKind)[keyof typeof BrowserRuntimeV2ActionKind]

export type BrowserRuntimeV2MessageKind =
  (typeof BrowserRuntimeV2MessageKind)[keyof typeof BrowserRuntimeV2MessageKind]

export type ScrollRegionDirection = 'up' | 'down' | 'left' | 'right'

export interface ScrollRegionStopWhen {
  /**
   * Skeleton contract for future richer stopping predicates. The v2 frontend
   * currently validates and preserves this value; execution falls back to the
   * compatible scroll handler.
   */
  type: 'edge' | 'selector_visible' | 'text_visible'
  selector?: string
  text?: string
}

export interface ScrollRegionParams {
  regionKey: string
  direction: ScrollRegionDirection
  /** Pixel distance to scroll inside the region. */
  amount: number
  stopWhen?: ScrollRegionStopWhen
  segments?: number
}

export interface ScrollRegionActionRequest {
  kind: typeof BrowserRuntimeV2ActionKind.ScrollRegion
  params: ScrollRegionParams
}

export interface ExtractRegionParams {
  regionKey: string
  maxItems?: number
  startIndex?: number
}

export interface DetectRegionParams {
  regionKey: string
  strategy?: 'auto' | 'dom'
}

export interface DetectRegionActionRequest {
  kind: typeof BrowserRuntimeV2ActionKind.DetectRegion
  params: DetectRegionParams
}

export interface ExtractRegionActionRequest {
  kind: typeof BrowserRuntimeV2ActionKind.ExtractRegion
  params: ExtractRegionParams
}

export interface RuntimeRegionRegisterMessage {
  kind: typeof BrowserRuntimeV2MessageKind.RegionRegister
  payload: {
    regionKey: string
    tabId: number
    rect: { x: number; y: number; width: number; height: number }
    source?: string
  }
}

export interface RuntimeRegionClearMessage {
  kind: typeof BrowserRuntimeV2MessageKind.RegionClear
  payload: {
    tabId?: number
    regionKey?: string
  }
}
