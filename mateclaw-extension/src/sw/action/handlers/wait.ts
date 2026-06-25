import { ActionFailureError, type ActionHandler } from '../ActionExecutor'
import type { WaitParams } from '../types'

export interface WaitHandlerDeps {
  /** Injectable clock - defaults to Date.now. */
  clock?: () => number
  /** Promise-returning sleeper - defaults to setTimeout-based. Tests can stub. */
  sleep?: (ms: number) => Promise<void>
  /** chrome API (for load_state strategy). Defaults to global chrome. */
  chrome?: typeof globalThis.chrome
}

type WaitErrorCode = ConstructorParameters<typeof ActionFailureError>[0] | 'VALIDATION'
type NavigationDetails = chrome.webNavigation.WebNavigationFramedCallbackDetails
type NavigationListener = (details: NavigationDetails) => void
type NavigationEvent = {
  addListener(listener: NavigationListener): void
  removeListener(listener: NavigationListener): void
}

const DEFAULT_IDLE_THRESHOLD_MS = 500

const defaultSleep = (ms: number) => new Promise<void>(resolve => {
  setTimeout(resolve, ms)
})

function actionFailure(code: WaitErrorCode, message: string, retryable = false) {
  return new ActionFailureError(
    code as ConstructorParameters<typeof ActionFailureError>[0],
    message,
    retryable,
  )
}

function assertNonNegativeNumber(value: unknown, field: string): number {
  if (typeof value !== 'number' || !Number.isFinite(value) || value < 0) {
    throw actionFailure('VALIDATION', `${field} must be a non-negative number`)
  }
  return value
}

function success(start: number, clock: () => number) {
  const waitedMs = clock() - start
  return {
    ok: true as const,
    elapsed_ms: waitedMs,
    payload: { waited_ms: waitedMs },
  }
}

async function withDeadline<T>(
  work: Promise<T>,
  deadlineMs: number,
  _sleep: (ms: number) => Promise<void>,
): Promise<T> {
  const timeoutMs = assertNonNegativeNumber(deadlineMs, 'deadline_ms')
  // 用本地可取消定时器替代 sleep(timeoutMs).then(throw):work(导航事件)先赢时,在 finally
  // clearTimeout 清掉超时计时器,避免底层 timer 无人清理、一直挂到 deadline 才空跑触发并向已 settle
  // 的 race 抛出(EX-H5)。镜像 ActionExecutor.runWithDeadline 的写法。
  let timer: ReturnType<typeof setTimeout> | undefined
  const timeout = new Promise<T>((_, reject) => {
    timer = setTimeout(
      () => reject(actionFailure('TIMEOUT_PAGE_LOAD', `wait timed out after ${timeoutMs}ms`, true)),
      timeoutMs,
    )
  })
  return Promise.race([work, timeout]).finally(() => {
    if (timer !== undefined) clearTimeout(timer)
  })
}

function waitForMainFrameEvent(
  event: NavigationEvent,
  tabId: number,
  cleanup: Array<() => void>,
) {
  return new Promise<void>(resolve => {
    const listener: NavigationListener = details => {
      if (details.tabId !== tabId || details.frameId !== 0) {
        return
      }
      resolve()
    }

    event.addListener(listener)
    cleanup.push(() => event.removeListener(listener))
  })
}

/**
 * wait handler.
 *
 * - strategy='time'        -> sleep(duration_ms) -> Success(waited_ms = duration_ms).
 *                             duration_ms required; throws ActionFailureError('VALIDATION') if missing.
 * - strategy='load_state'  -> register chrome.webNavigation listener for the
 *                             requested state (load -> onCompleted,
 *                             domcontentloaded -> onDOMContentLoaded,
 *                             network_idle -> onCompleted + 500ms idle window),
 *                             race against deadlineMs. Returns elapsed wall-clock.
 *                             Throws ActionFailureError('TIMEOUT_PAGE_LOAD') on deadline.
 * - strategy='network_idle' -> Phase-2 fallback: sleep(idle_threshold_ms ?? 500)
 *                             then return. FIXME(phase-3): swap in real
 *                             webRequest monitoring.
 *
 * All paths return Success with payload.waited_ms = clock()-start.
 */
export const waitHandler = (deps: WaitHandlerDeps = {}): ActionHandler<WaitParams> => {
  const clock = deps.clock ?? (() => Date.now())
  const sleep = deps.sleep ?? defaultSleep
  const chromeApi = deps.chrome ?? globalThis.chrome

  return async (tabId, params, deadlineMs) => {
    const start = clock()

    switch (params.strategy) {
      case 'time': {
        const durationMs = assertNonNegativeNumber(params.duration_ms, 'duration_ms')
        const timeoutMs = assertNonNegativeNumber(deadlineMs, 'deadline_ms')
        if (durationMs > timeoutMs) {
          throw actionFailure('TIMEOUT_PAGE_LOAD', `wait duration ${durationMs}ms exceeds deadline ${timeoutMs}ms`, true)
        }

        await sleep(durationMs)
        return success(start, clock)
      }

      case 'network_idle': {
        // FIXME(phase-3): replace this fallback with webRequest-backed monitoring
        // once host permission risk is addressed.
        const idleThresholdMs = params.idle_threshold_ms == null
          ? DEFAULT_IDLE_THRESHOLD_MS
          : assertNonNegativeNumber(params.idle_threshold_ms, 'idle_threshold_ms')

        await sleep(idleThresholdMs)
        return success(start, clock)
      }

      case 'load_state': {
        const loadState = params.load_state ?? 'load'
        if (!['load', 'domcontentloaded', 'network_idle'].includes(loadState)) {
          throw actionFailure('VALIDATION', `invalid load_state '${String(loadState)}'`)
        }
        if (!chromeApi?.webNavigation) {
          throw actionFailure('VALIDATION', 'chrome.webNavigation is unavailable')
        }

        const cleanup: Array<() => void> = []
        try {
          if (loadState === 'domcontentloaded') {
            await withDeadline(
              waitForMainFrameEvent(chromeApi.webNavigation.onDOMContentLoaded as NavigationEvent, tabId, cleanup),
              deadlineMs,
              sleep,
            )
          } else {
            await withDeadline(
              waitForMainFrameEvent(chromeApi.webNavigation.onCompleted as NavigationEvent, tabId, cleanup)
                .then(async () => {
                  if (loadState === 'network_idle') {
                    await sleep(DEFAULT_IDLE_THRESHOLD_MS)
                  }
                }),
              deadlineMs,
              sleep,
            )
          }

          return success(start, clock)
        } finally {
          for (const remove of cleanup) {
            remove()
          }
        }
      }

      default:
        throw actionFailure('VALIDATION', `invalid wait strategy '${String(params.strategy)}'`)
    }
  }
}
