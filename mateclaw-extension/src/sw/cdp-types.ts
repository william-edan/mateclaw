export interface CDP {
  'Page.navigate': {
    params: {
      url: string
      referrer?: string
      transitionType?: string
    }
    result: {
      frameId: string
      loaderId?: string
      errorText?: string
    }
  }
  'Input.dispatchMouseEvent': {
    params: {
      type: 'mousePressed' | 'mouseReleased' | 'mouseMoved' | 'mouseWheel'
      x: number
      y: number
      button?: 'none' | 'left' | 'middle' | 'right' | 'back' | 'forward'
      buttons?: number
      clickCount?: number
      deltaX?: number
      deltaY?: number
      modifiers?: number
    }
    result: Record<string, never>
  }
  'Input.dispatchKeyEvent': {
    params: {
      type: 'keyDown' | 'keyUp' | 'rawKeyDown' | 'char'
      text?: string
      key?: string
      code?: string
      windowsVirtualKeyCode?: number
      nativeVirtualKeyCode?: number
      unmodifiedText?: string
      modifiers?: number
    }
    result: Record<string, never>
  }
  'Input.insertText': {
    params: {
      text: string
    }
    result: Record<string, never>
  }
  'Network.enable': {
    params: {
      maxTotalBufferSize?: number
      maxResourceBufferSize?: number
      maxPostDataSize?: number
    }
    result: Record<string, never>
  }
  'Network.disable': {
    params: Record<string, never>
    result: Record<string, never>
  }
  'Network.getResponseBody': {
    params: {
      requestId: string
    }
    result: {
      body: string
      base64Encoded: boolean
    }
  }
  'Target.setAutoAttach': {
    params: {
      autoAttach: boolean
      waitForDebuggerOnStart: boolean
      flatten?: boolean
      filter?: Array<{
        type?: string
        exclude?: boolean
      }>
    }
    result: Record<string, never>
  }
  'Page.captureScreenshot': {
    params: {
      format?: 'jpeg' | 'png' | 'webp'
      quality?: number
      /**
       * Capture region in CSS px relative to the page (document) origin, plus a
       * render `scale`. We pass `scale: 1` so the output is exactly CSS-pixel
       * sized REGARDLESS of the display device-pixel ratio / OS scaling —
       * keeping image px == CSS px == the a11y bbox space the click/grounding
       * paths use. Omit to capture the visual viewport at the native DPR.
       */
      clip?: { x: number; y: number; width: number; height: number; scale: number }
      /** When false (our default with a clip), restrict to the on-screen region. */
      captureBeyondViewport?: boolean
    }
    result: {
      data: string
    }
  }
  /**
   * Page layout metrics. We read the CSS-pixel viewports to build a `scale: 1`
   * screenshot clip (see {@code Page.captureScreenshot}). `pageX/pageY` are the
   * scroll offset (document coords of the visible top-left); `clientWidth/Height`
   * the viewport size — together they position the clip over exactly what is
   * visible, at CSS-pixel scale.
   */
  'Page.getLayoutMetrics': {
    params: Record<string, never>
    result: {
      cssLayoutViewport: {
        pageX: number
        pageY: number
        clientWidth: number
        clientHeight: number
      }
      cssVisualViewport?: {
        offsetX: number
        offsetY: number
        pageX: number
        pageY: number
        clientWidth: number
        clientHeight: number
        scale: number
        zoom?: number
      }
      cssContentSize?: { x: number; y: number; width: number; height: number }
    }
  }
  'Runtime.evaluate': {
    params: {
      expression: string
      awaitPromise?: boolean
      returnByValue?: boolean
      userGesture?: boolean
    }
    result: {
      result: {
        type: string
        value?: unknown
        description?: string
        objectId?: string
      }
      exceptionDetails?: unknown
    }
  }
  /**
   * Full accessibility tree for the inspected page (top frame; child frames are
   * inlined by Chrome). Each node carries its computed role/name, the
   * {@link AXNode.backendDOMNodeId} pointing back at the DOM node (used to
   * resolve layout bounds via {@code DOM.getBoxModel}), an {@code ignored}
   * flag for AX-irrelevant nodes, and {@code childIds} preserving tree order.
   * Consumed by the CDP-native a11y extractor.
   */
  'Accessibility.getFullAXTree': {
    params: {
      /** Max depth; omitted = whole tree. */
      depth?: number
      /** Restrict to a frame; omitted = top frame. */
      frameId?: string
    }
    result: {
      nodes: AXNode[]
    }
  }
  /**
   * Box model (content/padding/border/margin quads, in CSS pixels relative to
   * the top-level layout viewport — same space as getBoundingClientRect in the
   * main frame) for a single DOM node addressed by backend id. The extractor
   * calls this once per kept AX node to attach a bbox.
   */
  'DOM.getBoxModel': {
    params: {
      backendNodeId?: number
      nodeId?: number
      objectId?: string
    }
    result: {
      model: BoxModel
    }
  }
}

