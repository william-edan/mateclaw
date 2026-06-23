import { EdgeMessageKind, makeEdgeMessage, type EdgeMessage } from '../shared/edge-protocol'
import type { TabRefResolver } from './action/tab-ref-resolver'
import type { TabRef } from './action/types'
import type { DebuggerManager } from './debugger-manager'
import type { CDP } from './cdp-types'

// Upper bound on the base64 we'll ship over the edge WS. JPEG (below) keeps a
// viewport capture well under this; the ceiling just guards against a runaway
// HiDPI capture. The SERVER WS text-buffer must be ≥ this (see WebSocketConfig
// MAX_TEXT_BUFFER_BYTES) or the frame is rejected.
const MAX_BASE64_LENGTH = 2_000_000
// Vision grounding only needs to LOCATE elements, not pixel-perfect color — so
// we capture JPEG (5–10× smaller than PNG for a thumbnail-heavy page like
// Douyin search) at a quality that stays small but legible. PNG screenshots
// routinely blew past the size limits and never reached the vision model.
const JPEG_QUALITY = 70

type ScreenshotErrorCode = 'NO_TARGET_TAB' | 'SCREENSHOT_TOO_LARGE' | 'PERMISSION_DENIED'

export interface ScreenshotCaptureHandlerDeps {
  resolver: TabRefResolver
  /** Drives CDP Page.captureScreenshot — captures THIS tab directly (no focus/
   *  active-tab/window-permission quirks of chrome.tabs.captureVisibleTab). */
  debuggerManager: DebuggerManager
  chrome?: typeof globalThis.chrome
  sendUp: (msg: EdgeMessage) => void
  uuid?: () => string
  clock?: () => number
}

export class ScreenshotCaptureHandler {
  constructor(private readonly deps: ScreenshotCaptureHandlerDeps) {}

  async handle(msg: EdgeMessage): Promise<void> {
    if (msg.kind !== EdgeMessageKind.ScreenshotCaptureRequest) return

    const payload = msg.payload ?? {}
    const tabRef = parseTabRef(payload.tab_ref)
    const snapshotId = this.uuid()
    const capturedAt = this.clock()

    let tabId: number | null = null
    if (tabRef !== null) {
      try {
        tabId = await this.deps.resolver.resolve(tabRef)
      } catch (err) {
        this.respondError(
          msg,
          snapshotId,
          capturedAt,
          'NO_TARGET_TAB',
          `tab_ref resolution threw: ${errorMessage(err)}`,
        )
        return
      }
    }

    if (tabId === null) {
      this.respondError(
        msg,
        snapshotId,
        capturedAt,
        'NO_TARGET_TAB',
        `tab_ref ${String(payload.tab_ref)} not resolved`,
      )
      return
    }

    try {
      // Capture via CDP Page.captureScreenshot (chrome.debugger), NOT
      // chrome.tabs.captureVisibleTab. captureVisibleTab only grabs the focused
      // window's ACTIVE tab and throws PERMISSION_DENIED / "No window with id"
      // in many states (background tab, no window focus) — that is why vision
      // grounding kept failing. Page.captureScreenshot targets THIS tab directly
      // regardless of focus/active state, consistent with how we already drive
      // input + read the AX tree over CDP. JPEG keeps the base64 under the WS
      // text-frame limit.
      await this.deps.debuggerManager.attach(tabId)
      try {
        // Pin the capture to CSS-pixel scale. Page.captureScreenshot renders at the
        // display's device-pixel ratio by default (e.g. 1.5× on a scaled Windows
        // display), so the JPEG would be physically larger than the CSS viewport.
        // SoM recomputes its own scale and survives that, but the coord-fallback
        // path in VisionEngine reads the model's x,y straight off the image and
        // would be off by the DPR factor. So we read the visible viewport via
        // Page.getLayoutMetrics and capture it with a clip at scale:1 — image px ==
        // CSS px == the a11y bbox space every grounding path expects. If metrics
        // are unavailable we fall back to a plain capture (native DPR): SoM still
        // works, only the coord path loses precision.
        const cssViewport = await this.readCssViewport(tabId)
        const captureParams: CDP['Page.captureScreenshot']['params'] = {
          format: 'jpeg',
          quality: JPEG_QUALITY,
        }
        if (cssViewport) {
          captureParams.clip = {
            x: cssViewport.x,
            y: cssViewport.y,
            width: cssViewport.w,
            height: cssViewport.h,
            scale: 1,
          }
          captureParams.captureBeyondViewport = false
        }
        const shot = await this.deps.debuggerManager.send(tabId, 'Page.captureScreenshot', captureParams)
        const base64 = shot?.data ?? ''
        if (!base64) {
          this.respondError(
            msg, snapshotId, capturedAt, 'PERMISSION_DENIED',
            'Page.captureScreenshot returned empty data',
          )
          return
        }
        if (base64.length > MAX_BASE64_LENGTH) {
          this.respondError(
            msg,
            snapshotId,
            capturedAt,
            'SCREENSHOT_TOO_LARGE',
            `payload ${base64.length} bytes exceeds ${MAX_BASE64_LENGTH}`,
          )
          return
        }

        // Report the CSS viewport the clip used (image px == CSS px at scale:1).
        // When metrics were unavailable, fall back to tab metadata dims (tabs.get
        // needs no capture permission and tolerates failure).
        let viewport: { w: number; h: number }
        if (cssViewport) {
          viewport = { w: cssViewport.w, h: cssViewport.h }
        } else {
          const tab = await this.chrome().tabs.get(tabId).catch(() => null)
          viewport = { w: tab?.width ?? 1280, h: tab?.height ?? 800 }
        }
        this.deps.sendUp(makeEdgeMessage({
          kind: EdgeMessageKind.ScreenshotCaptureResponse,
          traceId: msg.trace_id,
          inReplyTo: msg.msg_id,
          payload: {
            snapshot_id: snapshotId,
            captured_at_ms: capturedAt,
            tab_ref: tabId,
            format: 'jpeg',
            data_base64: base64,
            viewport,
            actual_dimensions: viewport,
          },
        }))
      } finally {
        // 不立即 detach:否则 CDP 调试横幅每次截图都"出现→消失",页面被顶下再弹回、连续截图时整页
        // 反复上下跳动(用户看到的"页面变形")。连续截图复用同一会话;空闲后由防抖延迟 detach 释放、
        // 横幅消失。tab 关闭时 Chrome 自动 detach,无泄漏。
        this.deps.debuggerManager.scheduleIdleDetach(tabId)
      }
    } catch (err) {
      this.respondError(
        msg,
        snapshotId,
        capturedAt,
        'PERMISSION_DENIED',
        errorMessage(err),
      )
    }
  }

