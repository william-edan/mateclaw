package vip.mate.browser.orchestrator.snapshot;

/**
 * 线程级"快照偏好"——是否让扩展【跳过 CDP a11y、直接用 DOM 走树器】取页面快照(observe)。
 *
 * <p>背景:observe 默认走 {@code a11y.snapshot.request} → 扩展用 CDP({@code chrome.debugger})取无障碍
 * 树,这会触发 Chrome 强制的"已开始调试此浏览器"横幅(占视口顶部一条;频繁 attach/detach 时页面被顶下/
 * 弹回反复跳动 = 用户看到的"页面变形")。而抖音获客是确定性 DOM 流程,observe 只需 url/title/几个选择器,
 * 扩展内置的 DOM 走树器足够、且更快、后台/最小化也不空树。
 *
 * <p>用法:获客执行器在 run 线程入口 {@link #setPreferDom}(true),结束在 finally {@link #clear};
 * {@link DefaultSnapshotEdgeClient} 构造 a11y.snapshot.request 时读 {@link #preferDom} 写进 payload 的
 * {@code prefer_dom},扩展据此跳过 CDP。通用浏览器 agent 不设此 ThreadLocal → 默认 false → CDP-first
 * 行为完全不变(只影响获客)。observe 同步运行在 run 线程,故 ThreadLocal 对 client.request 可见。
 */
public final class SnapshotPreferenceContext {

    private static final ThreadLocal<Boolean> PREFER_DOM = new ThreadLocal<>();

    private SnapshotPreferenceContext() {
    }

    public static void setPreferDom(boolean preferDom) {
        PREFER_DOM.set(preferDom);
    }

    public static boolean preferDom() {
        return Boolean.TRUE.equals(PREFER_DOM.get());
    }

    public static void clear() {
        PREFER_DOM.remove();
    }
}