export interface CDPEvents {
  'Network.requestWillBeSent': NetworkRequestWillBeSentEvent
  'Network.responseReceived': NetworkResponseReceivedEvent
  'Network.loadingFinished': NetworkLoadingFinishedEvent
  'Network.loadingFailed': NetworkLoadingFailedEvent
  'Target.attachedToTarget': TargetAttachedToTargetEvent
  'Target.detachedFromTarget': TargetDetachedFromTargetEvent
}

export interface NetworkRequestWillBeSentEvent {
  requestId: string
  loaderId?: string
  documentURL?: string
  type?: string
  request: {
    url: string
    method?: string
    headers?: Record<string, string | number | boolean>
    postData?: string
  }
  timestamp?: number
  wallTime?: number
}

export interface NetworkResponseReceivedEvent {
  requestId: string
  loaderId?: string
  timestamp?: number
  type?: string
  response: NetworkResponse
}

export interface NetworkResponse {
  url: string
  status: number
  statusText?: string
  headers?: Record<string, string | number | boolean>
  mimeType?: string
  encodedDataLength?: number
  fromDiskCache?: boolean
  fromServiceWorker?: boolean
}

export interface NetworkLoadingFinishedEvent {
  requestId: string
  timestamp?: number
  encodedDataLength?: number
}

export interface NetworkLoadingFailedEvent {
  requestId: string
  timestamp?: number
  type?: string
  errorText?: string
  canceled?: boolean
}

export interface TargetAttachedToTargetEvent {
  sessionId: string
  targetInfo: {
    targetId: string
    type: string
    title?: string
    url?: string
    attached?: boolean
    openerId?: string
    browserContextId?: string
  }
  waitingForDebugger?: boolean
}

export interface TargetDetachedFromTargetEvent {
  sessionId: string
  targetId?: string
}

/** A single property attached to an {@link AXNode} (state/relation/etc.). */
export interface AXProperty {
  name: string
  value: AXValue
}

/** A typed accessibility value (role, name, description, property value, …). */
export interface AXValue {
  type: string
  value?: unknown
}

/**
 * One node of {@code Accessibility.getFullAXTree}. Only the fields the CDP
 * extractor reads are modelled; the protocol carries more.
 */
export interface AXNode {
  /** Unique id of this AX node within the tree. */
  nodeId: string
  /** True if this node is not exposed to assistive tech (skip it). */
  ignored?: boolean
  /** Computed ARIA-ish role, e.g. {@code { type: 'role', value: 'button' }}. */
  role?: AXValue
  /** Computed accessible name. */
  name?: AXValue
  /** Computed accessible description (unused for the line format, kept for parity). */
  description?: AXValue
  /** State/relation properties (focusable, checked, disabled, …). */
  properties?: AXProperty[]
  /** Ordered child AX node ids — drives emitted tree order + indentation. */
  childIds?: string[]
  /** Back-pointer to the DOM node; the key we correlate bounds by. */
  backendDOMNodeId?: number
}

/**
 * {@code DOM.getBoxModel} result. Each quad is 8 numbers — four (x,y) corners
 * in clockwise order: top-left, top-right, bottom-right, bottom-left.
 */
export interface BoxModel {
  content: number[]
  padding: number[]
  border: number[]
  margin: number[]
  width: number
  height: number
}