  private respondError(
    req: EdgeMessage,
    snapshotId: string,
    capturedAt: number,
    code: ScreenshotErrorCode,
    message: string,
  ): void {
    this.deps.sendUp(makeEdgeMessage({
      kind: EdgeMessageKind.ScreenshotCaptureResponse,
      traceId: req.trace_id,
      inReplyTo: req.msg_id,
      payload: {
        snapshot_id: snapshotId,
        captured_at_ms: capturedAt,
        tab_ref: -1,
        error: { code, message },
      },
    }))
  }

  private uuid(): string {
    return (this.deps.uuid ?? crypto.randomUUID.bind(crypto))()
  }

  private clock(): number {
    return (this.deps.clock ?? Date.now)()
  }

  private chrome(): typeof globalThis.chrome {
    return this.deps.chrome ?? globalThis.chrome
  }

  /**
   * Read the visible viewport in CSS px (scroll offset + size) via
   * Page.getLayoutMetrics. Prefers the visual viewport (reflects pinch-zoom),
   * falling back to the layout viewport. Returns null on any failure or a
   * degenerate size so the caller captures without a clip (native DPR). Never
   * throws.
   */
  private async readCssViewport(
    tabId: number,
  ): Promise<{ x: number; y: number; w: number; h: number } | null> {
    try {
      const m = await this.deps.debuggerManager.send(tabId, 'Page.getLayoutMetrics', {})
      const v = m?.cssVisualViewport
      const l = m?.cssLayoutViewport
      const pick = v && v.clientWidth > 0 && v.clientHeight > 0 ? v : l
      if (!pick || !(pick.clientWidth > 0) || !(pick.clientHeight > 0)) return null
      return {
        x: Math.max(0, Math.round(pick.pageX ?? 0)),
        y: Math.max(0, Math.round(pick.pageY ?? 0)),
        w: Math.round(pick.clientWidth),
        h: Math.round(pick.clientHeight),
      }
    } catch {
      return null
    }
  }
}

function parseTabRef(value: unknown): TabRef | null {
  if (value === 'main' || value === 'active') return value
  if (typeof value === 'number' && Number.isInteger(value)) return value
  return null
}

function errorMessage(err: unknown): string {
  if (err instanceof Error) return err.message
  return String(err)
}
