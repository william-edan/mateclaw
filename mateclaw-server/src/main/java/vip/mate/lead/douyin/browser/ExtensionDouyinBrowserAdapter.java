package vip.mate.lead.douyin.browser;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;
import vip.mate.browser.edge.action.TypePayload;
import vip.mate.lead.douyin.collect.DouyinCommentCollector;
import vip.mate.lead.douyin.model.CommentCollectionResult;
import vip.mate.lead.douyin.model.DouyinCommentItem;
import vip.mate.lead.douyin.model.DouyinLeadAcquisitionInput;
import vip.mate.lead.douyin.model.EngagementResult;
import vip.mate.tool.builtin.ExtensionBrowserTool;

import java.net.URLEncoder;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.function.Consumer;
import java.util.function.Supplier;

@Component
public class ExtensionDouyinBrowserAdapter implements DouyinBrowserAdapter {

    private static final Logger log = LoggerFactory.getLogger(ExtensionDouyinBrowserAdapter.class);

    private static final int END_MARKER_STABLE_WINDOWS = 2;
    private static final int MAX_SCROLL_PROTECTION = 2_000;
    private static final long COMMENT_SCROLL_REGION_DEADLINE_MS = 15_000L;
    private static final long COMMENT_WHEEL_SCROLL_DEADLINE_MS = 4_000L;
    private static final long COMMENT_NETWORK_WHEEL_SCROLL_DEADLINE_MS = 1_500L;
    private static final double COMMENT_SCROLL_STEP_PX = 520.0d;
    private static final double COMMENT_NETWORK_SCROLL_STEP_PX = 680.0d;
    private static final long COMMENT_SCROLL_SETTLE_DELAY_MS = 450L;
    private static final long COMMENT_NETWORK_SCROLL_SETTLE_DELAY_MS = 180L;
    private static final long COMMENT_NETWORK_PAGE_TARGET_INTERVAL_MS = 1_000L;
    private static final long COMMENT_FINAL_SETTLE_DELAY_MS = 800L;
    private static final double COMMENT_COLLECTION_TARGET_COVERAGE = 1.0d;
    private static final long FILTER_PANEL_SETTLE_DELAY_MS = 600L;
    private static final long SORT_SELECT_SETTLE_DELAY_MS = 1_200L;
    private static final long SORTED_VIDEO_SNAPSHOT_TTL_MS = 30 * 60 * 1000L;
    private static final int COMMENT_EXTRACT_REGION_MAX_ITEMS = 80;
    private static final int COMMENT_DOM_INCREMENTAL_LOOKBACK = 8;
    private static final int COMMENT_DOM_FALLBACK_INTERVAL = 12;
    private static final int COMMENT_NETWORK_CAPTURE_MAX_PAGES = 200;
    private static final int COMMENT_NETWORK_CAPTURE_MAX_BODY_BYTES = 512 * 1024;
    private static final int COMMENT_NETWORK_CAPTURE_TTL_MS = 180_000;
    private static final boolean COMMENT_NETWORK_ONLY_COLLECTION = true;
    /**
     * 【临时调试开关】DOM-only 模式。为 true 时,排序 / 点视频 / 打开评论 这些步骤,
     * 只要页内 DOM 动作(douyin_ui sort/open_comments、douyin_open_video)返回 ok 就【信任成功】,
     * 跳过基于 observe 的二次校验和 CDP 兜底(openFilterPanel / 候选坐标点击 / 'x' 快捷键),
     * 并跳过 parkMouse/focus 等纯 CDP 鼠标移动(service_hover → MOVE_MOUSE)。
     *
     * 背景:后台 / 最小化窗口下 observe 取到的是空树,二次校验必然失败而误触发 CDP 兜底
     * (表现为"DOM 已点筛选、鼠标又移上去重复点""鼠标移到评论区")。本开关让整条链路只走 DOM,
     * 便于验证纯 DOM 是否可行。需与扩展端 debug-flags.ts 的 DOM_ONLY_NO_CDP_FALLBACK 配套使用,
     * 测试完一并改回 false。
     */
    private static final boolean DOM_ONLY_DEBUG = true;
    private static final int COMMENT_NETWORK_FALLBACK_GRACE_SCROLLS = 6;
    // 慢网络保守系数:续拉 XHR 在慢网下回包滞后,容易把"还在加载"误判成"到底"。
    // 把空转/稳定窗口阈值统一放大,宁可多等也不漏采(仍受 MAX_SCROLL_PROTECTION 总上限保护)。
    private static final double COMMENT_SLOW_NETWORK_WINDOW_FACTOR = 1.5d;
    private static final int COMMENT_NETWORK_ONLY_NO_PAGE_SCROLL_LIMIT =
            (int) Math.round(35 * COMMENT_SLOW_NETWORK_WINDOW_FACTOR);
    private static final int COMMENT_NETWORK_ONLY_STALE_WINDOW_LIMIT =
            (int) Math.round(60 * COMMENT_SLOW_NETWORK_WINDOW_FACTOR);
    // 判"到底/无新增"前,若 network capture 仍有"在途未回"的续拉,额外再等一个 settle 再判;
    // 该窗口既不计入空转也不计入稳定窗口。设总上限避免续拉一直在途时无限等待。
    private static final long COMMENT_NETWORK_INFLIGHT_SETTLE_MS = 900L;
    private static final int COMMENT_NETWORK_INFLIGHT_MAX_EXTRA_WAITS = 8;
    private static final int AUTHOR_PROFILE_OPEN_MAX_ATTEMPTS = 2;
    private static final int AUTHOR_PROFILE_CONFIRM_ATTEMPTS = 16;
    private static final int AUTHOR_PROFILE_RETRY_CONFIRM_ATTEMPTS = 24;
    private static final long AUTHOR_PROFILE_CONFIRM_WAIT_MS = 750L;
    private static final long AUTHOR_PROFILE_RETRY_CONFIRM_WAIT_MS = 1_000L;

    /**
     * 【私信复核去后台误判 feature-flag,默认 off,旧行为零变化】
     *
     * 根因(后台恒假误判):后台/最小化 tab 的复核完全依赖空信号——a11y observe 必返空树
     * (Chrome 对非活动 tab 的硬限制),而 DM DOM region 提取又用 dmRegion(obs) 按 obs 视口
     * 几何注册,后台 obs 视口塌缩为 0 → 区域坍缩、提取 items=0。两路在后台【恒为空】,导致
     * confirmDmSent 在后台【恒返回 false】。于是即便扩展已用 slate/CDP 真把私信发出去
     * (sentByDmPrimitive=true),复核也判 false → distrust sent → 触发 send-only 二次点发,
     * 既造成"明明发了却判失败",又有重复发送风险。
     *
     * 修复方向(flag=on 时):
     *  1) 扩展自报 sentByDmPrimitive=true 直接视为 sentConfirmed —— 扩展端是经 slate/CDP 真发出
     *     的第一手强信号,不再用"后台恒空"的复核去推翻它;
     *  2) 复核降级为"扩展未自报 sent 时的二次确认",且区分三态:命中=CONFIRMED、确凿未见=NOT_FOUND、
     *     全程观测为空(后台/blank)=INCONCLUSIVE。INCONCLUSIVE 绝不据空信号判 false、绝不触发 send-only。
     *
     * flag=off 时:reviewDmSent 不参与,完全走原 confirmDmSent 布尔路径,逐字保留旧逻辑。
     * 默认 ON(根治私信后台 observe 空误判);改为 false 即应急回退旧布尔复核路径。
     */
    private static final boolean DM_REVIEW_TRUST_PRIMITIVE_SENT = true;

    // ===== 慢环境(3G/慢机)适配:时间驱动 → 状态/事件驱动 =====
    // 慢网/慢机下,搜索结果与视频列表懒加载迟迟不就绪。整条主流程旧逻辑在"上一步动作发出"后
    // 用固定等待/固定轮询次数就进入下一步,还没加载完就判失败(搜索✓→排序✓→点视频❌:
    // douyin_open_video 拿不到视频卡 no_video_cover_cards / total=0,又回退 a11y candidates,
    // 慢网下 candidates 同样空 → VIDEO_RESULT_NOT_FOUND → 任务 failed)。
    // 体系化方向:对关键固定等待乘一个保守的慢环境系数;并把"点视频"从一次性探测改为"重试等列表
    // 就绪(退避轮询 + 总上限)",直到命中视频卡或超时才回退。系数与上限都很保守,快网下因"条件
    // 一旦成立立即提前退出"不会白等;所有循环都带总上限,避免无限等待。
    private static final double SLOW_ENV_WAIT_FACTOR = 1.6d;

    /** 把链路里关键的固定 settle 等待按慢环境系数放大(向上取整)。 */
    private static long slowMs(long baseMs) {
        return (long) Math.ceil(Math.max(0L, baseMs) * SLOW_ENV_WAIT_FACTOR);
    }

    /** 把固定轮询次数按慢环境系数放大(向上取整,至少与原值相同)。 */
    private static int slowAttempts(int baseAttempts) {
        return Math.max(baseAttempts, (int) Math.ceil(baseAttempts * SLOW_ENV_WAIT_FACTOR));
    }

    // 【点视频重试等列表】douyin_open_video 内部已做 ~12s 卡片稳定轮询(见扩展端);后端在其之上
    // 再包一层"重试等列表加载"的外层循环:每轮重新调 douyin_open_video,退避递增等待,直到命中
    // 视频卡(确认进入视频页)或达到外层总上限,仍不行才回退 a11y candidates。
    // 退避序列(ms)。外层尝试数 = 数组长度;总等待上限 ≈ Σ序列 ≈ 12s,叠加每轮扩展内部 ~12s。
    private static final long[] OPEN_VIDEO_RELOAD_BACKOFF_MS = {1_200L, 1_800L, 2_500L, 3_200L, 3_500L};
    // 排序会触发结果区整列重载,比首屏更慢:进入点视频前先等结果区出现视频卡的就绪宽限上限。
    private static final long SORT_RELOAD_SETTLE_MS = slowMs(SORT_SELECT_SETTLE_DELAY_MS);
    private static final List<String> DOUYIN_COMPREHENSIVE_SORT_LABELS =
            List.of("综合排序", "综合", "默认排序");
    private static final List<String> DOUYIN_LIKE_SORT_LABELS =
            List.of("最多点赞", "点赞最多", "按点赞", "点赞量");
    private static final List<String> DOUYIN_LATEST_SORT_LABELS =
            List.of("最新发布", "发布时间", "按时间", "按发布时间");
    private static final List<String> DOUYIN_SORT_PANEL_LABELS =
            List.of("综合排序", "最多点赞", "点赞最多", "按点赞", "点赞量", "最新发布", "发布时间", "按时间", "按发布时间", "最新");
    private static final Pattern TREE_LINE_PATTERN = Pattern.compile(
            "^\\s*([A-Za-z][\\w-]*)\\s*\\[ref=[^\\],]+(?:,\\s*[^\\]]+)?\\]\\s*"
                    + "(?::\\s*(.*?))?\\s*(?:@\\{(-?\\d+),(-?\\d+)\\s+(\\d+)x(\\d+)\\})?\\s*$");

    private final ExtensionBrowserTool browser;
    private final ObjectMapper mapper;
    private final DouyinCommentCollector collector;
    private volatile SortedVideoSnapshot lastSortedVideoSnapshot = SortedVideoSnapshot.empty();
    private volatile boolean commentNetworkCaptureActive = false;

    private record OpenedAuthorProfile(BrowserObservation observation, @Nullable Long tabId) {
    }

    public ExtensionDouyinBrowserAdapter(ExtensionBrowserTool browser,
                                         ObjectMapper mapper,
                                         DouyinCommentCollector collector) {
        this.browser = browser;
        this.mapper = mapper;
        this.collector = collector;
    }

    // ===== 断连续跑(方案A止血 D 组)=====
    // 旧逻辑:中途断连(SESSION_DETACHED / 任务期间扩展短暂掉线 / 单步 DEADLINE_EXCEEDED)在大多数步骤会
    // 直接抛 DouyinBrowserException 终止整轮(只有 search/sort/profile 各自有零散的 SESSION_DETACHED 重试)。
    // 体系化方向:把"断连归一为可重试"收编进统一的 withReconnect(step) 包装——把
    // SESSION_DETACHED / DEADLINE_EXCEEDED / CANCELLED 归一为 retryable,在重试前给一段"等浏览器会话重现"的
    // 宽限(短轮询,最多 ~10s),宽限内连续观察到主页可观测(observe 不再 SESSION_DETACHED)即认为会话重连成功,
    // 然后重跑【当前步】,而不是整轮失败,也不是干等 60s deadline。
    //
    // 重连依赖:重连本身由 C 组负责——扩展重新握手后 EdgeSessionRegistry 会以发起人 subject(D 组在执行入口
    // RoutingSubjectContext.set 的 userId)重新登记 session;ExtensionBrowserTool.resolveSession 在 ChatOrigin
    // 无 subject 时回退读 RoutingSubjectContext.get() 精确命中本人新会话。故这里的宽限只需"轮询到主页可观测"
    // 即可,无需直接持有 registry——下一次 service_* 调用会自动落到重连后的新 session 上。
    private static final long RECONNECT_GRACE_MAX_MS = 10_000L;
    private static final long RECONNECT_GRACE_POLL_MS = 800L;
    private static final int RECONNECT_MAX_ATTEMPTS = 2;

    // 私信幂等(跨重试/续跑):followAndDraft 内的 sentDmKeys 只在单次方法调用内防"重复点发"。一旦把
    // followAndDraft 这一步纳入 withReconnect 重跑,新一次调用会得到空的本地集合 —— 若上次已点过发送但
    // 在确认阶段断连,重跑就可能二次发私信。故把"已点过发送的 dmKey"提升为实例级守卫:重跑进入
    // followAndDraft 时若该 dmKey 已在守卫集合中,直接走"跳过点发、只强制复核"路径,确保续跑绝不重复发私信。
    // 用并发安全集合:@Component 单例下可能有多个获客 run 并发(各自虚拟线程)。dmKey = hash(作者标识+草稿文本),
    // 与 followAndDraft 内既有口径完全一致;不同 run 对"同一作者+同一草稿"会共享同一 dmKey(与现状同质,见 notes)。
    private final Set<String> clickedDmKeys = java.util.concurrent.ConcurrentHashMap.newKeySet();
    // 守卫集合上限:单例长期运行下避免无限增长。守卫的有效窗口仅为"同一步 withReconnect 的数次重跑"
    // (秒级),故达到上限直接清空是安全的——极端情况下最多丢失"恰好正在重跑中的某条 dmKey"守卫,
    // 概率极低且仅退化为现状(单次调用内仍有本地 sentDmKeys 防重发)。
    private static final int CLICKED_DM_KEYS_MAX = 4_096;

    /** 记录"已点过发送"的 dmKey 到实例级守卫,带上限清理,防止单例长期运行下集合无限增长。 */
    private void recordClickedDmKey(String dmKey) {
        if (dmKey == null) {
            return;
        }
        if (clickedDmKeys.size() >= CLICKED_DM_KEYS_MAX) {
            clickedDmKeys.clear();
        }
        clickedDmKeys.add(dmKey);
    }

    /**
     * 统一的"断连即重连重试当前步"包装。把 {@code SESSION_DETACHED / DEADLINE_EXCEEDED / CANCELLED}
     * 归一为可重试:重试前先给一段"等浏览器会话重现"的宽限(短轮询,最多 {@link #RECONNECT_GRACE_MAX_MS}),
     * 宽限内观察到主页可观测即认为重连成功,再重跑 {@code work};不可重试的异常原样抛出。
     *
     * <p>注意:仅用于【幂等或带自身幂等守卫】的步骤。导航/打开评论/采集/打开主页这些步骤天然幂等
     * (重新导航/重新打开无副作用);私信触达(followAndDraft)非幂等,靠 {@link #clickedDmKeys} 实例级
     * dmKey 守卫保证重跑不重复点发。RUN_CANCELLED(用户主动取消)不在重试之列,原样抛出尽快收口。
     */
    private <T> T withReconnect(String step, Supplier<T> work) {
        DouyinBrowserException last = null;
        for (int attempt = 0; attempt <= RECONNECT_MAX_ATTEMPTS; attempt++) {
            try {
                return work.get();
            } catch (DouyinBrowserException e) {
                last = e;
                if (attempt >= RECONNECT_MAX_ATTEMPTS || !isReconnectRetryable(e)) {
                    throw e;
                }
                // 用户主动停止(RunService.cancel 会 interrupt 本执行线程)与"会话层 CANCELLED"会用同一
                // CANCELLED code(见 ActionExecutionService.completeCancelling:user_stop / deadline 共用);
                // 故重试前先看线程是否被中断:被中断 = 用户停止,绝不重试,原样抛出尽快收口,保住停止响应性。
                if (Thread.currentThread().isInterrupted()) {
                    log.info("[douyin.lead] step '{}' got reconnectable code={} but thread is interrupted "
                            + "(user stop); not retrying", step, e.code());
                    throw e;
                }
                log.warn("[douyin.lead] step '{}' hit reconnectable failure (code={}, attempt={}); "
                                + "waiting for browser session to reappear then retrying step",
                        step, e.code(), attempt + 1);
                waitForBrowserSessionReconnect(step);
            }
        }
        throw last == null ? new DouyinBrowserException("STEP_FAILED", "step failed: " + step) : last;
    }

    /**
     * 断连可重试判定:扩展掉线 / CDP 目标关闭 → SESSION_DETACHED;单步在断连窗口内超时 → DEADLINE_EXCEEDED;
     * 任务期间浏览器侧因重连取消在途调用 → CANCELLED。这三类都属于"会话层瞬态",重连后重跑当前步可恢复。
     * 用户主动取消(RUN_CANCELLED)不在此列。
     */
    private boolean isReconnectRetryable(DouyinBrowserException e) {
        if (e == null) {
            return false;
        }
        String code = e.code();
        return "SESSION_DETACHED".equals(code)
                || "DEADLINE_EXCEEDED".equals(code)
                || "CANCELLED".equals(code);
    }

    /**
     * 重连宽限:短轮询最多 {@link #RECONNECT_GRACE_MAX_MS},直到主页 observe 不再 SESSION_DETACHED
     * (即扩展已重新握手、C 组按 subject 重登记了 session、下一次 service_* 能落到新会话)。
     * 超时仍未重现也返回——由上层 withReconnect 再发起一次重跑,重跑若仍断连则按不可恢复抛出。
     */
    private void waitForBrowserSessionReconnect(String step) {
        long deadline = System.currentTimeMillis() + RECONNECT_GRACE_MAX_MS;
        while (System.currentTimeMillis() < deadline) {
            sleepLocal(RECONNECT_GRACE_POLL_MS);
            try {
                // observeMain 不抛异常:失败时返回 ok=false 且 code=SESSION_DETACHED/OBSERVE_FAILED。
                // 故必须看 probe.ok() —— ok=true 才代表 service_observe_main 真正落到了一个可观测的会话上
                // (扩展已重新握手、C 组按 subject 重登记了新 session)。后台 tab 空树但会话在线时 ok 仍为
                // true(url 可能为空但请求成功返回),足以判定"会话重现",页面内容由重跑的步骤自行校验。
                BrowserObservation probe = observeMain("all");
                if (probe != null && probe.ok()) {
                    log.info("[douyin.lead] browser session reappeared during grace for step '{}'; retrying step", step);
                    return;
                }
                if (probe != null && !isReconnectRetryable(observeFailureCode(probe))) {
                    // 探测返回的失败码非会话层(非 SESSION_DETACHED/DEADLINE_EXCEEDED/CANCELLED),
                    // 不再傻等;交由重跑/上层处理。
                    return;
                }
                // 仍断连(ok=false 且会话层码),继续轮询直到宽限耗尽。
            } catch (DouyinBrowserException probeError) {
                // observeMain 理论上不抛(仅 parse JSON 异常会抛 BROWSER_JSON_INVALID),稳妥兜底:
                // 非会话层异常不再傻等。
                if (!isReconnectRetryable(probeError)) {
                    return;
                }
            }
        }
        log.warn("[douyin.lead] browser session did not reappear within grace ({}ms) for step '{}'; "
                + "will retry step once more anyway", RECONNECT_GRACE_MAX_MS, step);
    }

    /** 按错误码判定会话层瞬态(observeMain 返回失败码时复用此判定,不必造异常)。 */
    private boolean isReconnectRetryable(String code) {
        return code != null
                && ("SESSION_DETACHED".equals(code)
                || "DEADLINE_EXCEEDED".equals(code)
                || "CANCELLED".equals(code));
    }

    private String observeFailureCode(BrowserObservation probe) {
        return probe == null ? "" : (probe.code() == null ? "" : probe.code());
    }

    @Override
    public BrowserObservation openDouyinAndSearch(DouyinLeadAcquisitionInput input) {
        clearSortedVideoSnapshot();
        // 断连续跑:整步纳入 withReconnect —— 单步内仍保留搜索自身的多次重试(搜索框慢渲染/未验证等
        // 业务性恢复),会话层断连(SESSION_DETACHED/DEADLINE_EXCEEDED/CANCELLED)则由 withReconnect 在
        // 等会话重现后重跑整步。搜索天然幂等(重新导航+重新输入),重跑安全。
        return withReconnect("open_douyin_search", () -> {
            DouyinBrowserException last = null;
            for (int attempt = 0; attempt < 3; attempt++) {
                try {
                    return openDouyinAndSearchOnce(input, attempt);
                } catch (DouyinBrowserException e) {
                    last = e;
                    // 会话层断连交给外层 withReconnect 统一处理(等重现再整步重跑);此处只做搜索业务性退避重试。
                    if (isReconnectRetryable(e) || attempt >= 2) {
                        throw e;
                    }
                    sleepLocal(1200L + attempt * 900L);
                }
            }
            throw last == null ? new DouyinBrowserException("SEARCH_FAILED", "抖音搜索失败") : last;
        });
    }

    private BrowserObservation openDouyinAndSearchOnce(DouyinLeadAcquisitionInput input, int attempt) {
        BrowserObservation current = observeMain("all");
        if (canReuseSearchResultsForSorting(current, input.keyword())) {
            return current;
        }
        // 真实 DOM 搜索流程(用户要求,非 URL 直达):进抖音首页(默认跳 /jingxuan)→ 在搜索框
        // 输入关键词(React 受控:原生 setter + _valueTracker)→ 点击搜索按钮。全程页内 DOM
        // (douyin_search 动作),不依赖窗口活动tab/焦点,后台/最小化可用,且行为接近真人。
        BrowserObservation viaDom = searchByRealDomFlow(input.keyword(), attempt);
        if (searchVerified(viaDom, input.keyword())) {
            log.info("[douyin.lead] search opened via real DOM flow (type + click search, background-capable): keyword={}",
                    input.keyword());
            return viaDom;
        }
        // 兜底(仅前台活动tab可用):首页搜索框 CDP 打字 + 回车
        JsonNode navigate = parse(browser.extension_browser_navigate(
                "https://www.douyin.com/jingxuan",
                "domcontentloaded",
                null));
        waitMs(attempt == 0 ? 1800L : 2600L);
        SearchTarget target = waitForSearchBoxTarget(8, 650L);
        if (target.point() == null && !ok(navigate)) {
            BrowserObservation obs = target.observation();
            throw new DouyinBrowserException(
                    navigate.path("code").asText("NAVIGATE_DOUYIN_VIDEO_HOME_FAILED"),
                    "browser action failed at navigate_douyin_video_home: "
                            + navigate.path("code").asText("UNKNOWN") + " - "
                            + navigate.path("message").asText("")
                            + ", observedUrl=" + obs.url()
                            + ", observedTitle=" + obs.title()
                            + ", signals=" + searchSignals(obs, input.keyword()));
        }
        JsonNode focusResult = errorNode("NOT_NEEDED", "search box point found");
        if (target.point() == null) {
            focusResult = focusSearchBoxByGrounding();
        }
        if (target.point() == null && !ok(focusResult)) {
            BrowserObservation obs = target.observation();
            throw new DouyinBrowserException("SEARCH_BOX_NOT_FOUND",
                    "未找到抖音搜索框: url=" + obs.url()
                            + ", title=" + obs.title()
                            + ", focusFallback=" + errorSummary(focusResult)
                            + ", signals=" + searchSignals(obs, input.keyword())
                            + ", tree=" + treeExcerpt(obs.tree()));
        }
        TypePayload.FocusTarget searchFocus = null;
        if (target.point() != null) {
            ClickPoint search = target.point();
            searchFocus = new TypePayload.FocusTarget(search.x(), search.y());
        }
        requireOk(browser.service_type_main(input.keyword(), searchFocus), "type_search_keyword");
        submitSearchWithEnterAndVerify(input.keyword());
        BrowserObservation observed = waitForSearchVerified(input.keyword(), 8, 900L);
        if (searchVerified(observed, input.keyword())) {
            return observed;
        }

        BrowserObservation typed = observed;
        if (!searchTextEntered(typed, input.keyword())) {
            SearchTarget retryTarget = waitForSearchBoxTarget(2, 300L);
            if (retryTarget.point() != null) {
                ClickPoint search = retryTarget.point();
                searchFocus = new TypePayload.FocusTarget(search.x(), search.y());
            }
            requireOk(browser.service_type_main(input.keyword(), searchFocus), "retry_type_search_keyword");
            submitSearchWithEnterAndVerify(input.keyword());
            observed = waitForSearchVerified(input.keyword(), 8, 900L);
            if (searchVerified(observed, input.keyword())) {
                return observed;
            }
            typed = observed;
        }

        if (!searchVerified(observed, input.keyword())) {
            throw new DouyinBrowserException("SEARCH_NOT_VERIFIED",
                    "已通过首页搜索框输入并按回车提交，但没有观察到 " + input.keyword() + " 搜索结果或筛选入口。url="
                            + observed.url() + ", title=" + observed.title()
                            + ", typedSignals=" + searchSignals(typed, input.keyword())
                            + ", finalSignals=" + searchSignals(observed, input.keyword()));
        }
        return observed;
    }

    /**
     * 真实 DOM 搜索流程(后台可用,非 URL 直达):
     *   1. 确保在抖音首页/精选(搜索栏存在)——不在则 navigate 到 /jingxuan;
     *   2. 调 douyin_search 动作:页内把关键词写入 React 搜索框 + 合成点击搜索按钮;
     *      搜索框可能还在渲染,故重试若干次;
     *   3. 用 searchVerified 校验已进入搜索结果。
     * 全程页内 DOM + chrome 导航,不依赖窗口活动tab/焦点,后台/最小化可用。
     */
    private BrowserObservation searchByRealDomFlow(String keyword, int attempt) {
        String kw = keyword == null ? "" : keyword.trim();
        if (kw.isEmpty()) {
            return BrowserObservation.failed("EMPTY_KEYWORD", "搜索关键词为空");
        }
        BrowserObservation cur = observeMain("all");
        if (!hasDouyinSearchBarContext(cur.url())) {
            tryOk(browser.extension_browser_navigate(
                    "https://www.douyin.com/jingxuan",
                    "domcontentloaded",
                    null));
            // 慢环境:首页搜索栏在慢网下渲染更慢,导航后就绪宽限按慢系数放大,降低首次 douyin_search 落空。
            waitMs(slowMs(attempt == 0 ? 2000L : 2800L));
        }
        JsonNode res = null;
        // 慢环境:搜索框渲染慢,重试次数/间隔放大,给 React 受控搜索框更多 mount 时间(ok 即提前退出)。
        int searchTries = slowAttempts(5);
        for (int i = 0; i < searchTries; i++) {
            res = parse(browser.service_douyin_search_main(kw));
            if (ok(res)) {
                break;
            }
            // 搜索框尚未渲染/未就绪,等待后重试
            waitMs(slowMs(700L));
        }
        if (res == null || !ok(res)) {
            log.warn("[douyin.lead] real DOM search action not ok after retries: keyword={}, last={}",
                    kw, errorSummary(res));
            return observeMain("all");
        }
        log.info("[douyin.lead] real DOM search submitted: keyword={}, payload={}",
                kw, res.path("results").path(0).path("payload"));
        waitMs(slowMs(1200L));
        return waitForSearchVerified(kw, slowAttempts(8), 900L);
    }

    private boolean hasDouyinSearchBarContext(@Nullable String url) {
        String u = url == null ? "" : url.toLowerCase(Locale.ROOT);
        return u.contains("douyin.com") && (u.contains("/jingxuan") || u.contains("/search/"));
    }

    @Override
    public BrowserObservation applySort(DouyinLeadAcquisitionInput input) {
        DouyinSortSpec sort = DouyinSortSpec.from(input == null ? "" : input.sort());
        String keyword = input == null ? DouyinLeadAcquisitionInput.DEFAULT_KEYWORD : input.keyword();
        // 断连续跑:整步纳入 withReconnect。排序天然幂等(重新等结果页 + 重选排序),会话层断连由外层
        // 等会话重现后整步重跑。
        return withReconnect("apply_sort", () -> {
            BrowserObservation observed = waitForSearchVerified(keyword, 4, 600L);
            observed = ensurePlainSearchResultPage(observed, keyword);
            if (sort.isComprehensive()) {
                rememberSortedVideoSnapshot(keyword, observed);
                return observed;
            }
            if (sortVerified(observed, keyword, sort)) {
                rememberSortedVideoSnapshot(keyword, observed);
                return observed;
            }
            // 后台可用优先:douyin_ui(合成 hover 展开「筛选」+ 点排序选项),不依赖 CDP hover
            BrowserObservation byUi = sortByDouyinUi(keyword, sort);
            if (byUi != null) {
                rememberSortedVideoSnapshot(keyword, byUi);
                return byUi;
            }
            // 兜底(仅前台):CDP hover/click 打开筛选面板再选
            BrowserObservation panel = openFilterPanel(observed, keyword, sort);
            return selectSortOption(panel, keyword, sort);
        });
    }

    /**
     * 后台可用的排序:douyin_ui 动作在页内合成 hover 展开「筛选」面板,再点排序选项。
     * 成功且校验生效则返回观察;否则返回 null,交回退(CDP hover/click 筛选面板)。
     */
    @Nullable
    private BrowserObservation sortByDouyinUi(String keyword, DouyinSortSpec sort) {
        JsonNode res;
        try {
            res = parse(browser.service_douyin_ui_main("sort", sort.primaryLabel()));
        } catch (RuntimeException e) {
            log.info("[douyin.lead] douyin_ui sort threw, fallback to CDP filter: {}", e.getMessage());
            return null;
        }
        if (!ok(res)) {
            log.info("[douyin.lead] douyin_ui sort not ok, fallback to CDP filter: {}", errorSummary(res));
            return null;
        }
        if (DOM_ONLY_DEBUG) {
            // douyin_ui sort 的 ok 已表示"找到筛选并点中了排序选项",DOM-only 直接信任,
            // 不做后台不可靠的 observe 二次校验(空树校验失败会回退 CDP 筛选面板,导致重复点)。
            log.info("[douyin.lead] sorted via douyin_ui hover (DOM_ONLY, trust ok): {}", sort.primaryLabel());
            return observeMain("all");
        }
        BrowserObservation observed = waitForSortVerifiedAfterSelection(keyword, sort, 10, 700L);
        if (sortVerified(observed, keyword, sort)) {
            log.info("[douyin.lead] sorted via douyin_ui hover (background-capable): {}", sort.primaryLabel());
            return observed;
        }
        log.info("[douyin.lead] douyin_ui sort clicked but not verified, fallback to CDP filter");
        return null;
    }

    @Override
    public BrowserObservation openVideo(int zeroBasedIndex) {
        // 断连续跑:仅【首个视频(index=0)】整步纳入 withReconnect —— index=0 走"点结果区第 N 张封面卡"
        // 路径,天然幂等(已在视频页则复用,否则重新点卡片),重跑安全。
        // index>0 走 douyin_ui next_video 键盘式切换(每次前进一个),【非幂等】:若在一次成功 next_video
        // 之后、方法返回之前断连,重跑会把已切到的"下一个"误判为 already-open 并再次 next_video,造成跳过/错位。
        // 故 index>0 不做整步重连重跑(其内部已有 slowAttempts 业务重试);该视频若因断连失败,executor 会
        // 捕获单视频失败并继续下一个,绝不整轮失败,也不会切错目标。
        if (zeroBasedIndex == 0) {
            return withReconnect("open_video", () -> openVideoInternal(zeroBasedIndex));
        }
        return openVideoInternal(zeroBasedIndex);
    }

    private BrowserObservation openVideoInternal(int zeroBasedIndex) {
        startCommentNetworkCapture();
        BrowserObservation current = observeMain("all");
        if (looksLikeVideoOpenHard(current)) {
            if (zeroBasedIndex > 0) {
                return switchToNextVideoByKeyboard(current, zeroBasedIndex);
            } else {
                return new BrowserObservation(
                        current.ok(),
                        current.url(),
                        current.title(),
                        current.tree(),
                        current.viewportWidth(),
                        current.viewportHeight(),
                        "VIDEO_TARGET",
                        "reused_existing_video_page");
            }
        }
        // 【搜索/排序后等结果就绪(慢环境)】首个视频(index=0)紧接在排序之后,排序会触发结果区
        // 整列重载,3G 下比首屏更慢。点视频前先在一个慢环境上限内等"结果区真正出现视频卡"再继续,
        // 而不是固定等待即点。就绪探测优先复用 douyin_open_video(后台 a11y 树为空时它仍能在页内
        // 直接探测视频卡),命中即提前退出;探测不出也只是多花就绪宽限,随后照常走下面的打开流程。
        if (zeroBasedIndex == 0) {
            waitForVideoResultListReady();
        }
        // 确定性优先:直接点结果区第 N 张【视频】封面卡(跳过图文、按 top-left,页内点元素本身),
        // 绝不会"落到第二张"。失败再回退候选 + 坐标点击。
        BrowserObservation byCard = openVideoByCoverCard(zeroBasedIndex);
        if (byCard != null) {
            return byCard;
        }
        VideoCandidates candidates = sortedVideoSnapshotCandidates(current);
        if (candidates.isEmpty()) {
            candidates = videoCandidatesFromObservation(current);
        }
        if (candidates.isEmpty()) {
            // 慢环境:a11y 候选轮询次数/间隔放大,给慢网懒加载更多渲染时间(命中即提前退出)。
            candidates = waitForVideoTargets(slowAttempts(8), slowMs(700L));
        }
        if (candidates.isEmpty()) {
            BrowserObservation observed = candidates.observation();
            throw new DouyinBrowserException("VIDEO_RESULT_NOT_FOUND",
                    "未找到可打开的视频结果: url=" + observed.url()
                            + ", title=" + observed.title()
                            + ", signals=" + searchSignals(observed, "openclaw")
                            + ", domDebug=" + candidates.debug()
                            + ", tree=" + treeExcerpt(observed.tree()));
        }
        List<VideoResultTarget> visualOrder = candidates.targets();
        int index = Math.min(Math.max(zeroBasedIndex, 0), visualOrder.size() - 1);
        VideoResultTarget target = visualOrder.get(index);
        BrowserObservation opened = clickAndVerifyVideoTarget(target, candidates.observation());
        if (!looksLikeVideoOpenHard(opened)) {
            throw new DouyinBrowserException("VIDEO_OPEN_NOT_CONFIRMED",
                    "已点击第 " + (index + 1) + " 个视频候选，但没有观察到视频页/弹层。target="
                            + target.debugSummary()
                            + ", attemptedPoints=" + videoOpenClickPoints(target, candidates.observation())
                            + ", url=" + opened.url()
                            + ", title=" + opened.title()
                            + ", signals=" + videoOpenSignals(opened));
        }
        if (!openedMatchesTarget(opened, target)) {
            throw new DouyinBrowserException("VIDEO_OPENED_TARGET_MISMATCH",
                    "已打开视频，但打开后的页面没有匹配点击前记录的第一结果卡片。target="
                            + target.debugSummary() + ", url=" + opened.url()
                            + ", title=" + opened.title()
                            + ", tree=" + treeExcerpt(opened.tree()));
        }
        return new BrowserObservation(
                opened.ok(),
                opened.url(),
                opened.title(),
                opened.tree(),
                opened.viewportWidth(),
                opened.viewportHeight(),
                "VIDEO_TARGET",
                target.debugSummary() + ", domDebug=" + candidates.debug());
    }

    /**
     * 确定性打开第 N 个视频:调 douyin_search 同款机制的 douyin_open_video 动作,
     * 页内定位结果区第 N 张【视频】封面卡(跳过图文)并直接点该元素。成功且确认进入
     * 视频页则返回观察;否则返回 null,交回退路径(候选 + 坐标点击)。
     *
     * 【慢环境:点视频重试等列表(核心)】单次 douyin_open_video 在 3G 懒加载下可能拿不到视频卡
     * (no_video_cover_cards / total=0),旧逻辑一拿到 null 就回退 a11y candidates —— 但慢网下
     * candidates 同样空(a11y 树里只有搜索框),直接 VIDEO_RESULT_NOT_FOUND 让任务失败。这里改为:
     * 当扩展回报"列表还没出视频卡(no_video_cover_cards / total=0)"时,不立刻放弃,而在一个慢环境
     * 总上限内退避重试 douyin_open_video、等结果列表加载,直到命中视频卡(确认进入视频页)或超时,
     * 仍不行才返回 null 走 candidates。命中即提前退出,快网下不会白等。带总上限避免无限循环。
     */
    @Nullable
    private BrowserObservation openVideoByCoverCard(int zeroBasedIndex) {
        CoverCardOutcome outcome = null;
        for (int attempt = 0; attempt < OPEN_VIDEO_RELOAD_BACKOFF_MS.length; attempt++) {
            outcome = tryOpenVideoByCoverCardOnce(zeroBasedIndex);
            if (outcome.observation() != null) {
                return outcome.observation();
            }
            if (!outcome.listNotReady()) {
                // 不是"列表未就绪"(例如点了但没进视频页 / 动作异常):重试 douyin_open_video 多半无益,
                // 直接交回退路径(候选 + 坐标点击)。
                return null;
            }
            if (attempt < OPEN_VIDEO_RELOAD_BACKOFF_MS.length - 1) {
                long backoff = OPEN_VIDEO_RELOAD_BACKOFF_MS[attempt];
                log.info("[douyin.lead] cover-card list not ready yet (reason={}), retry waiting list load: "
                                + "attempt={}/{}, backoffMs={}",
                        outcome.reason(), attempt + 1, OPEN_VIDEO_RELOAD_BACKOFF_MS.length, backoff);
                waitMs(backoff);
            }
        }
        log.info("[douyin.lead] cover-card list still not ready after {} reload retries (lastReason={}), "
                        + "fallback to candidates",
                OPEN_VIDEO_RELOAD_BACKOFF_MS.length, outcome == null ? "" : outcome.reason());
        return null;
    }

    /**
     * 单次尝试 douyin_open_video 并确认进入视频页。返回 {@link CoverCardOutcome}:
     *   - observation != null:已确认进入视频页,直接成功;
     *   - listNotReady == true:扩展回报结果列表尚未出视频卡(no_video_cover_cards / total=0),
     *     由外层 {@link #openVideoByCoverCard} 退避重试等列表加载;
     *   - 其余:列表已有卡但本次未确认打开(点了没进页 / 异常),外层不再重试、直接走 candidates。
     */
    private CoverCardOutcome tryOpenVideoByCoverCardOnce(int zeroBasedIndex) {
        JsonNode opened;
        try {
            opened = parse(browser.service_douyin_open_video_main(Math.max(0, zeroBasedIndex)));
        } catch (RuntimeException e) {
            log.info("[douyin.lead] open video by cover card threw, fallback to candidates: {}", e.getMessage());
            return CoverCardOutcome.giveUp("threw:" + e.getMessage());
        }
        if (!ok(opened)) {
            String summary = errorSummary(opened);
            // 扩展端 no_video_cover_cards / total=0 会以失败 message 形式回传(见 douyin_open_video.ts):
            // 这是"列表还没出视频卡",标记为可重试等列表加载,而非直接放弃。
            if (coverCardListNotReady(opened)) {
                log.info("[douyin.lead] open video by cover card: list not ready ({}), will retry waiting", summary);
                return CoverCardOutcome.listNotReady(summary);
            }
            log.info("[douyin.lead] open video by cover card not ok, fallback to candidates: {}", summary);
            return CoverCardOutcome.giveUp(summary);
        }
        JsonNode payload = opened.path("results").path(0).path("payload");
        // ok=true 但扩展回报 total=0(理论少见,稳妥兜底):同样按"列表未就绪"重试等加载。
        if (payload.path("total").asInt(-1) == 0) {
            log.info("[douyin.lead] open video by cover card ok but total=0, treat as list not ready, will retry");
            return CoverCardOutcome.listNotReady("total=0");
        }
        waitMs(slowMs(1_800L));
        BrowserObservation obs = observeMain("all");
        if (looksLikeVideoOpenHard(obs) || looksLikeLoginWall(obs)) {
            tryOk(browser.service_douyin_ui_main("pause", "")); // 暂停视频,避免自动播放
            log.info("[douyin.lead] opened video by cover card (deterministic): index={}, total={}, title={}",
                    payload.path("index").asInt(-1), payload.path("total").asInt(-1), payload.path("title").asText(""));
            return CoverCardOutcome.opened(new BrowserObservation(
                    obs.ok(), obs.url(), obs.title(), obs.tree(),
                    obs.viewportWidth(), obs.viewportHeight(),
                    "VIDEO_TARGET",
                    "opened_by_cover_card:index=" + payload.path("index").asInt(zeroBasedIndex)
                            + ",total=" + payload.path("total").asInt(-1)
                            + ",title=" + payload.path("title").asText("")));
        }
        if (DOM_ONLY_DEBUG && payload.path("clicked").asBoolean(false) && looksLikeVideoOpen(obs)) {
            // DOM-only 下信任门槛从"dispatch 了点击"升级为"确实进了视频页":要求 clicked=true
            // 之外,observe 还能软确认视频页(url 含 /video/、modal_id/aweme_id,或视频播放器
            // 与互动区信号齐全 —— 见 looksLikeVideoOpen)。后台 observe 偶有信号不足时,二次确认
            // 再放宽一点:轻量补一次 observe,给 SPA 更多渲染时间。
            tryOk(browser.service_douyin_ui_main("pause", ""));
            log.info("[douyin.lead] opened video by cover card (DOM_ONLY, clicked+confirmed): index={}, total={}, title={}",
                    payload.path("index").asInt(-1), payload.path("total").asInt(-1), payload.path("title").asText(""));
            return CoverCardOutcome.opened(new BrowserObservation(
                    obs.ok(), obs.url(), obs.title(), obs.tree(),
                    obs.viewportWidth(), obs.viewportHeight(),
                    "VIDEO_TARGET",
                    "opened_by_cover_card_dom_only:index=" + payload.path("index").asInt(zeroBasedIndex)
                            + ",total=" + payload.path("total").asInt(-1)
                            + ",title=" + payload.path("title").asText("")));
        }
        if (DOM_ONLY_DEBUG && payload.path("clicked").asBoolean(false)) {
            // clicked=true 但首轮 observe 没软确认到视频页:不立刻信任,补一次轻量 observe
            // 兜底(后台/导航初期视频页信号可能延迟渲染)。仍确认不过则不信任、返回 giveUp 走回退。
            waitMs(slowMs(900L));
            BrowserObservation reobs = observeMain("all");
            if (looksLikeVideoOpen(reobs) || looksLikeLoginWall(reobs)) {
                tryOk(browser.service_douyin_ui_main("pause", ""));
                log.info("[douyin.lead] opened video by cover card (DOM_ONLY, clicked+confirmed on retry): index={}, total={}, title={}",
                        payload.path("index").asInt(-1), payload.path("total").asInt(-1), payload.path("title").asText(""));
                return CoverCardOutcome.opened(new BrowserObservation(
                        reobs.ok(), reobs.url(), reobs.title(), reobs.tree(),
                        reobs.viewportWidth(), reobs.viewportHeight(),
                        "VIDEO_TARGET",
                        "opened_by_cover_card_dom_only:index=" + payload.path("index").asInt(zeroBasedIndex)
                                + ",total=" + payload.path("total").asInt(-1)
                                + ",title=" + payload.path("title").asText("")));
            }
            log.info("[douyin.lead] cover-card clicked=true but video page not confirmed (DOM_ONLY), fallback to candidates");
            return CoverCardOutcome.giveUp("clicked_but_not_confirmed");
        }
        log.info("[douyin.lead] cover-card click ok but video not confirmed, fallback to candidates");
        return CoverCardOutcome.giveUp("ok_but_not_confirmed");
    }

    /**
     * 单次 cover-card 尝试的结局载体(见 {@link #tryOpenVideoByCoverCardOnce})。
     * observation != null → 成功;listNotReady → 列表未就绪可重试;否则放弃走 candidates。
     */
    private record CoverCardOutcome(@Nullable BrowserObservation observation,
                                    boolean listNotReady,
                                    String reason) {
        static CoverCardOutcome opened(BrowserObservation obs) {
            return new CoverCardOutcome(obs, false, "opened");
        }

        static CoverCardOutcome listNotReady(String reason) {
            return new CoverCardOutcome(null, true, reason);
        }

        static CoverCardOutcome giveUp(String reason) {
            return new CoverCardOutcome(null, false, reason);
        }
    }

    /**
     * 扩展端 douyin_open_video 回报"结果列表还没出视频卡":no_video_cover_cards(失败 message)
     * 或 total=0。3G 懒加载下这是"还没加载完",应重试等待而非直接判失败。
     */
    private boolean coverCardListNotReady(JsonNode openVideoResult) {
        if (openVideoResult == null) {
            return false;
        }
        String summary = (openVideoResult.path("code").asText("") + " "
                + openVideoResult.path("message").asText("")).toLowerCase(Locale.ROOT);
        return summary.contains("no_video_cover_cards")
                || summary.contains("video_card_not_found")
                || summary.contains("total=0");
    }

    /**
     * 切到下一个视频(契约2,DOM 化)。
     *
     * 旧实现用 service_press_key("ArrowDown")(CDP 在后台标签是 no-op)再靠 observe 校验,
     * 但后台 a11y 树为空 → videoObservationChanged 判不出变化 → 反复落到
     * VIDEO_KEYBOARD_SWITCH_NOT_CONFIRMED 失败。现改为页内 DOM 切换:
     *   调 service_douyin_ui_main("next_video","") —— 扩展端在页内点下一个视频并已自行
     *   确认 url 的视频 id 变化,以 {ok:changed, op, detail} 回报(top-level ok=changed)。
     * 后端据 ok/detail【信任】切换成功,不再依赖后台空树 observe 二次校验。
     * 按慢系数重试(总上限 = 退避序列长度,绝不无限循环);成功后 tryOk(pause) 暂停自动播放,
     * 再 observeMain 取一次观察(后台可能空,不影响判定),返回 VIDEO_TARGET 并标注
     * switched_next_dom:index=N。DOM-only 关闭时(前台模式)保留原 CDP ArrowDown 作兜底。
     */
    private BrowserObservation switchToNextVideoByKeyboard(BrowserObservation current, int zeroBasedIndex) {
        BrowserObservation before = closeCommentPanelBeforeVideoSwitch(current);

        // 按慢系数把重试次数放大;每轮退避递增,带总上限避免无限循环。
        int attempts = slowAttempts(3);
        String lastDetail = "";
        String lastSummary = "";
        for (int attempt = 0; attempt < attempts; attempt++) {
            startCommentNetworkCapture();
            JsonNode res;
            try {
                res = parse(browser.service_douyin_ui_main("next_video", ""));
            } catch (RuntimeException e) {
                lastSummary = "threw:" + e.getMessage();
                log.info("[douyin.lead] douyin_ui next_video threw (attempt {}/{}): {}",
                        attempt + 1, attempts, e.getMessage());
                waitMs(slowMs(attempt == 0 ? 600L : 900L));
                continue;
            }
            JsonNode payload = res.path("results").path(0).path("payload");
            lastDetail = payload.path("detail").asText("");
            if (ok(res)) {
                // 扩展已确认 url 的视频 id 变化(ok=changed):信任切换成功,不做后台空树 observe 校验。
                tryOk(browser.service_douyin_ui_main("pause", "")); // 暂停自动播放
                waitMs(slowMs(800L));
                BrowserObservation after = observeMain("all"); // 后台可能空,仅用于回带 url/title/tree
                log.info("[douyin.lead] switched to next video via douyin_ui (DOM, trust ok): index={}, detail={}",
                        zeroBasedIndex, lastDetail);
                return new BrowserObservation(
                        after.ok(),
                        after.url(),
                        after.title(),
                        after.tree(),
                        after.viewportWidth(),
                        after.viewportHeight(),
                        "VIDEO_TARGET",
                        "switched_next_dom:index=" + zeroBasedIndex
                                + (lastDetail.isBlank() ? "" : ",detail=" + lastDetail));
            }
            lastSummary = errorSummary(res);
            log.info("[douyin.lead] douyin_ui next_video not ok (attempt {}/{}): {}",
                    attempt + 1, attempts, lastSummary);
            waitMs(slowMs(attempt == 0 ? 600L : 900L));
        }

        // 兜底(仅前台,DOM-only 关闭时):原 CDP ArrowDown + observe 校验路径。
        // 后台标签 CDP 多半 no-op,DOM-only 模式下直接跳过,避免无谓重试后再抛假阴性。
        if (!DOM_ONLY_DEBUG) {
            BrowserObservation byKey = switchToNextVideoByCdpArrowDown(before, zeroBasedIndex);
            if (byKey != null) {
                return byKey;
            }
        }

        throw new DouyinBrowserException("VIDEO_KEYBOARD_SWITCH_NOT_CONFIRMED",
                "已尝试用 douyin_ui next_video 切换第 " + (zeroBasedIndex + 1)
                        + " 个视频(共 " + attempts + " 次),但扩展未确认切换。lastDetail=" + lastDetail
                        + ", lastError=" + lastSummary
                        + ", url=" + before.url()
                        + ", title=" + before.title()
                        + ", tree=" + treeExcerpt(before.tree()));
    }

    /**
     * 前台兜底:原 CDP ArrowDown + observe 确认切换(后台标签多为 no-op,仅 DOM-only 关闭时调用)。
     * 确认切换返回观察;始终判不出变化则返回 null,交外层抛出明确错误(不静默吞)。
     */
    @Nullable
    private BrowserObservation switchToNextVideoByCdpArrowDown(BrowserObservation before, int zeroBasedIndex) {
        for (int attempt = 0; attempt < 3; attempt++) {
            startCommentNetworkCapture();
            if (!tryOk(browser.service_press_key_main("ArrowDown"))) {
                waitMs(350L);
                continue;
            }
            waitMs(attempt == 0 ? 1_500L : 900L);
            BrowserObservation after = observeMain("all");
            if (!looksLikeVideoOpenHard(after)) {
                continue;
            }
            if (videoObservationChanged(before, after) || attempt == 2) {
                return new BrowserObservation(
                        after.ok(),
                        after.url(),
                        after.title(),
                        after.tree(),
                        after.viewportWidth(),
                        after.viewportHeight(),
                        "VIDEO_TARGET",
                        "keyboard_arrow_down:index=" + zeroBasedIndex);
            }
        }
        return null;
    }

    private BrowserObservation closeCommentPanelBeforeVideoSwitch(BrowserObservation current) {
        BrowserObservation observed = current;
        if (observed != null && looksLikeCommentsOpen(observed.tree())) {
            tryOk(browser.service_press_key_main("x"));
            waitMs(450L);
            BrowserObservation afterClose = observeMain("all");
            if (looksLikeVideoOpenHard(afterClose)) {
                observed = afterClose;
            }
        }
        return observed;
    }

    private boolean videoObservationChanged(BrowserObservation before, BrowserObservation after) {
        if (before == null || after == null) {
            return false;
        }
        String beforeId = videoIdentity(before.url());
        String afterId = videoIdentity(after.url());
        if (!beforeId.isBlank() && !afterId.isBlank()) {
            return !beforeId.equals(afterId);
        }
        String beforeUrl = before.url() == null ? "" : before.url();
        String afterUrl = after.url() == null ? "" : after.url();
        if (!beforeUrl.isBlank() && !afterUrl.isBlank() && !beforeUrl.equals(afterUrl)) {
            return true;
        }
        String beforeTitle = before.title() == null ? "" : before.title();
        String afterTitle = after.title() == null ? "" : after.title();
        return !beforeTitle.isBlank() && !afterTitle.isBlank() && !beforeTitle.equals(afterTitle);
    }

    @Override
    public BrowserObservation openComments() {
        // 断连续跑:整步纳入 withReconnect。打开评论天然幂等(已打开则复用),会话层断连由外层
        // 等会话重现后整步重跑。
        return withReconnect("open_comments", this::openCommentsInternal);
    }

    private BrowserObservation openCommentsInternal() {
        startCommentNetworkCapture();
        BrowserObservation observed = observeMain("all");
        if (commentsPanelReady(observed)) {
            return commentsOpenedObservation(observed, "already_open");
        }

        // 后台可用优先:douyin_ui 点评论按钮 [data-e2e="feed-comment-icon"]
        // (页内 DOM 点击,不依赖 'x' 快捷键的 CDP/焦点)。它的 ok=hasList(),即评论列表确已出现。
        boolean uiOpened = false;
        try {
            JsonNode res = parse(browser.service_douyin_ui_main("open_comments", ""));
            String detail = res.path("results").path(0).path("payload").path("detail").asText("");
            if (ok(res)) {
                uiOpened = detail.contains("opened") || detail.contains("already_open");
                log.info("[douyin.lead] douyin_ui open_comments: {}", detail);
            } else {
                log.info("[douyin.lead] douyin_ui open_comments not ok: {}", errorSummary(res));
            }
        } catch (RuntimeException e) {
            log.info("[douyin.lead] douyin_ui open_comments threw: {}", e.getMessage());
        }
        // 慢环境:点开评论后列表渲染更慢,二次确认前的就绪宽限按慢系数放大,避免前台模式误走 'x' 兜底。
        waitMs(slowMs(1_200L));
        observed = observeMain("all");
        if (commentsPanelReady(observed)) {
            return commentsOpenedObservation(observed, "douyin_ui_icon");
        }
        if (DOM_ONLY_DEBUG && uiOpened) {
            // 后台 observe 空树,commentsPanelReady 判不出;但 douyin_ui 的 ok 已确认评论列表出现,
            // DOM-only 直接信任,跳过 'x' 快捷键兜底。
            log.info("[douyin.lead] comments opened via douyin_ui (DOM_ONLY, trust ok)");
            return commentsOpenedObservation(observed, "douyin_ui_icon_dom_only");
        }

        // 兜底(仅前台):'x' 快捷键(DOM-only 模式下跳过)
        if (!DOM_ONLY_DEBUG) {
            observed = pressDouyinCommentShortcutX(observed);
            if (commentsPanelReady(observed)) {
                return commentsOpenedObservation(observed, "shortcut_x");
            }
        }

        throw new DouyinBrowserException("COMMENTS_TRIGGER_NOT_FOUND",
                "未找到评论入口: url=" + observed.url()
                        + ", title=" + observed.title()
                        + ", signals=" + commentTriggerSignals(observed)
                        + ", tree=" + treeExcerpt(observed.tree()));
    }

    private boolean commentsPanelReady(BrowserObservation observed) {
        if (observed == null) {
            return false;
        }
        List<TreeLine> lines = parseTreeLines(observed.tree());
        if (hasDouyinCommentPanelTabs(lines) && hasDouyinCommentPanelStructure(lines)) {
            return true;
        }
        if (looksLikeStrongCommentPanelText(observed.tree())
                && hasDouyinCommentPanelStructure(lines)) {
            return true;
        }
        if (looksLikeStrongCommentPanelText(observed.tree())
                && hasVisibleCommentItemEvidence(lines)) {
            return true;
        }
        RegionInfo runtimeRegion = detectRuntimeCommentRegion();
        if (runtimeRegion != null && extractedCommentPanelReady(runtimeRegion, observed.url())) {
            return true;
        }
        return collector.detectCommentRegion(observed)
                .filter(region -> looksLikeStrongCommentPanelText(observed.tree())
                        || extractedCommentPanelReady(region, observed.url()))
                .isPresent();
    }

    private boolean extractedCommentPanelReady(RegionInfo region, String videoKey) {
        if (COMMENT_NETWORK_ONLY_COLLECTION) {
            return false;
        }
        if (region == null) {
            return false;
        }
        try {
            boolean registered = tryOk(browser.service_register_region_main(
                    region.regionKey(), region.x(), region.y(), region.width(), region.height(), region.source()));
            if (!registered) {
                return false;
            }
            JsonNode root = parse(browser.service_extract_region_main(region.regionKey(), 40));
            if (!root.path("ok").asBoolean(false)) {
                return false;
            }
            int declared = collector.declaredCommentCountFromExtractedRegion(root);
            List<DouyinCommentItem> comments = collector.commentsFromExtractedRegion(root, videoKey);
            if (declared > 0 || !comments.isEmpty()) {
                return true;
            }
            JsonNode items = root.path("results").path(0).path("payload").path("items");
            if (!items.isArray()) {
                return false;
            }
            for (JsonNode item : items) {
                String text = item.path("text").asText("").replaceAll("\\s+", "");
                if (text.contains("全部评论")
                        || text.contains("说点什么")
                        || text.contains("发表评论")
                        || text.contains("暂时没有评论")) {
                    return true;
                }
            }
            return false;
        } catch (Exception ignored) {
            return false;
        }
    }

    private BrowserObservation waitForCommentsPanelReadyObservation(
            BrowserObservation current,
            int attempts,
            long waitMs) {
        BrowserObservation observed = current;
        for (int i = 0; i < Math.max(1, attempts); i++) {
            if (commentsPanelReady(observed)) {
                return observed;
            }
            waitMs(waitMs);
            observed = observeMain("all");
        }
        return observed;
    }

    private BrowserObservation commentsOpenedObservation(BrowserObservation observed, String method) {
        return new BrowserObservation(
                observed.ok(),
                observed.url(),
                observed.title(),
                observed.tree(),
                observed.viewportWidth(),
                observed.viewportHeight(),
                "COMMENTS_OPEN",
                "method=" + method + "; signals=" + commentTriggerSignals(observed));
    }

    @Override
    public RegionInfo detectCommentRegion() {
        // 断连续跑:整步纳入 withReconnect。区域探测/注册天然幂等(重新探测+重新注册无副作用),
        // 会话层断连由外层等会话重现后整步重跑。
        return withReconnect("detect_comment_region", this::detectCommentRegionInternal);
    }

    private RegionInfo detectCommentRegionInternal() {
        BrowserObservation observed = observeMain("all");
        RegionInfo domDetected = detectRuntimeCommentRegion();
        if (domDetected != null) {
            parkMouseInCommentRegion(domDetected, "park_comments_region_after_dom_detect");
            return domDetected;
        }
        RegionInfo detected = collector.detectCommentRegion(observed)
                .orElseGet(() -> fallbackVisibleCommentPanelRegion(observed));
        if (detected == null) {
            throw new DouyinBrowserException(
                    "COMMENT_REGION_NOT_FOUND",
                    "未能确认评论区已打开，拒绝使用整屏坐标兜底滚动。url=" + observed.url()
                            + ", title=" + observed.title()
                            + ", signals=" + commentTriggerSignals(observed)
                            + ", tree=" + treeExcerpt(observed.tree()));
        }
        RegionInfo region = withCommentPanelSafePoint(observed, detected);
        requireOk(browser.service_register_region_main(
                        region.regionKey(), region.x(), region.y(), region.width(), region.height(), region.source()),
                "register_comments_region");
        parkMouseInCommentRegion(region, "park_comments_region_after_register");
        return region;
    }

    @Nullable
    private RegionInfo fallbackVisibleCommentPanelRegion(BrowserObservation observed) {
        if (observed == null || !looksLikeStrongCommentPanelText(observed.tree())) {
            return null;
        }
        int viewportW = Math.max(1, observed.viewportWidth());
        int viewportH = Math.max(1, observed.viewportHeight());
        if (viewportW < 700 || viewportH < 360) {
            return null;
        }
        double x = Math.max(360.0d, viewportW * 0.54d);
        double y = 0.0d;
        double width = Math.max(260.0d, viewportW - x);
        double height = viewportH;
        return new RegionInfo(
                "douyin.comments",
                x,
                y,
                width,
                height,
                x + width * 0.72d,
                Math.min(viewportH - 72.0d, Math.max(180.0d, viewportH * 0.62d)),
                "comments-open-right-panel-fallback");
    }

    private boolean looksLikeStrongCommentPanelText(String tree) {
        String value = tree == null ? "" : tree;
        String compact = value.replaceAll("\\s+", "");
        return compact.contains("全部评论")
                || compact.matches(".*\\d+条评论.*")
                || compact.contains("评论区")
                || compact.contains("评论详情")
                || compact.contains("详情评论")
                || compact.contains("评论回复")
                || value.contains("说点什么")
                || value.contains("写评论")
                || value.contains("发表评论")
                || value.contains("暂时没有评论");
    }

    @Nullable
    private RegionInfo detectRuntimeCommentRegion() {
        try {
            JsonNode root = parse(browser.service_detect_region_main("douyin.comments", "dom"));
            if (!root.path("ok").asBoolean(false)) {
                return null;
            }
            JsonNode payload = root.path("results").path(0).path("payload");
            JsonNode rect = payload.path("rect");
            double x = rect.path("x").asDouble(Double.NaN);
            double y = rect.path("y").asDouble(Double.NaN);
            double width = rect.path("width").asDouble(Double.NaN);
            double height = rect.path("height").asDouble(Double.NaN);
            if (!Double.isFinite(x) || !Double.isFinite(y)
                    || !Double.isFinite(width) || !Double.isFinite(height)
                    || width <= 0.0d || height <= 0.0d) {
                return null;
            }
            JsonNode safe = payload.path("safePoint");
            double safeX = safe.path("x").asDouble(x + width / 2.0d);
            double safeY = safe.path("y").asDouble(y + height / 2.0d);
            return new RegionInfo(
                    "douyin.comments",
                    x,
                    y,
                    width,
                    height,
                    safeX,
                    safeY,
                    payload.path("source").asText("dom_detect"));
        } catch (Exception ignored) {
            return null;
        }
    }

    @Override
    public CommentCollectionResult collectAllComments(RegionInfo region) {
        return collectAllComments(region, ignored -> {
        });
    }

    @Override
    public CommentCollectionResult collectAllComments(RegionInfo region,
                                                      Consumer<CommentCollectionProgress> progressConsumer) {
        // 断连续跑:整步纳入 withReconnect。采集天然幂等(以 commentKey 去重累积,重跑会重新去重收敛),
        // 会话层断连(滚动/抽取在断连窗口内抛 SESSION_DETACHED/DEADLINE_EXCEEDED)由外层等会话重现后整步重跑,
        // 而非整轮失败。注意:采集步本身已内吞了大量滚动失败(keep collector alive),只有真正逃逸到顶层的
        // 会话层异常才会触发重连重跑。
        return withReconnect("collect_all_comments",
                () -> collectAllCommentsInternal(region, progressConsumer));
    }

    private CommentCollectionResult collectAllCommentsInternal(RegionInfo region,
                                                               Consumer<CommentCollectionProgress> progressConsumer) {
        LinkedHashMap<String, DouyinCommentItem> seen = new LinkedHashMap<>();
        int stableNoNew = 0;
        int stableEndMarker = 0;
        int declared = 0;
        boolean complete = false;
        String stopReason = "unknown";
        ScrollRegionEvidence lastScrollEvidence = ScrollRegionEvidence.none();
        int lastExtractedCount = 0;
        int lastVisibleCount = 0;
        int lastNewItems = 0;
        boolean lastCollectionAdvanced = false;
        int lastWindowBeforeCount = 0;
        int lastWindowAfterCount = 0;
        int effectiveScrolls = 0;
        int advancedWindows = 0;
        int forwardScrolls = 0;
        int repeatedWindows = 0;
        int totalNewItems = 0;
        int staleScrolls = 0;
        String lastWindowSignature = "";
        long lastLoopMs = 0L;
        int domExtractCursor = 0;
        int lastProgressComments = -1;
        int lastProgressPages = -1;
        // 已为"续拉在途未回"额外等待的次数,达到上限后即便仍在途也按正常判定收口,避免无限等待。
        int inflightExtraWaits = 0;
        NetworkCollectionState network = new NetworkCollectionState();
        if (COMMENT_NETWORK_ONLY_COLLECTION && !commentNetworkCaptureActive) {
            startCommentNetworkCapture();
        }
        // 确保评论区域已在扩展侧 RegionRegistry 注册,供后台 DOM 区域滚动
        // (scroll_region 的抖音评论容器滚动)定位使用——否则 service_scroll_region_main
        // 会因 "no region registered" 失败,采集只能拿到首屏第一页。
        tryOk(browser.service_register_region_main(
                region.regionKey(), region.x(), region.y(), region.width(), region.height(),
                region.source() == null || region.source().isBlank() ? "comments-collect" : region.source()));
        BrowserObservation current = observeMain("all");
        String collectionVideoIdentity = videoIdentity(current.url());
        ensureCommentsPanelReady(region, current, "before_collect_loop");

        for (int scrolls = 0; scrolls <= MAX_SCROLL_PROTECTION; scrolls++) {
            long loopStartedAt = System.nanoTime();
            if (Thread.currentThread().isInterrupted()) {
                return collectionResult(seen, declared, false, "INTERRUPTED", scrolls, stableNoNew,
                        stableEndMarker, lastExtractedCount, lastVisibleCount, lastNewItems,
                        lastCollectionAdvanced, lastWindowBeforeCount, lastWindowAfterCount,
                        effectiveScrolls, advancedWindows, forwardScrolls, repeatedWindows, totalNewItems, staleScrolls,
                        lastWindowSignature, lastLoopMs, lastScrollEvidence, network);
            }
            if (!COMMENT_NETWORK_ONLY_COLLECTION && shouldRunDeepPanelCheck(scrolls, stableNoNew, lastScrollEvidence)) {
                ensureCommentsPanelReady(region, current, "before_extract_" + scrolls);
            }
            if (!COMMENT_NETWORK_ONLY_COLLECTION && videoChangedDuringCollection(collectionVideoIdentity, current)) {
                return collectionResult(seen, declared, false, "VIDEO_CHANGED_DURING_COLLECTION", scrolls, stableNoNew,
                        stableEndMarker, lastExtractedCount, lastVisibleCount, lastNewItems,
                        lastCollectionAdvanced, lastWindowBeforeCount, lastWindowAfterCount,
                        effectiveScrolls, advancedWindows, forwardScrolls, repeatedWindows, totalNewItems, staleScrolls,
                        lastWindowSignature, lastLoopMs, lastScrollEvidence, network);
            }
            if (!COMMENT_NETWORK_ONLY_COLLECTION && !network.primaryActive() && !commentsPanelAvailable(current, region)) {
                return collectionResult(seen, declared, false, "COMMENT_PANEL_LOST_DURING_COLLECTION", scrolls, stableNoNew,
                        stableEndMarker, lastExtractedCount, lastVisibleCount, lastNewItems,
                        lastCollectionAdvanced, lastWindowBeforeCount, lastWindowAfterCount,
                        effectiveScrolls, advancedWindows, forwardScrolls, repeatedWindows, totalNewItems, staleScrolls,
                        lastWindowSignature, lastLoopMs, lastScrollEvidence, network);
            }
            CommentWindowHarvest harvest = harvestCommentWindow(
                    seen,
                    region,
                    current,
                    declared,
                    domExtractCursor,
                    network,
                    shouldUseDomFallback(network, scrolls, stableEndMarker));
            domExtractCursor = harvest.nextDomStartIndex();
            declared = harvest.declaredCommentCount();
            lastWindowBeforeCount = harvest.beforeCount();
            lastWindowAfterCount = harvest.afterCount();
            lastNewItems = harvest.newItems();
            lastExtractedCount = harvest.extractedCount();
            lastVisibleCount = harvest.visibleCount();
            if (seen.isEmpty() && declared <= 0 && harvest.emptyReached()) {
                return collectionResult(seen, declared, true, "NO_COMMENTS", scrolls, stableNoNew,
                        stableEndMarker, lastExtractedCount, lastVisibleCount, lastNewItems,
                        lastCollectionAdvanced, lastWindowBeforeCount, lastWindowAfterCount,
                        effectiveScrolls, advancedWindows, forwardScrolls, repeatedWindows, totalNewItems, staleScrolls,
                        lastWindowSignature, lastLoopMs, lastScrollEvidence, network);
            }
            lastCollectionAdvanced = lastNewItems > 0;
            totalNewItems += lastNewItems;
            if (lastCollectionAdvanced) {
                advancedWindows++;
            }
            if (shouldPublishCommentProgress(seen.size(), network.pageCount(), lastProgressComments, lastProgressPages, scrolls)) {
                publishCommentProgress(progressConsumer, seen.size(), declared, scrolls, network, "");
                lastProgressComments = seen.size();
                lastProgressPages = network.pageCount();
            }
            boolean endReached = harvest.endReached();
            if (harvest.networkHasMoreFalse()) {
                waitMs(COMMENT_FINAL_SETTLE_DELAY_MS);
                CommentWindowHarvest finalHarvest = harvestCommentWindow(
                        seen,
                        region,
                        current,
                        declared,
                        domExtractCursor,
                        network,
                        false);
                domExtractCursor = finalHarvest.nextDomStartIndex();
                declared = finalHarvest.declaredCommentCount();
                if (finalHarvest.newItems() > 0) {
                    lastExtractedCount = finalHarvest.extractedCount();
                    lastVisibleCount = finalHarvest.visibleCount();
                    lastWindowBeforeCount = finalHarvest.beforeCount();
                    lastWindowAfterCount = finalHarvest.afterCount();
                    lastNewItems = finalHarvest.newItems();
                    lastCollectionAdvanced = true;
                    totalNewItems += finalHarvest.newItems();
                    advancedWindows++;
                }
                return collectionResult(seen, declared, true,
                        seen.isEmpty() ? "NO_COMMENTS" : "NETWORK_HAS_MORE_FALSE",
                        scrolls, stableNoNew, stableEndMarker,
                        lastExtractedCount, lastVisibleCount, lastNewItems,
                        lastCollectionAdvanced, lastWindowBeforeCount, lastWindowAfterCount,
                        effectiveScrolls, advancedWindows, forwardScrolls, repeatedWindows, totalNewItems, staleScrolls,
                        lastWindowSignature, lastLoopMs, lastScrollEvidence, network);
            }
            // 慢网络防漏采:本窗口没有新增,但 network capture 仍有"在途未回"的续拉时,
            // 不要急着把它计入空转/稳定窗口而提前判到底——多等一个 settle 再重新 drain。
            // 该等待既不 scroll 也不累加计数器,受 inflightExtraWaits 上限保护避免无限等待。
            if (!lastCollectionAdvanced
                    && network.hasPendingPulls()
                    && inflightExtraWaits < COMMENT_NETWORK_INFLIGHT_MAX_EXTRA_WAITS) {
                inflightExtraWaits++;
                waitMs(COMMENT_NETWORK_INFLIGHT_SETTLE_MS);
                continue;
            }
            if (lastCollectionAdvanced) {
                inflightExtraWaits = 0;
            }
            if (!lastCollectionAdvanced) {
                stableNoNew++;
            } else {
                stableNoNew = 0;
            }
            if (COMMENT_NETWORK_ONLY_COLLECTION
                    && !network.primaryActive()
                    && scrolls >= COMMENT_NETWORK_ONLY_NO_PAGE_SCROLL_LIMIT) {
                return collectionResult(seen, declared, false,
                        "NETWORK_PAGES_NOT_OBSERVED",
                        scrolls, stableNoNew, stableEndMarker,
                        lastExtractedCount, lastVisibleCount, lastNewItems,
                        lastCollectionAdvanced, lastWindowBeforeCount, lastWindowAfterCount,
                        effectiveScrolls, advancedWindows, forwardScrolls, repeatedWindows, totalNewItems, staleScrolls,
                        lastWindowSignature, lastLoopMs, lastScrollEvidence, network);
            }
            if (COMMENT_NETWORK_ONLY_COLLECTION
                    && network.primaryActive()
                    && stableNoNew >= COMMENT_NETWORK_ONLY_STALE_WINDOW_LIMIT) {
                return collectionResult(seen, declared, false,
                        "NETWORK_NOT_ADVANCING",
                        scrolls, stableNoNew, stableEndMarker,
                        lastExtractedCount, lastVisibleCount, lastNewItems,
                        lastCollectionAdvanced, lastWindowBeforeCount, lastWindowAfterCount,
                        effectiveScrolls, advancedWindows, forwardScrolls, repeatedWindows, totalNewItems, staleScrolls,
                        lastWindowSignature, lastLoopMs, lastScrollEvidence, network);
            }
            if (endReached && !lastCollectionAdvanced) {
                stableEndMarker++;
            } else if (endReached) {
                stableEndMarker = 1;
            } else {
                stableEndMarker = 0;
            }
            if (stableEndMarker >= END_MARKER_STABLE_WINDOWS) {
                waitMs(COMMENT_FINAL_SETTLE_DELAY_MS);
                current = observeForCommentCollection(network);
                CommentWindowHarvest finalHarvest = harvestCommentWindow(
                        seen,
                        region,
                        current,
                        declared,
                        domExtractCursor,
                        network,
                        shouldUseDomFallback(network, scrolls, stableEndMarker));
                domExtractCursor = finalHarvest.nextDomStartIndex();
                declared = finalHarvest.declaredCommentCount();
                if (finalHarvest.newItems() > 0) {
                    lastExtractedCount = finalHarvest.extractedCount();
                    lastVisibleCount = finalHarvest.visibleCount();
                    lastWindowBeforeCount = finalHarvest.beforeCount();
                    lastWindowAfterCount = finalHarvest.afterCount();
                    lastNewItems = finalHarvest.newItems();
                    lastCollectionAdvanced = true;
                    totalNewItems += finalHarvest.newItems();
                    advancedWindows++;
                }
                complete = declared <= 0 || seen.size() >= declared;
                stopReason = complete ? "END_OF_LIST" : "END_OF_LIST_DECLARED_MISMATCH";
                return collectionResult(seen, declared, complete, stopReason, scrolls, stableNoNew,
                        stableEndMarker, lastExtractedCount, lastVisibleCount, lastNewItems,
                        lastCollectionAdvanced, lastWindowBeforeCount, lastWindowAfterCount,
                        effectiveScrolls, advancedWindows, forwardScrolls, repeatedWindows, totalNewItems, staleScrolls,
                        lastWindowSignature, lastLoopMs, lastScrollEvidence, network);
            }
            ScrollRegionEvidence scrollEvidence = scrollCommentRegion(region, scrolls, COMMENT_NETWORK_ONLY_COLLECTION);
            lastScrollEvidence = scrollEvidence;
            if (scrollEvidence.forwardProgress()) {
                effectiveScrolls++;
                forwardScrolls++;
            } else if (scrollEvidence.repeatedWindow()) {
                repeatedWindows++;
            } else {
                staleScrolls++;
            }
            lastWindowSignature = scrollEvidence.afterWindowSignature();
            waitMs(commentScrollSettleDelay(network));
            if (!COMMENT_NETWORK_ONLY_COLLECTION) {
                current = observeForCommentCollection(network);
                if (videoChangedDuringCollection(collectionVideoIdentity, current)) {
                    return collectionResult(seen, declared, false, "VIDEO_CHANGED_DURING_SCROLL", scrolls + 1, stableNoNew,
                            stableEndMarker, lastExtractedCount, lastVisibleCount, lastNewItems,
                            lastCollectionAdvanced, lastWindowBeforeCount, lastWindowAfterCount,
                            effectiveScrolls, advancedWindows, forwardScrolls, repeatedWindows, totalNewItems, staleScrolls,
                            lastWindowSignature, lastLoopMs, lastScrollEvidence, network);
                }
                boolean postScrollEndReached = !network.primaryActive()
                        && commentsEndReached(current, region, ExtractedComments.empty());
                boolean panelAvailableAfterScroll = network.primaryActive()
                        || postScrollEndReached
                        || commentsPanelAvailable(current, region);
                if (!network.primaryActive()
                        && scrollEvidence.panelLostSignal()
                        && !postScrollEndReached
                        && !panelAvailableAfterScroll) {
                    return collectionResult(seen, declared, false, "COMMENT_PANEL_LOST_DURING_SCROLL", scrolls + 1, stableNoNew,
                            stableEndMarker, lastExtractedCount, lastVisibleCount, lastNewItems,
                            lastCollectionAdvanced, lastWindowBeforeCount, lastWindowAfterCount,
                            effectiveScrolls, advancedWindows, forwardScrolls, repeatedWindows, totalNewItems, staleScrolls,
                            lastWindowSignature, lastLoopMs, lastScrollEvidence, network);
                }
                if (!network.primaryActive() && !panelAvailableAfterScroll) {
                    return collectionResult(seen, declared, false, "COMMENT_PANEL_LOST_AFTER_SCROLL", scrolls + 1, stableNoNew,
                            stableEndMarker, lastExtractedCount, lastVisibleCount, lastNewItems,
                            lastCollectionAdvanced, lastWindowBeforeCount, lastWindowAfterCount,
                            effectiveScrolls, advancedWindows, forwardScrolls, repeatedWindows, totalNewItems, staleScrolls,
                            lastWindowSignature, lastLoopMs, lastScrollEvidence, network);
                }
            }
            lastLoopMs = waitForNetworkCadenceIfNeeded(loopStartedAt, network);
        }
        stopReason = "PROTECTION_LIMIT";
        return collectionResult(seen, declared, complete, stopReason, MAX_SCROLL_PROTECTION, stableNoNew,
                stableEndMarker, lastExtractedCount, lastVisibleCount, lastNewItems,
                lastCollectionAdvanced, lastWindowBeforeCount, lastWindowAfterCount,
                effectiveScrolls, advancedWindows, forwardScrolls, repeatedWindows, totalNewItems, staleScrolls,
                lastWindowSignature, lastLoopMs, lastScrollEvidence, network);
    }

    private boolean shouldPublishCommentProgress(int commentsCollected,
                                                 int networkPages,
                                                 int lastProgressComments,
                                                 int lastProgressPages,
                                                 int scrolls) {
        return commentsCollected != lastProgressComments
                || networkPages != lastProgressPages
                || scrolls == 0
                || scrolls % 10 == 0;
    }

    private void publishCommentProgress(Consumer<CommentCollectionProgress> progressConsumer,
                                        int commentsCollected,
                                        int declared,
                                        int scrolls,
                                        NetworkCollectionState network,
                                        String stopReason) {
        if (progressConsumer == null) {
            return;
        }
        progressConsumer.accept(new CommentCollectionProgress(
                commentsCollected,
                declared,
                scrolls,
                network == null ? 0 : network.pageCount(),
                network != null && network.hasTerminalPage(),
                network != null && network.primaryActive() ? "network_observed" : "dom_or_a11y",
                stopReason));
    }

    private boolean shouldRunDeepPanelCheck(int scrolls, int stableNoNew, ScrollRegionEvidence evidence) {
        if (scrolls == 0) {
            return true;
        }
        if (evidence != null && evidence.panelLostSignal()) {
            return true;
        }
        return stableNoNew > 0 && scrolls % 40 == 0;
    }

    private BrowserObservation observeForCommentCollection(NetworkCollectionState network) {
        if (network == null || !network.primaryActive()) {
            return observeMain("all");
        }
        try {
            return observeMain("default");
        } catch (RuntimeException e) {
            return observeMain("all");
        }
    }

    private long commentScrollSettleDelay(NetworkCollectionState network) {
        return network != null && network.primaryActive()
                ? COMMENT_NETWORK_SCROLL_SETTLE_DELAY_MS
                : COMMENT_SCROLL_SETTLE_DELAY_MS;
    }

    private long waitForNetworkCadenceIfNeeded(long loopStartedAt, NetworkCollectionState network) {
        long elapsed = elapsedMs(loopStartedAt);
        if (network == null || !network.primaryActive()) {
            return elapsed;
        }
        long remaining = COMMENT_NETWORK_PAGE_TARGET_INTERVAL_MS - elapsed;
        if (remaining > 0) {
            waitMs(remaining);
            return elapsedMs(loopStartedAt);
        }
        return elapsed;
    }

    private boolean commentsPanelAvailable(BrowserObservation observed, RegionInfo region) {
        if (commentsPanelStillVisible(observed, region)) {
            return true;
        }
        return extractedCommentPanelReady(region, observed == null ? "" : observed.url());
    }

    private boolean commentsPanelStillVisible(BrowserObservation observed, RegionInfo region) {
        if (observed == null) {
            return false;
        }
        if (collector.commentsReachedEnd(observed.tree())) {
            return true;
        }
        if (looksLikeStrongCommentPanelText(observed.tree())) {
            return true;
        }
        List<TreeLine> lines = parseTreeLines(observed.tree());
        if (hasDouyinCommentPanelTabs(lines) || hasVisibleCommentItemEvidence(lines)) {
            return true;
        }
        if (region == null) {
            return false;
        }
        return !collector.visibleComments(observed, region).isEmpty();
    }

    private boolean commentsEndReached(BrowserObservation observed, RegionInfo region, ExtractedComments extracted) {
        if (extracted != null && extracted.endReached()) {
            return true;
        }
        if (observed == null) {
            return false;
        }
        return collector.commentsReachedEnd(observed.tree(), region)
                || collector.commentsReachedEnd(observed.tree());
    }

    private boolean commentsEmpty(BrowserObservation observed, RegionInfo region, ExtractedComments extracted) {
        if (extracted != null && extracted.emptyReached()) {
            return true;
        }
        if (observed == null) {
            return false;
        }
        return collector.commentsEmpty(observed.tree(), region)
                || collector.commentsEmpty(observed.tree());
    }

    private boolean shouldUseDomFallback(NetworkCollectionState network, int scrolls, int stableEndMarker) {
        if (COMMENT_NETWORK_ONLY_COLLECTION) {
            return false;
        }
        if (network != null && network.primaryActive()) {
            return false;
        }
        if (stableEndMarker > 0) {
            return true;
        }
        if (scrolls < COMMENT_NETWORK_FALLBACK_GRACE_SCROLLS) {
            return false;
        }
        return scrolls == COMMENT_NETWORK_FALLBACK_GRACE_SCROLLS
                || scrolls % COMMENT_DOM_FALLBACK_INTERVAL == 0;
    }

    private CommentWindowHarvest harvestCommentWindow(
            LinkedHashMap<String, DouyinCommentItem> seen,
            RegionInfo region,
            BrowserObservation current,
            int declared,
            int domExtractCursor,
            NetworkCollectionState network,
            boolean useDomFallback
    ) {
        int before = seen.size();
        ExtractedComments networkResult = drainNetworkComments(current == null ? "" : current.url(), network);
        if (network != null) {
            network.record(networkResult);
        }
        for (DouyinCommentItem item : networkResult.comments()) {
            if (!item.text().isBlank()) {
                mergeComment(seen, item);
            }
        }
        boolean effectiveDomFallback = useDomFallback && !COMMENT_NETWORK_ONLY_COLLECTION;
        int domStartIndex = Math.max(0, domExtractCursor - COMMENT_DOM_INCREMENTAL_LOOKBACK);
        ExtractedComments extractedResult = effectiveDomFallback
                ? extractRegionComments(region, current == null ? "" : current.url(), domStartIndex)
                : ExtractedComments.empty();
        boolean domFallbackUsed = false;
        if (effectiveDomFallback
                && domStartIndex > 0
                && extractedResult.comments().isEmpty()
                && !extractedResult.endReached()
                && !extractedResult.emptyReached()) {
            extractedResult = extractRegionComments(region, current == null ? "" : current.url(), 0);
            domStartIndex = 0;
            domFallbackUsed = true;
        }
        for (DouyinCommentItem item : extractedResult.comments()) {
            if (!item.text().isBlank()) {
                mergeComment(seen, item);
            }
        }
        boolean networkPrimaryActive = network != null && network.primaryActive();
        List<DouyinCommentItem> visibleTreeComments = current == null || networkPrimaryActive || COMMENT_NETWORK_ONLY_COLLECTION
                ? List.of()
                : collector.visibleComments(current, region);
        if (!networkPrimaryActive && networkResult.comments().isEmpty() && extractedResult.comments().isEmpty()) {
            for (DouyinCommentItem item : visibleTreeComments) {
                if (!item.text().isBlank()) {
                    mergeComment(seen, item);
                }
            }
        }
        int domDeclared = current == null
                ? extractedResult.declaredCommentCount()
                : Math.max(extractedResult.declaredCommentCount(), collector.declaredCommentCount(current.tree(), region));
        int uiDeclared = COMMENT_NETWORK_ONLY_COLLECTION
                ? networkResult.declaredCommentCount()
                : Math.max(networkResult.declaredCommentCount(), domDeclared);
        int nextDeclared = uiDeclared > 0 ? Math.max(declared, uiDeclared) : declared;
        boolean endReached = !networkPrimaryActive
                && !COMMENT_NETWORK_ONLY_COLLECTION
                && commentsEndReached(current, region, extractedResult);
        boolean emptyReached = !networkPrimaryActive
                && !COMMENT_NETWORK_ONLY_COLLECTION
                && commentsEmpty(current, region, extractedResult);
        int reportedNextDomStartIndex = extractedResult.nextDomStartIndex();
        int nextDomStartIndex;
        if (domFallbackUsed) {
            nextDomStartIndex = reportedNextDomStartIndex >= 0
                    ? reportedNextDomStartIndex
                    : extractedResult.comments().size();
        } else if (reportedNextDomStartIndex >= 0) {
            nextDomStartIndex = Math.max(domExtractCursor, reportedNextDomStartIndex);
        } else {
            nextDomStartIndex = Math.max(domExtractCursor, domStartIndex + extractedResult.comments().size());
        }
        return new CommentWindowHarvest(
                before,
                seen.size(),
                Math.max(0, seen.size() - before),
                networkResult.comments().size() + extractedResult.comments().size(),
                visibleTreeComments.size(),
                nextDeclared,
                nextDomStartIndex,
                endReached,
                emptyReached,
                networkResult.networkPageCount(),
                networkResult.networkHasMoreFalse());
    }

    private long elapsedMs(long startedAtNanos) {
        return java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAtNanos);
    }

    private boolean videoChangedDuringCollection(String expectedVideoIdentity, BrowserObservation observed) {
        if (expectedVideoIdentity == null || expectedVideoIdentity.isBlank() || observed == null) {
            return false;
        }
        String current = videoIdentity(observed.url());
        return !current.isBlank() && !expectedVideoIdentity.equals(current);
    }

    private CommentCollectionResult collectionResult(
            LinkedHashMap<String, DouyinCommentItem> seen,
            int declared,
            boolean complete,
            String stopReason,
            int scrolls,
            int stableNoNew,
            int stableEndMarker,
            int lastExtractedCount,
            int lastVisibleCount,
            int lastNewItems,
            boolean lastCollectionAdvanced,
            int lastWindowBeforeCount,
            int lastWindowAfterCount,
            int effectiveScrolls,
            int advancedWindows,
            int forwardScrolls,
            int repeatedWindows,
            int totalNewItems,
            int staleScrolls,
            String lastWindowSignature,
            long lastLoopMs,
            ScrollRegionEvidence lastScrollEvidence,
            NetworkCollectionState network
    ) {
        ScrollRegionEvidence evidence = lastScrollEvidence == null ? ScrollRegionEvidence.none() : lastScrollEvidence;
        NetworkCollectionState networkState = network == null ? new NetworkCollectionState() : network;
        double coverage = declared > 0
                ? Math.min(1.0d, seen.size() / (double) declared)
                : 0.0d;
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("collectionCoverage", coverage);
        metadata.put("targetCoverage", COMMENT_COLLECTION_TARGET_COVERAGE);
        metadata.put("targetCoverageReached", declared > 0 && coverage >= COMMENT_COLLECTION_TARGET_COVERAGE);
        metadata.put("remainingDeclaredComments", declared > 0
                ? Math.max(0, declared - seen.size())
                : 0);
        metadata.put("collectedCount", seen.size());
        metadata.put("lastWindowBeforeCount", lastWindowBeforeCount);
        metadata.put("lastWindowAfterCount", lastWindowAfterCount);
        metadata.put("lastNewItems", lastNewItems);
        metadata.put("lastCollectionAdvanced", lastCollectionAdvanced);
        metadata.put("effectiveScrolls", effectiveScrolls);
        metadata.put("forwardScrolls", forwardScrolls);
        metadata.put("advancedWindows", advancedWindows);
        metadata.put("repeatedWindows", repeatedWindows);
        metadata.put("totalNewItems", totalNewItems);
        metadata.put("staleScrolls", staleScrolls);
        metadata.put("lastLoopMs", lastLoopMs);
        metadata.put("stableNoNewWindows", stableNoNew);
        metadata.put("stableEndMarkerWindows", stableEndMarker);
        metadata.put("lastExtractedCount", lastExtractedCount);
        metadata.put("lastVisibleCount", lastVisibleCount);
        metadata.put("lastScrollMoved", evidence.moved());
        metadata.put("lastScrollMode", evidence.mode());
        metadata.put("lastScrollReason", evidence.reason());
        metadata.put("lastScrollForwardProgress", evidence.forwardProgress());
        metadata.put("lastScrollNewVisibleItemCount", evidence.newVisibleItemCount());
        metadata.put("lastScrollRetainedVisibleItemCount", evidence.retainedVisibleItemCount());
        metadata.put("lastScrollTopBefore", evidence.before());
        metadata.put("lastScrollTopAfter", evidence.after());
        metadata.put("lastCommentWindowSignature", firstNonBlank(lastWindowSignature, evidence.afterWindowSignature()));
        metadata.put("lastBeforeFirstItemSignature", evidence.beforeFirstItemSignature());
        metadata.put("lastBeforeLastItemSignature", evidence.beforeLastItemSignature());
        metadata.put("lastAfterFirstItemSignature", evidence.afterFirstItemSignature());
        metadata.put("lastAfterLastItemSignature", evidence.afterLastItemSignature());
        long networkObservedComments = seen.values().stream()
                .filter(item -> "network_observed".equals(String.valueOf(item.metadata().get("source"))))
                .count();
        long extractedRegionComments = seen.values().stream()
                .filter(item -> "extract_region".equals(String.valueOf(item.metadata().get("source"))))
                .count();
        long a11yTreeComments = seen.values().stream()
                .filter(item -> "a11y_tree".equals(String.valueOf(item.metadata().get("source"))))
                .count();
        metadata.put("networkObservedComments", networkObservedComments);
        metadata.put("networkOnlyCollection", COMMENT_NETWORK_ONLY_COLLECTION);
        metadata.put("networkObservedPages", networkState.pageCount());
        metadata.put("networkTerminalPages", networkState.terminalPageCount());
        metadata.put("networkHasMoreFalseObserved", networkState.hasTerminalPage());
        metadata.put("networkLastCursor", networkState.lastCursor());
        metadata.put("networkLastNextCursor", networkState.lastNextCursor());
        metadata.put("extractedRegionComments", extractedRegionComments);
        metadata.put("a11yTreeComments", a11yTreeComments);
        boolean a11yOnlyCollection = a11yTreeComments > 0
                && extractedRegionComments == 0
                && networkObservedComments == 0;
        boolean declaredCountMismatch = declared > 0 && seen.size() < declared;
        boolean endOfListStop = stopReason != null && stopReason.startsWith("END_OF_LIST");
        boolean networkTerminalStop = stopReason != null && stopReason.startsWith("NETWORK_");
        boolean domTopLevelEndReached = endOfListStop
                && declaredCountMismatch
                && extractedRegionComments > 0
                && !a11yOnlyCollection;
        boolean networkTopLevelEndReached = networkTerminalStop
                && networkObservedComments > 0
                && networkState.hasTerminalPage();
        boolean effectiveComplete = complete || domTopLevelEndReached || networkTopLevelEndReached;
        String effectiveStopReason = domTopLevelEndReached && !complete
                ? "END_OF_LIST_TOP_LEVEL"
                : stopReason;
        boolean reportedComplete = effectiveComplete && !a11yOnlyCollection;
        String reportedStopReason = a11yOnlyCollection && "END_OF_LIST".equals(effectiveStopReason)
                ? "END_OF_LIST_A11Y_ONLY"
                : effectiveStopReason;
        metadata.put("primaryCollectionSource", networkObservedComments > 0
                ? "network_observed"
                : extractedRegionComments > 0 ? "extract_region" : "a11y_tree");
        metadata.put("domExtractionUnavailable", a11yOnlyCollection);
        metadata.put("fullCollectionExpected", reportedComplete
                && (networkTopLevelEndReached || declared > 0 && declared <= seen.size()));
        metadata.put("topLevelCollectionComplete", reportedComplete && (endOfListStop || networkTerminalStop));
        metadata.put("declaredTotalMayIncludeReplies", domTopLevelEndReached || networkTopLevelEndReached && declaredCountMismatch);
        metadata.put("partialCollection", a11yOnlyCollection
                || declaredCountMismatch && !domTopLevelEndReached && !networkTopLevelEndReached);
        metadata.put("declaredCountMismatch", declaredCountMismatch);
        if (domTopLevelEndReached || networkTopLevelEndReached && declaredCountMismatch) {
            metadata.put("declaredCountMismatchReason", "declared_count_may_include_collapsed_replies");
        }
        metadata.put("replyExpansionEnabled", false);
        metadata.put("replyExpansionMode", "disabled_quality_first");
        stopCommentNetworkCapture();
        return new CommentCollectionResult(
                new ArrayList<>(seen.values()),
                declared,
                reportedComplete,
                reportedStopReason,
                scrolls,
                metadata);
    }

    private void mergeComment(LinkedHashMap<String, DouyinCommentItem> seen, DouyinCommentItem item) {
        DouyinCommentItem existing = seen.get(item.commentKey());
        String existingKey = item.commentKey();
        if (existing == null) {
            existingKey = equivalentCommentKey(seen, item).orElse(item.commentKey());
            existing = seen.get(existingKey);
        }
        if (existing == null || commentQualityScore(item) > commentQualityScore(existing)) {
            if (!existingKey.equals(item.commentKey())) {
                seen.remove(existingKey);
            }
            seen.put(item.commentKey(), item);
        }
    }

    private Optional<String> equivalentCommentKey(LinkedHashMap<String, DouyinCommentItem> seen, DouyinCommentItem item) {
        String video = normalizeForEquivalence(item.videoKey());
        String author = normalizeForEquivalence(item.authorName());
        String text = normalizeForEquivalence(item.text());
        if (text.isBlank() || author.isBlank()) {
            return Optional.empty();
        }
        return seen.entrySet().stream()
                .filter(entry -> {
                    DouyinCommentItem existing = entry.getValue();
                    return normalizeForEquivalence(existing.videoKey()).equals(video)
                            && normalizeForEquivalence(existing.authorName()).equals(author)
                            && normalizeForEquivalence(existing.text()).equals(text);
                })
                .map(Map.Entry::getKey)
                .findFirst();
    }

    private String normalizeForEquivalence(String value) {
        return value == null ? "" : value.replaceAll("\\s+", "").trim();
    }

    private int commentQualityScore(DouyinCommentItem item) {
        int score = 0;
        if (item.authorProfileUrl() != null) score += 40;
        if (item.authorTarget() != null && item.authorTarget().hasPoint()) score += 30;
        if (!item.authorName().isBlank()) score += 20;
        if (item.metadata().containsKey("source") && "network_observed".equals(String.valueOf(item.metadata().get("source")))) {
            score += 25;
        }
        if (item.metadata().containsKey("source") && "extract_region".equals(String.valueOf(item.metadata().get("source")))) {
            score += 15;
        }
        if (item.metadata().containsKey("source") && "a11y_tree".equals(String.valueOf(item.metadata().get("source")))) {
            score += 5;
        }
        score += Math.min(40, item.text().length() / 4);
        return score;
    }

    @Override
    public BrowserObservation openAuthorProfile(DouyinCommentItem comment) {
        // 断连续跑:整步纳入 withReconnect。打开作者主页本身幂等(新开 tab,未确认的 tab 会被清理),
        // 会话层断连由外层等会话重现后整步重跑。注意:openAuthorProfileForEngagement 自身的重试只处理
        // PROFILE_OPEN_FAILED/PROFILE_TAB_NOT_CONTROLLED 等业务性失败;会话层断连统一交给本包装。
        return withReconnect("open_author_profile",
                () -> openAuthorProfileForEngagement(comment).observation());
    }

    private OpenedAuthorProfile openAuthorProfileForEngagement(DouyinCommentItem comment) {
        if (comment == null || !comment.hasAuthorOpenTarget()) {
            throw new DouyinBrowserException("AUTHOR_TARGET_MISSING", "匹配评论没有绑定作者入口");
        }
        List<String> attempts = new ArrayList<>();
        DouyinBrowserException lastFailure = null;
        for (int attempt = 0; attempt < AUTHOR_PROFILE_OPEN_MAX_ATTEMPTS; attempt++) {
            attempts.add("attempt-" + (attempt + 1) + ":new-tab:"
                    + (comment.authorProfileUrl() == null ? "dom-comment-link" : comment.authorProfileUrl()));
            JsonNode openAction = parse(browser.service_open_author_from_comment_main(
                    comment.text(),
                    comment.authorName(),
                    comment.authorProfileUrl() == null ? "" : comment.authorProfileUrl()));
            if (ok(openAction)) {
                Long tabId = actionPayloadLong(openAction, "tabId");
                int confirmAttempts = attempt == 0
                        ? AUTHOR_PROFILE_CONFIRM_ATTEMPTS
                        : AUTHOR_PROFILE_RETRY_CONFIRM_ATTEMPTS;
                long confirmWaitMs = attempt == 0
                        ? AUTHOR_PROFILE_CONFIRM_WAIT_MS
                        : AUTHOR_PROFILE_RETRY_CONFIRM_WAIT_MS;
                OpenedAuthorProfile opened = tabId == null
                        ? new OpenedAuthorProfile(waitForAuthorProfileAnyTab(comment, confirmAttempts, confirmWaitMs), null)
                        : waitForAuthorProfileTabOrActive(tabId, comment, confirmAttempts, confirmWaitMs);
                if (looksLikeDouyinUserProfile(opened.observation(), comment)) {
                    return opened;
                }
                cleanupUnconfirmedAuthorProfileTab(comment, opened.tabId(), opened.observation());
                lastFailure = new DouyinBrowserException("PROFILE_TAB_NOT_CONTROLLED",
                        profileTabNotControlledMessage(comment, attempts, tabId, opened));
            } else {
                lastFailure = new DouyinBrowserException("PROFILE_OPEN_FAILED",
                        "作者主页打开动作失败。code=" + openAction.path("code").asText("")
                                + ", message=" + openAction.path("message").asText("")
                                + ", author=" + comment.authorName()
                                + ", attempts=" + attempts);
            }
            if (!retryableProfileOpenFailure(lastFailure) || attempt >= AUTHOR_PROFILE_OPEN_MAX_ATTEMPTS - 1) {
                break;
            }
            waitMs(700L + attempt * 500L);
        }
        throw lastFailure == null
                ? new DouyinBrowserException("PROFILE_OPEN_FAILED", "作者主页打开失败: " + comment.authorName())
                : lastFailure;
    }

    private String profileTabNotControlledMessage(DouyinCommentItem comment,
                                                  List<String> attempts,
                                                  @Nullable Long requestedTabId,
                                                  OpenedAuthorProfile opened) {
        BrowserObservation observation = opened.observation();
        return "作者主页没有在新标签页中打开或未确认。tabId=" + requestedTabId
                + ", effectiveTabId=" + opened.tabId()
                + ", observedOk=" + observation.ok()
                + ", observedCode=" + observation.code()
                + ", observedMessage=" + observation.message()
                + ", observedUrl=" + observation.url()
                + ", observedTitle=" + observation.title()
                + ", author=" + comment.authorName()
                + ", expectedProfileUrl=" + comment.authorProfileUrl()
                + ", attempts=" + attempts
                + ", tree=" + treeExcerpt(observation.tree());
    }

    private boolean retryableProfileOpenFailure(DouyinBrowserException failure) {
        if (failure == null) {
            return false;
        }
        String code = failure.code();
        return "PROFILE_OPEN_FAILED".equals(code)
                || "PROFILE_TAB_NOT_CONTROLLED".equals(code)
                || "DEADLINE_EXCEEDED".equals(code)
                || "SESSION_DETACHED".equals(code);
    }

    private void cleanupUnconfirmedAuthorProfileTab(DouyinCommentItem comment,
                                                    @Nullable Long tabId,
                                                    @Nullable BrowserObservation observed) {
        try {
            if (tabId != null && tryOk(browser.service_close_tab(tabId))) {
                waitMs(350L);
                log.info("[douyin.lead] closed unconfirmed author profile tab: author={}, tabId={}",
                        comment.authorName(), tabId);
                return;
            }
            BrowserObservation active = observeActive("all");
            BrowserObservation main = observeMain("all");
            boolean activeLooksLikeTemporaryEngagementTab = isDouyinPage(active.url())
                    && !sameUrl(active.url(), main.url())
                    && (looksLikeDouyinUserProfile(active, comment)
                    || looksLikeDouyinDmPage(active)
                    || active.url().contains("/user/"));
            if (activeLooksLikeTemporaryEngagementTab && tryOk(browser.service_close_tab_active())) {
                waitMs(350L);
                log.info("[douyin.lead] closed active unconfirmed author profile tab: author={}, url={}",
                        comment.authorName(), active.url());
            } else if (observed != null) {
                log.warn("[douyin.lead] unconfirmed author profile tab was not closed: author={}, tabId={}, url={}, title={}",
                        comment.authorName(), tabId, observed.url(), observed.title());
            }
        } catch (RuntimeException e) {
            log.warn("[douyin.lead] failed to cleanup unconfirmed author profile tab: author={}, tabId={}, error={}",
                    comment.authorName(), tabId, e.getMessage());
        }
    }

    @Override
    public EngagementResult followAndDraft(DouyinCommentItem comment, String dmDraft, boolean sendDm) {
        // 断连续跑 + 幂等:整步纳入 withReconnect,会话层断连(关注/打开私信/输入/发送各子动作在断连窗口内
        // 抛 SESSION_DETACHED/DEADLINE_EXCEEDED/CANCELLED)等会话重现后重跑整步,而非整轮失败。
        // 关键:私信非幂等,重跑前后用实例级 clickedDmKeys 守卫"已点过发送的 dmKey",重跑只复核不重发(见
        // followAndDraftInternal 起始处的 dmKey 预置)。重跑安全的另一前提:关注/打开私信对同一作者重复操作
        // 无副作用(已关注则复用、私信浮层重开同一会话)。
        return withReconnect("engage_matched_comment_author",
                () -> followAndDraftInternal(comment, dmDraft, sendDm));
    }

    private EngagementResult followAndDraftInternal(DouyinCommentItem comment, String dmDraft, boolean sendDm) {
        boolean profileOpened = false;
        Long engagementTabId = null;
        try {
        OpenedAuthorProfile openedProfile = openAuthorProfileForEngagement(comment);
        engagementTabId = openedProfile.tabId();
        BrowserObservation profile = openedProfile.observation();
        profileOpened = true;
        if (!isDouyinPage(profile.url())) {
            return EngagementResult.failed(comment, "NOT_DOUYIN_PROFILE", "当前活动页不是抖音作者主页");
        }
        String resultProfileUrl = firstNonBlank(comment.authorProfileUrl(), profile.url());

        boolean alreadyFollowed = profileFollowConfirmed(profile);
        boolean followClicked = false;
        if (!alreadyFollowed) {
            followClicked = clickProfileAction(profile, "follow", List.of("关注", "回关", "Follow"), engagementTabId);
            if (!followClicked) {
                return new EngagementResult(comment, comment.authorName(), resultProfileUrl, true,
                        false, false, false, false, "failed", "FOLLOW_BUTTON_NOT_FOUND",
                        "未找到可点击的关注入口，已跳过私信以避免未关注直达私信。");
            }
            // 慢环境:关注后"已关注"态在慢机/后台 tab 渲染滞后,轮询次数/间隔放大,避免还没确认就误判。
            profile = waitForFollowConfirmation(engagementTabId, profile, slowAttempts(6), slowMs(700L));
        }
        boolean followConfirmed = alreadyFollowed || profileFollowConfirmed(profile);

        List<String> dmLabels = List.of("私信", "发私信", "Message", "发消息");
        if (!clickProfileAction(profile, "dm", dmLabels, engagementTabId)) {
            return new EngagementResult(comment, comment.authorName(), resultProfileUrl, true,
                    followConfirmed, false, false, false, "failed", "DM_BUTTON_NOT_FOUND",
                    "未找到私信入口：A11y 与 DOM 均未命中");
        }
        // 慢环境:私信浮层/页在慢网下确认更慢,等待页轮询次数/间隔放大,避免还没到发私信就 DM_PAGE_NOT_CONFIRMED。
        BrowserObservation dmPage = waitForDmPage(engagementTabId, slowAttempts(8), slowMs(650L));
        // 后台 tab 的 observe(a11y) 必然空(snapshot blank、连 url 都空)——Chrome 对非活动 tab 的硬限制。
        // 私信浮层(#imSaasContainerId)是作者主页上的同页浮层、URL 不变，靠 url/a11y 确认会误判并拦死。
        // 故：observe 可用(前台)时正常确认私信页；observe 空(后台)时不拦，坚持 engagementTabId 直接走
        // DOM 输入——type_dm_draft 以 #imSaasContainerId 浮层为锚点自行确认浮层是否打开并输入草稿；
        // 浮层真没打开时它返回 dm_panel_not_open → 最终归为 DM_INPUT_NOT_FOUND，而不是在这里就拦死。
        boolean dmObserveUsable = !isBlankObservation(dmPage) && isDouyinPage(dmPage.url());
        if (dmObserveUsable) {
            if (!looksLikeDouyinDmPage(dmPage)) {
                dmPage = retryDmDomActionAfterUnconfirmedPage(comment, profile, dmPage, dmLabels, engagementTabId);
            }
            if (!looksLikeDouyinDmPage(dmPage)) {
                log.warn("[douyin.lead] dm page not confirmed: url={}, title={}, signals={}, tree={}",
                        dmPage.url(), dmPage.title(), dmPageSignals(dmPage), treeExcerpt(dmPage.tree()));
                return new EngagementResult(comment, comment.authorName(), resultProfileUrl, true,
                        followConfirmed, false, false, false, "failed", "DM_PAGE_NOT_CONFIRMED",
                        "未确认进入目标用户私信页，拒绝输入草稿。signals=" + dmPageSignals(dmPage));
            }
        } else {
            log.info("[douyin.lead] dm observe blank/non-douyin (background); proceeding with DOM-anchored input on tab={}",
                    engagementTabId);
        }
        JsonNode dmDraftAction = errorNode("NOT_RUN", "type_dm_draft not run");
        boolean typedByDmPrimitive = false;
        boolean typedByGenericFallback = false;
        boolean sentByDmPrimitive = false;
        // 扩展自报"已点击发送"(clicked=true)——契约新增字段。注意:clicked 只代表点了发送按钮,
        // 不代表真发出(后台节流可能 no-op);真发出由后端强制复核确认。clicked 仅用于幂等去重。
        boolean clickedByDmPrimitive = false;
        // 私信幂等去重:为本条私信生成稳定 dmKey(作者标识 + 私信文本 的 hash),本次触达流程内
        // 维护"已点击发送过的集合"。重试循环 + send-only 兜底会对同一草稿带 sendDm=true 反复调用,
        // 慢网络下"已点但确认失败"若按 sent=true 才记入,会被重复发送 —— 故凡 clicked=true(扩展
        // 已点过发送按钮)即记入集合,已在集合中的 dmKey 不再二次点发送,确保"已点过绝不重复点发"。
        Set<String> sentDmKeys = new HashSet<>();
        String dmKey = sendDm ? dmKey(comment, dmDraft) : null;
        // 断连续跑幂等(跨重跑):若本步在上一次尝试中已点过发送(dmKey 记入实例级 clickedDmKeys),
        // 本次重跑直接把它预置进本地 sentDmKeys —— 下面的 DM 循环与 send-only 兜底都会据此走"跳过点发、
        // 只强制复核"路径,确保 withReconnect 重跑【绝不重复发私信】。
        if (dmKey != null && clickedDmKeys.contains(dmKey)) {
            sentDmKeys.add(dmKey);
            log.info("[douyin.lead] dmKey already clicked in a previous attempt (instance guard); "
                    + "this retry will re-check only, never resend: dmKey={}", dmKey);
        }
        // 私信浮层(#imSaasContainerId)点开后异步渲染，输入框可能要等几秒才 mount——重试
        // service_type_dm_draft 直到输入成功或超时(~12s)，避免一次找不到输入框就 DM_INPUT_NOT_FOUND。
        // 慢环境:发送前台化后单次 handler 内部最坏预算(切前台+渲染+输入+点发送)更长,外层输入
        // 重试窗口必须随慢系数放大,保证 >= 单次 handler 内部最坏预算,否则慢机会在 handler 还没
        // 跑完就过期、提前回退到 generic fallback。
        long dmTypeDeadlineMs = System.currentTimeMillis() + slowMs(12_000L);
        int dmAttempt = 0;
        while (true) {
            dmAttempt++;
            // 已点击发送过该 dmKey:本条私信已点过发送,绝不再带 sendDm 二次调用点发,直接收口。
            // (是否真发出由循环后强制复核判定,这里只防"重复点发"。)
            if (dmKey != null && sentDmKeys.contains(dmKey)) {
                clickedByDmPrimitive = true;
                typedByDmPrimitive = true;
                log.info("[douyin.lead] dm already clicked-send (dmKey hit); skip resend: dmKey={}", dmKey);
                break;
            }
            try {
                dmDraftAction = parse(engagementTabId == null
                        ? browser.service_type_dm_draft_active(dmDraft, sendDm)
                        : browser.service_type_dm_draft_tab(engagementTabId, dmDraft, sendDm));
                // 契约:扩展在"草稿已写但发送未确认"时不再 throw,而是 return ok:false 且 payload 带
                // {draftTyped, clicked, sent, reason}。故 typed 不能只看 ok——还要看 payload.draftTyped。
                typedByDmPrimitive = ok(dmDraftAction) || actionPayloadBoolean(dmDraftAction, "draftTyped");
                clickedByDmPrimitive = actionPayloadBoolean(dmDraftAction, "clicked");
                sentByDmPrimitive = actionPayloadBoolean(dmDraftAction, "sent");
            } catch (RuntimeException e) {
                log.warn("[douyin.lead] type_dm_draft attempt {} failed: {}", dmAttempt, e.getMessage());
                typedByDmPrimitive = false;
            }
            // 幂等:扩展已点击发送(clicked=true)即据信号回填 dmKey 并立刻终止循环,即使本次 ok=false
            // 或 sent=false(发送未确认)也不再重试点发,避免"已点但确认失败→再点一遍"造成重复发送。
            if (sendDm && clickedByDmPrimitive && dmKey != null) {
                sentDmKeys.add(dmKey);
                recordClickedDmKey(dmKey); // 断连续跑幂等:跨重跑也记住"已点过发送",重跑不重发
                log.info("[douyin.lead] dm clicked-send by primitive; record dmKey and stop: attempt={}, sent={}, dmKey={}",
                        dmAttempt, sentByDmPrimitive, dmKey);
                break;
            }
            if (typedByDmPrimitive) {
                log.info("[douyin.lead] dm draft typed by primitive: attempt={}, clicked={}, sent={}",
                        dmAttempt, clickedByDmPrimitive, sentByDmPrimitive);
                break;
            }
            String dmReason = dmDraftAction.path("reason").asText(
                    dmDraftAction.path("message").asText(dmDraftAction.path("code").asText("")));
            if (System.currentTimeMillis() >= dmTypeDeadlineMs) {
                log.info("[douyin.lead] dm draft not typed after {} attempts (~slow*12s); last reason={}; trying fallback",
                        dmAttempt, dmReason);
                break;
            }
            log.info("[douyin.lead] dm input not ready yet, waiting for panel render: attempt={}, reason={}",
                    dmAttempt, dmReason);
            waitMs(2_000L);
        }
        if (!typedByDmPrimitive) {
            BrowserObservation afterDraftAttempt = observeEngagementTab(engagementTabId, "all");
            if (dmDraftVisibleOnEngagementTab(engagementTabId, afterDraftAttempt, dmDraft)) {
                typedByDmPrimitive = true;
                dmPage = afterDraftAttempt;
                log.info("[douyin.lead] dm draft already visible after primitive failure; will continue to send-only path");
            } else {
                ClickPoint input = findDmInputPoint(dmPage);
                if (input == null) {
                    input = collector.inferDmInputPoint(dmPage)
                            .map(point -> new ClickPoint(point.x(), point.y()))
                            .orElse(null);
                }
                if (input == null) {
                    return new EngagementResult(comment, comment.authorName(), resultProfileUrl, true,
                            followConfirmed, true, false, false, "failed", "DM_INPUT_NOT_FOUND", "未找到私信输入框");
                }
                requireOk(engagementTabId == null
                                ? browser.service_type_active(dmDraft, new TypePayload.FocusTarget(input.x(), input.y()))
                                : browser.service_type_tab(engagementTabId, dmDraft, new TypePayload.FocusTarget(input.x(), input.y())),
                        "type_dm_draft");
                typedByGenericFallback = true;
            }
        }
        // 核心:绝不盲信扩展自报 sent。后台标签的合成点击/键入可能 no-op(消息没真正发出),扩展却
        // 仍回 sent:true。故凡扩展自报 sent=true,都【强制复核】——对 engagementTabId 走 *_tab observe
        // (不是 *_active:后台活动标签 != engagement 标签),在 DM 会话里查找刚发出的草稿文本气泡;
        // 复核到才认定真发出,否则置 sentConfirmed=false 并走 send-only 兜底。
        boolean sentConfirmed = false;
        if (DM_REVIEW_TRUST_PRIMITIVE_SENT) {
            // ===== flag=on:去后台误判路径 =====
            // (1) 扩展自报 sentByDmPrimitive=true 直接视为发出(扩展端经 slate/CDP 真发的第一手强信号),
            //     不再用"后台恒空"的复核去推翻它。
            if (sendDm && sentByDmPrimitive) {
                sentConfirmed = true;
                log.info("[douyin.lead] dm trusted as sent by extension primitive (sentByDmPrimitive=true); "
                        + "skip distrust/send-only: tabId={}, dmKey={}", engagementTabId, dmKey);
            }
            // (2) 复核降级为"扩展未自报 sent 时的二次确认",且区分三态:仅在确凿可观测却未见草稿(NOT_FOUND)
            //     时才走 send-only;后台/blank 的 INCONCLUSIVE 不据空判 false、不触发 send-only(防误判+防重发)。
            boolean dmKeyAlreadyClicked = dmKey != null && sentDmKeys.contains(dmKey);
            if (sendDm && !sentConfirmed) {
                DmSentReview review = reviewDmSent(engagementTabId, dmDraft, "secondary");
                if (review == DmSentReview.CONFIRMED) {
                    sentConfirmed = true;
                } else if (review == DmSentReview.NOT_FOUND && !dmKeyAlreadyClicked) {
                    // 确凿可观测却未见草稿,且该 dmKey 尚未点过发送 → 才允许 send-only 二次确认。
                    try {
                        JsonNode sendAction = parse(engagementTabId == null
                                ? browser.service_send_dm_active(dmDraft)
                                : browser.service_send_dm_tab(engagementTabId, dmDraft));
                        boolean sendClicked = ok(sendAction) || actionPayloadBoolean(sendAction, "clicked")
                                || actionPayloadBoolean(sendAction, "sent");
                        // 幂等:send-only 已点过发送即记入 dmKey,即便复核未过也绝不二次点发。
                        if (sendClicked && dmKey != null) {
                            sentDmKeys.add(dmKey);
                            recordClickedDmKey(dmKey);
                        }
                        if (sendClicked) {
                            // 二次确认同样用三态:CONFIRMED 才算发出;INCONCLUSIVE/NOT_FOUND 都不再继续点发。
                            sentConfirmed = reviewDmSent(engagementTabId, dmDraft, "send-only") == DmSentReview.CONFIRMED;
                        }
                        log.info("[douyin.lead] send-only dm fallback (NOT_FOUND path): clicked={}, sentConfirmed={}, dmKey={}",
                                sendClicked, sentConfirmed, dmKey);
                    } catch (RuntimeException e) {
                        log.warn("[douyin.lead] send-only dm primitive failed: {}", e.getMessage());
                    }
                } else {
                    // INCONCLUSIVE(后台 blank)或已点过发送:不据空判 false、绝不二次点发。
                    log.info("[douyin.lead] dm review inconclusive or dmKey already clicked; "
                            + "neither distrust nor resend: review={}, dmKeyAlreadyClicked={}, tabId={}, dmKey={}",
                            review, dmKeyAlreadyClicked, engagementTabId, dmKey);
                }
            }
        } else {
            // ===== flag=off(默认):逐字保留旧布尔路径,旧行为零变化 =====
            if (sendDm && sentByDmPrimitive) {
                sentConfirmed = confirmDmSent(engagementTabId, dmDraft, "primitive");
                if (!sentConfirmed) {
                    log.warn("[douyin.lead] extension self-reported sent but re-check found no draft bubble on engagement tab; "
                            + "distrust sent and fall back to send-only: tabId={}, dmKey={}", engagementTabId, dmKey);
                }
            }
            // 幂等:dmKey 已点击发送过则跳过 send-only 兜底,绝不二次点发。
            boolean dmKeyAlreadyClicked = dmKey != null && sentDmKeys.contains(dmKey);
            // send-only 兜底前台化:不再用"后台 observe 空恒不成立"的可见性 guard 拦死兜底——后台路径直接
            // 走 *_tab(扩展在点发送前会把该 tab 切到前台、注入 visibility override,使发送在前台 trusted
            // 生效),发完再强制复核。仅当扩展未确认发出 / 复核未通过 / 该 dmKey 尚未点过发送时才兜底。
            if (sendDm && !sentConfirmed && !dmKeyAlreadyClicked) {
                try {
                    JsonNode sendAction = parse(engagementTabId == null
                            ? browser.service_send_dm_active(dmDraft)
                            : browser.service_send_dm_tab(engagementTabId, dmDraft));
                    boolean sendClicked = ok(sendAction) || actionPayloadBoolean(sendAction, "clicked")
                            || actionPayloadBoolean(sendAction, "sent");
                    // 幂等:send-only 已点过发送即记入 dmKey,即便复核未过也不再二次点发。
                    if (sendClicked && dmKey != null) {
                        sentDmKeys.add(dmKey);
                        recordClickedDmKey(dmKey); // 断连续跑幂等:跨重跑也记住"已点过发送",重跑不重发
                    }
                    // 后端是发出与否的唯一权威:只要点了发送(前台化后 trusted),就强制复核,不管扩展自报
                    // sent 是否为 false(前台化后可能已真发出,扩展的 sent=false 可能是假阴性)。
                    if (sendClicked) {
                        sentConfirmed = confirmDmSent(engagementTabId, dmDraft, "send-only");
                    }
                    log.info("[douyin.lead] send-only dm fallback: clicked={}, sentConfirmed={}, dmKey={}",
                            sendClicked, sentConfirmed, dmKey);
                } catch (RuntimeException e) {
                    log.warn("[douyin.lead] send-only dm primitive failed: {}", e.getMessage());
                }
            } else if (sendDm && dmKeyAlreadyClicked && !sentConfirmed) {
                // 该 dmKey 已点过发送但本流程内尚未复核到——再做一次强制复核(气泡可能慢渲染),复核到则认定发出。
                sentConfirmed = confirmDmSent(engagementTabId, dmDraft, "dmKey-hit");
                log.info("[douyin.lead] dm already clicked-send (dmKey hit); skip resend, re-check only: sentConfirmed={}, dmKey={}",
                        sentConfirmed, dmKey);
            }
        }
        waitMs(500);
        BrowserObservation verify = observeEngagementTab(engagementTabId, "all");
        boolean draftTyped = sentConfirmed
                || typedByDmPrimitive
                || typedByGenericFallback
                || dmDraftVisibleOnEngagementTab(engagementTabId, verify, dmDraft);
        // sent 最终只认强制复核的结果(sentConfirmed),不再等于扩展自报 sent。
        boolean sent = sendDm && sentConfirmed;
        // 后台 observe 空时 followConfirmed / dmPage.tree 读不到——用"关注点击已发出"(followClicked)
        // 或"已关注"(alreadyFollowed)作为触达判据，避免把后台读不到误报成 FOLLOW_NOT_CONFIRMED。
        boolean engaged = followConfirmed || followClicked || alreadyFollowed || dmPage.tree().contains("私信");
        boolean succeeded = draftTyped && engaged && (!sendDm || sent);
        String failureCode = succeeded ? null
                : (!draftTyped ? "DM_INPUT_NOT_FOUND"
                : (!engaged ? "FOLLOW_NOT_CONFIRMED"
                : "DM_SEND_NOT_CONFIRMED"));
        // 成功时：不勾自动发送则明确提示"只输入草稿、未发出"；失败时给对应原因。
        String failureMessage = !succeeded
                ? (!draftTyped ? "未找到私信输入框 / 未能输入草稿"
                    : (!engaged ? "未能确认已关注目标作者"
                    : "未能确认私信已发送"))
                : (!sendDm ? "未勾选自动发送私信：已输入私信草稿、未发出" : null);
        return new EngagementResult(comment, comment.authorName(), resultProfileUrl, true, followConfirmed,
                true, draftTyped, sent, succeeded ? "succeeded" : "failed",
                failureCode,
                failureMessage);
        } finally {
            if (profileOpened || engagementTabId != null) {
                restoreVideoContextAfterEngagement(comment, engagementTabId);
            }
        }
    }

    private void restoreVideoContextAfterEngagement(DouyinCommentItem comment, @Nullable Long engagementTabId) {
        try {
            if (engagementTabId != null && tryOk(browser.service_close_tab(engagementTabId))) {
                waitMs(700L);
                BrowserObservation mainAfterClose = observeMain("all");
                if (looksLikeVideoOpenHard(mainAfterClose)) {
                    log.info("[douyin.lead] closed engagement tab by id and preserved current video: author={}, tabId={}",
                            comment.authorName(), engagementTabId);
                    return;
                }
            }
            BrowserObservation active = observeActive("all");
            BrowserObservation mainBeforeClose = observeMain("all");
            boolean activeLooksLikeEngagementTab = isDouyinPage(active.url())
                    && !sameUrl(active.url(), mainBeforeClose.url())
                    && (looksLikeDouyinUserProfile(active, comment) || looksLikeDouyinDmPage(active));
            if (activeLooksLikeEngagementTab && tryOk(browser.service_close_tab_active())) {
                waitMs(700L);
                BrowserObservation mainAfterClose = observeMain("all");
                if (looksLikeVideoOpenHard(mainAfterClose)) {
                    log.info("[douyin.lead] closed engagement tab and preserved current video: author={}",
                            comment.authorName());
                    return;
                }
            }
            if (activeLooksLikeEngagementTab && tryOk(browser.service_press_key_active("Control+W"))) {
                waitMs(700L);
                BrowserObservation mainAfterClose = observeMain("all");
                if (looksLikeVideoOpenHard(mainAfterClose)) {
                    log.info("[douyin.lead] closed engagement tab by keyboard and preserved current video: author={}",
                            comment.authorName());
                    return;
                }
            }
            BrowserObservation main = observeMain("all");
            if (!looksLikeVideoOpenHard(main)) {
                log.warn("[douyin.lead] main video context not confirmed after engagement: author={}, mainUrl={}, activeUrl={}",
                        comment.authorName(), main.url(), active.url());
            }
        } catch (RuntimeException e) {
            log.warn("[douyin.lead] failed to restore video context after engagement: author={}, error={}",
                    comment.authorName(), e.getMessage());
        }
    }

    private boolean sameUrl(String left, String right) {
        if (left == null || right == null) {
            return false;
        }
        return left.trim().equalsIgnoreCase(right.trim());
    }

    private BrowserObservation retryDmDomActionAfterUnconfirmedPage(
            DouyinCommentItem comment,
            BrowserObservation profile,
            BrowserObservation unconfirmed,
            List<String> dmLabels,
            @Nullable Long engagementTabId) {
        BrowserObservation active = observeEngagementTab(engagementTabId, "all");
        BrowserObservation retryBase = looksLikeDouyinUserProfile(active, comment) ? active
                : (looksLikeDouyinUserProfile(profile, comment) ? profile : unconfirmed);
        if (!looksLikeDouyinUserProfile(retryBase, comment)) {
            log.warn("[douyin.lead] skip dom dm retry because current page is not confirmed profile: url={}, title={}, signals={}",
                    active.url(), active.title(), dmPageSignals(active));
            return unconfirmed;
        }
        if (!clickProfileActionByDom(engagementTabId, dmLabels)) {
            log.warn("[douyin.lead] dom dm retry did not find profile action: url={}, title={}, tree={}",
                    retryBase.url(), retryBase.title(), treeExcerpt(retryBase.tree()));
            return unconfirmed;
        }
        log.info("[douyin.lead] retried profile action by dom after unconfirmed dm page: labels={}, previousUrl={}, currentUrl={}",
                dmLabels, unconfirmed == null ? "" : unconfirmed.url(), retryBase.url());
        return waitForDmPage(engagementTabId, slowAttempts(8), slowMs(650L));
    }

    private BrowserObservation waitForFollowConfirmation(@Nullable Long engagementTabId,
                                                         BrowserObservation fallback,
                                                         int attempts,
                                                         long waitMs) {
        BrowserObservation observed = fallback;
        for (int i = 0; i < Math.max(1, attempts); i++) {
            waitMs(waitMs);
            try {
                observed = observeEngagementTab(engagementTabId, "all");
                if (profileFollowConfirmed(observed)) {
                    return observed;
                }
            } catch (RuntimeException e) {
                log.warn("[douyin.lead] follow confirmation observe failed: {}", e.getMessage());
                return observed == null ? fallback : observed;
            }
        }
        return observed == null ? fallback : observed;
    }

    private boolean profileFollowConfirmed(@Nullable BrowserObservation profile) {
        if (profile == null || profile.tree() == null) {
            return false;
        }
        String tree = profile.tree();
        return tree.contains("已关注")
                || tree.contains("互相关注")
                || tree.toLowerCase(Locale.ROOT).contains("following");
    }

    /**
     * 私信幂等键:作者稳定标识 + 私信文本 的稳定 hash。作者标识优先用 authorProfileUrl(整条触达
     * 流程中不变),否则退到 authorName,再退到 commentKey(构造时必非空)。同一作者的不同私信文本
     * 不会被合并;同一草稿在重试/兜底中复算得到相同 key,据此判"是否已确认发出"。
     */
    private String dmKey(DouyinCommentItem comment, String dmDraft) {
        String authorIdentity = firstNonBlank(comment.authorProfileUrl(),
                firstNonBlank(comment.authorName(), comment.commentKey()));
        String raw = authorIdentity + "|" + (dmDraft == null ? "" : dmDraft.trim());
        return "dm-" + Integer.toHexString(raw.hashCode());
    }

    private boolean actionPayloadBoolean(JsonNode root, String fieldName) {
        if (root == null || fieldName == null || fieldName.isBlank()) {
            return false;
        }
        if (root.path(fieldName).isBoolean()) {
            return root.path(fieldName).asBoolean(false);
        }
        JsonNode results = root.path("results");
        if (results.isArray()) {
            for (JsonNode result : results) {
                JsonNode payload = result.path("payload");
                if (payload.path(fieldName).isBoolean()) {
                    return payload.path(fieldName).asBoolean(false);
                }
            }
        }
        return false;
    }

    @Nullable
    private Long actionPayloadLong(JsonNode root, String fieldName) {
        if (root == null || fieldName == null || fieldName.isBlank()) {
            return null;
        }
        JsonNode direct = root.path(fieldName);
        if (direct.isNumber()) {
            return direct.asLong();
        }
        if (direct.isTextual()) {
            try {
                return Long.parseLong(direct.asText());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        JsonNode results = root.path("results");
        if (results.isArray()) {
            for (JsonNode result : results) {
                JsonNode payload = result.path("payload");
                JsonNode value = payload.path(fieldName);
                if (value.isNumber()) {
                    return value.asLong();
                }
                if (value.isTextual()) {
                    try {
                        return Long.parseLong(value.asText());
                    } catch (NumberFormatException ignored) {
                        return null;
                    }
                }
            }
        }
        return null;
    }

    private boolean clickProfileAction(BrowserObservation profile, String actionName, List<String> labels,
                                       @Nullable Long engagementTabId) {
        // [出路③] DOM 优先 + 短轮询：open_author 已等到页面 readyState complete(加载完成)。这里
        // 在加载完成基础上再给 SPA 一点渲染余量，轮询匹配关注/私信按钮，最多约 15s——不再死等，
        // 找不到就如实失败、继续下一个(并由 clickProfileActionByDom 打印后台 tab 的 DOM 诊断：
        // readyState/按钮数/按钮文本，用于判断是后台根本不渲染、还是按钮在但选择器没匹配)。
        // a11y+CDP 坐标点击仅作兜底(仅活动 tab 有效)。是否真生效由外层 followConfirmed 判定。
        // 慢环境:关注/私信按钮在慢机/后台 tab 的 SPA 渲染更慢,短轮询总上限按慢系数放大(~24s),
        // 避免还没渲染出按钮就放弃导致 FOLLOW_BUTTON_NOT_FOUND/DM_BUTTON_NOT_FOUND。带总上限不死等。
        long retryDeadlineMs = System.currentTimeMillis() + slowMs(15_000L);
        int attempt = 0;
        while (true) {
            attempt++;
            if (clickProfileActionByDom(engagementTabId, labels)) {
                log.info("[douyin.lead] clicked profile action by dom: action={}, labels={}, attempt={}",
                        actionName, labels, attempt);
                return true;
            }
            ClickPoint a11y = findProfileActionPoint(profile, labels.toArray(String[]::new));
            if (a11y != null && clickEngagementPoint(engagementTabId, a11y)) {
                log.info("[douyin.lead] clicked profile action by a11y(fallback): action={}, labels={}, x={}, y={}, attempt={}",
                        actionName, labels, a11y.x(), a11y.y(), attempt);
                return true;
            }
            if (System.currentTimeMillis() >= retryDeadlineMs) {
                break;
            }
            log.info("[douyin.lead] profile action not present yet, waiting for SPA render: action={}, labels={}, attempt={}",
                    actionName, labels, attempt);
            waitMs(slowMs(2_000L));
        }
        if ("dm".equals(actionName) && openProfileMoreMenu(profile, engagementTabId)) {
            waitMs(350L);
            if (clickProfileActionByDom(engagementTabId, labels)) {
                log.info("[douyin.lead] clicked profile action from more menu by dom: action={}, labels={}",
                        actionName, labels);
                return true;
            }
            BrowserObservation afterMore = observeEngagementTab(engagementTabId, "all");
            ClickPoint menuPoint = findProfileActionPoint(afterMore, labels.toArray(String[]::new));
            if (menuPoint != null && clickEngagementPoint(engagementTabId, menuPoint)) {
                log.info("[douyin.lead] clicked profile action from more menu by a11y(fallback): action={}, labels={}, x={}, y={}",
                        actionName, labels, menuPoint.x(), menuPoint.y());
                return true;
            }
        }
        log.warn("[douyin.lead] profile action not found after {} attempts (~180s): action={}, labels={}, url={}, tree={}",
                attempt, actionName, labels, profile == null ? "" : profile.url(),
                profile == null ? "" : treeExcerpt(profile.tree()));
        return false;
    }

    private boolean clickEngagementPoint(@Nullable Long engagementTabId, ClickPoint point) {
        if (point == null) {
            return false;
        }
        return tryOk(engagementTabId == null
                ? browser.service_click_active(point.x(), point.y())
                : browser.service_click_tab(engagementTabId, point.x(), point.y()));
    }

    private boolean clickProfileActionByDom(@Nullable Long engagementTabId, List<String> labels) {
        String raw = engagementTabId == null
                ? browser.service_click_profile_action_active(labels)
                : browser.service_click_profile_action_tab(engagementTabId, labels);
        if (tryOk(raw)) {
            return true;
        }
        // 诊断：找不到按钮时打印扩展回传的后台 tab DOM 状态(reason 里带 diag={rs,vis,roots,btns,texts})，
        // 用于判断后台 tab 是否真的渲染了主内容区与关注/私信按钮。
        String snippet = raw == null ? "" : (raw.length() > 600 ? raw.substring(0, 600) : raw);
        log.info("[douyin.lead] dom click_profile miss: labels={}, raw={}", labels, snippet);
        return false;
    }

    private BrowserObservation observeEngagementTab(@Nullable Long engagementTabId, String filter) {
        return engagementTabId == null ? observeActive(filter) : observeTab(engagementTabId, filter);
    }

    private boolean openProfileMoreMenu(BrowserObservation profile, @Nullable Long engagementTabId) {
        if (profile == null) {
            return false;
        }
        ClickPoint more = findProfileMoreActionPoint(profile);
        if (more != null && clickEngagementPoint(engagementTabId, more)) {
            log.info("[douyin.lead] opened profile more menu by a11y: x={}, y={}", more.x(), more.y());
            return true;
        }
        return clickProfileActionByDom(engagementTabId, List.of("更多", "...", "…"));
    }

    @Nullable
    private ClickPoint findProfileMoreActionPoint(BrowserObservation obs) {
        if (obs == null || obs.tree() == null || obs.tree().isBlank()) {
            return null;
        }
        int viewportW = Math.max(1, obs.viewportWidth());
        int viewportH = Math.max(1, obs.viewportHeight());
        double minContentX = Math.max(180.0d, viewportW * 0.16d);
        return parseTreeLines(obs.tree()).stream()
                .filter(line -> {
                    String name = line.name().replaceAll("\\s+", "");
                    return name.equals("更多") || name.equals("...") || name.equals("…") || name.contains("更多操作");
                })
                .filter(line -> line.x() >= minContentX)
                .filter(line -> line.y() >= 70.0d && line.y() <= Math.max(360.0d, viewportH * 0.72d))
                .filter(line -> !line.role().equalsIgnoreCase("Searchbox"))
                .max(Comparator.comparingInt(line -> scoreProfileActionLine(line, "更多", "...", "…", "更多操作")))
                .map(TreeLine::point)
                .orElse(null);
    }

    private boolean looksLikeDouyinDmPage(BrowserObservation obs) {
        if (obs == null || !isDouyinPage(obs.url())) {
            return false;
        }
        String url = obs.url() == null ? "" : obs.url().toLowerCase(Locale.ROOT);
        String tree = obs.tree() == null ? "" : obs.tree();
        return url.contains("/im") || url.contains("/message") || url.contains("/conversation")
                || (tree.contains("私信") && (tree.contains("发送消息") || tree.contains("输入消息")))
                || (tree.contains("关闭会话") && (tree.contains("回关") || tree.contains("发送一条文字消息")
                || tree.contains("对方回复或关注你之前")));
    }

    private String dmPageSignals(BrowserObservation obs) {
        if (obs == null) {
            return "obs=null";
        }
        String url = obs.url() == null ? "" : obs.url().toLowerCase(Locale.ROOT);
        String tree = obs.tree() == null ? "" : obs.tree();
        return "isDouyin=" + isDouyinPage(obs.url())
                + ", urlDm=" + (url.contains("/im") || url.contains("/message") || url.contains("/conversation"))
                + ", hasPrivateMessage=" + tree.contains("私信")
                + ", hasSendMessage=" + (tree.contains("发送消息") || tree.contains("输入消息"))
                + ", hasConversationClose=" + tree.contains("关闭会话")
                + ", hasReplyOrFollowGate=" + tree.contains("对方回复或关注你之前");
    }

    private BrowserObservation waitForDmPage(@Nullable Long engagementTabId, int attempts, long waitMs) {
        BrowserObservation observed = observeEngagementTab(engagementTabId, "all");
        for (int i = 0; i < Math.max(1, attempts); i++) {
            if (looksLikeDouyinDmPage(observed)) {
                return observed;
            }
            waitMs(waitMs);
            observed = observeEngagementTab(engagementTabId, "all");
        }
        return observed;
    }

    @Nullable
    private ClickPoint findDmInputPoint(BrowserObservation obs) {
        List<TreeLine> lines = parseTreeLines(obs.tree());
        double minY = Math.max(140.0d, obs.viewportHeight() * 0.35d);
        return lines.stream()
                .filter(line -> line.y() >= minY)
                .filter(line -> !line.role().equalsIgnoreCase("Searchbox"))
                .filter(line -> {
                    String name = line.name();
                    return line.role().equalsIgnoreCase("Textbox")
                            || name.contains("输入消息")
                            || name.contains("发送消息");
                })
                .max(Comparator.comparingInt(line -> scoreDmInputLine(line, obs)))
                .map(TreeLine::point)
                .orElse(null);
    }

    private int scoreDmInputLine(TreeLine line, BrowserObservation obs) {
        int score = 0;
        if (line.role().equalsIgnoreCase("Textbox")) score += 80;
        if (line.name().contains("输入消息") || line.name().contains("发送消息")) score += 60;
        if (line.y() >= obs.viewportHeight() * 0.65d) score += 30;
        if (line.name().contains("搜索")) score -= 200;
        return score;
    }

    private boolean dmDraftVisibleInDmInputArea(BrowserObservation obs, String dmDraft) {
        if (dmDraft == null || dmDraft.isBlank()) {
            return false;
        }
        double minY = Math.max(140.0d, obs.viewportHeight() * 0.35d);
        return parseTreeLines(obs.tree()).stream()
                .filter(line -> line.y() >= minY)
                .filter(line -> !line.role().equalsIgnoreCase("Searchbox"))
                .anyMatch(line -> line.name().contains(dmDraft));
    }

    private boolean dmDraftVisibleInDmDom(BrowserObservation obs, String dmDraft) {
        return dmDraftVisibleInDmDomTab(null, obs, dmDraft);
    }

    /**
     * tab 感知的 DM DOM 草稿气泡检查:engagementTabId 非空时走 *_tab(后台活动标签 != engagement 标签,
     * 必须显式指定 engagement 标签才能读到刚发出的草稿气泡),为空时退回 *_active。后台 observe 空导致
     * obs.url() 读不到时仍尝试 region 提取(region 走扩展端 DOM,不依赖 a11y snapshot),不被空 url 拦死。
     */
    private boolean dmDraftVisibleInDmDomTab(@Nullable Long engagementTabId, BrowserObservation obs, String dmDraft) {
        if (obs == null || dmDraft == null || dmDraft.isBlank()) {
            return false;
        }
        RegionInfo dmRegion = dmRegion(obs);
        boolean registered = engagementTabId == null
                ? tryOk(browser.service_register_region_active(
                        dmRegion.regionKey(), dmRegion.x(), dmRegion.y(), dmRegion.width(), dmRegion.height(), dmRegion.source()))
                : tryOk(browser.service_register_region_tab(engagementTabId,
                        dmRegion.regionKey(), dmRegion.x(), dmRegion.y(), dmRegion.width(), dmRegion.height(), dmRegion.source()));
        if (!registered) {
            return false;
        }
        try {
            JsonNode root = parse(engagementTabId == null
                    ? browser.service_extract_region_active(dmRegion.regionKey(), 20)
                    : browser.service_extract_region_tab(engagementTabId, dmRegion.regionKey(), 20));
            if (!root.path("ok").asBoolean(false)) {
                return false;
            }
            for (JsonNode item : root.path("results").path(0).path("payload").path("items")) {
                if (item.path("text").asText("").contains(dmDraft)) {
                    return true;
                }
            }
            return false;
        } catch (Exception ignored) {
            return false;
        }
    }

    /**
     * 在 engagement 标签上判断草稿文本是否可见(输入框区域 a11y 命中 或 DM DOM region 命中)。a11y 走传入的
     * obs(已是 *_tab observe 结果),DOM 走 tab 感知的 region 提取。前台时两路都可能命中;后台 a11y 空但
     * region(DOM)仍可能命中。用于"草稿已写入"的判定。
     */
    private boolean dmDraftVisibleOnEngagementTab(@Nullable Long engagementTabId, BrowserObservation obs, String dmDraft) {
        if (obs == null || dmDraft == null || dmDraft.isBlank()) {
            return false;
        }
        return dmDraftVisibleInDmInputArea(obs, dmDraft)
                || dmDraftVisibleInDmDomTab(engagementTabId, obs, dmDraft);
    }

    /**
     * 强制复核"私信是否真发出":绝不盲信扩展自报 sent。对 engagementTabId 走 *_tab observe + tab 感知的
     * DM DOM region 提取,在会话里查找刚发出的草稿文本气泡(已发出的消息会作为气泡留在会话流里);复核到
     * 才认定真发出。慢机/慢网下气泡渲染滞后,带慢系数的短轮询(总上限),命中即提前返回,不死等。
     */
    private boolean confirmDmSent(@Nullable Long engagementTabId, String dmDraft, String source) {
        if (dmDraft == null || dmDraft.isBlank()) {
            return false;
        }
        int attempts = slowAttempts(4);
        long waitMs = slowMs(700L);
        for (int i = 0; i < Math.max(1, attempts); i++) {
            try {
                BrowserObservation obs = observeEngagementTab(engagementTabId, "all");
                if (dmDraftVisibleInDmDomTab(engagementTabId, obs, dmDraft)
                        || dmDraftVisibleInDmInputArea(obs, dmDraft)) {
                    log.info("[douyin.lead] dm send confirmed by re-check on engagement tab: source={}, attempt={}, tabId={}",
                            source, i + 1, engagementTabId);
                    return true;
                }
            } catch (RuntimeException e) {
                log.warn("[douyin.lead] dm send re-check observe failed: source={}, attempt={}, error={}",
                        source, i + 1, e.getMessage());
            }
            waitMs(waitMs);
        }
        log.warn("[douyin.lead] dm send NOT confirmed after re-check on engagement tab: source={}, attempts={}, tabId={}",
                source, attempts, engagementTabId);
        return false;
    }

    /**
     * 私信复核三态结果(仅 {@link #DM_REVIEW_TRUST_PRIMITIVE_SENT}=on 路径使用):
     * <ul>
     *   <li>{@code CONFIRMED} —— 在会话流/输入区命中草稿文本,确凿已发出;</li>
     *   <li>{@code NOT_FOUND} —— 复核窗口内至少有一次【非空观测】(前台 obs 可用)却始终未命中草稿,
     *       才算"确凿未见";此时可保守走 send-only 二次确认;</li>
     *   <li>{@code INCONCLUSIVE} —— 整个复核窗口的观测全部为 blank(后台/最小化 tab,a11y 空树 +
     *       DOM region 几何坍缩),信息不足,【不得据此判 false、不得触发 send-only】。</li>
     * </ul>
     */
    private enum DmSentReview {
        CONFIRMED,
        NOT_FOUND,
        INCONCLUSIVE
    }

    /**
     * 三态复核版(P0 去后台误判):与 {@link #confirmDmSent} 同源逻辑,但把"全程观测为空(后台)"
     * 与"确凿未见草稿(前台可观测却没命中)"区分开。核心:只有在复核窗口里【至少出现过一次非空观测】
     * 后仍未命中,才返回 {@link DmSentReview#NOT_FOUND};若每次观测都 blank(后台 tab 的 a11y 必空 +
     * region 几何坍缩 items=0),返回 {@link DmSentReview#INCONCLUSIVE},交由调用方按"信任扩展自报/
     * 不据空判 false"处理,绝不据此触发 send-only 二次点发。
     *
     * <p>仅 {@link #DM_REVIEW_TRUST_PRIMITIVE_SENT}=on 时调用;off 时本方法不参与,旧布尔路径不变。
     */
    private DmSentReview reviewDmSent(@Nullable Long engagementTabId, String dmDraft, String source) {
        if (dmDraft == null || dmDraft.isBlank()) {
            // 没有可比对的草稿文本:无法做出任何判断,视作信息不足(而非"未见")。
            return DmSentReview.INCONCLUSIVE;
        }
        int attempts = slowAttempts(4);
        long waitMs = slowMs(700L);
        boolean sawNonBlankObservation = false;
        for (int i = 0; i < Math.max(1, attempts); i++) {
            try {
                BrowserObservation obs = observeEngagementTab(engagementTabId, "all");
                boolean blank = isBlankObservation(obs);
                if (!blank) {
                    sawNonBlankObservation = true;
                }
                if (dmDraftVisibleInDmDomTab(engagementTabId, obs, dmDraft)
                        || dmDraftVisibleInDmInputArea(obs, dmDraft)) {
                    log.info("[douyin.lead] dm send confirmed by re-check on engagement tab: source={}, attempt={}, tabId={}",
                            source, i + 1, engagementTabId);
                    return DmSentReview.CONFIRMED;
                }
            } catch (RuntimeException e) {
                log.warn("[douyin.lead] dm send re-check observe failed: source={}, attempt={}, error={}",
                        source, i + 1, e.getMessage());
            }
            waitMs(waitMs);
        }
        if (!sawNonBlankObservation) {
            // 后台 tab:整窗观测全空,a11y/region 在后台恒空 —— 信息不足,不据此判 false。
            log.info("[douyin.lead] dm send re-check INCONCLUSIVE (all observations blank/background): "
                    + "source={}, attempts={}, tabId={}", source, attempts, engagementTabId);
            return DmSentReview.INCONCLUSIVE;
        }
        log.warn("[douyin.lead] dm send NOT confirmed after re-check on engagement tab (observable but draft not seen): "
                + "source={}, attempts={}, tabId={}", source, attempts, engagementTabId);
        return DmSentReview.NOT_FOUND;
    }

    private RegionInfo dmRegion(BrowserObservation obs) {
        int viewportW = Math.max(1, obs.viewportWidth());
        int viewportH = Math.max(1, obs.viewportHeight());
        double x = Math.max(0.0d, viewportW * 0.24d);
        double y = Math.max(0.0d, viewportH * 0.18d);
        double width = viewportW - x;
        double height = viewportH - y;
        return new RegionInfo(
                "douyin.dm",
                x,
                y,
                width,
                height,
                x + width * 0.52d,
                Math.max(y + 80.0d, viewportH - 86.0d),
                "dm-active-page");
    }

    private BrowserObservation observeMain(String filter) {
        return parseObservation(browser.service_observe_main(filter));
    }

    private ExtractedComments extractRegionComments(RegionInfo region, String videoKey) {
        return extractRegionComments(region, videoKey, 0);
    }

    private ExtractedComments extractRegionComments(RegionInfo region, String videoKey, int startIndex) {
        try {
            JsonNode root = parse(browser.service_extract_region_main(
                    region.regionKey(),
                    COMMENT_EXTRACT_REGION_MAX_ITEMS,
                    Math.max(0, startIndex)));
            if (!root.path("ok").asBoolean(false)) {
                return ExtractedComments.empty();
            }
            ExtractedComments extracted = new ExtractedComments(
                    collector.commentsFromExtractedRegion(root, videoKey),
                    collector.declaredCommentCountFromExtractedRegion(root),
                    collector.commentsReachedEndFromExtractedRegion(root),
                    collector.commentsEmptyFromExtractedRegion(root),
                    nextDomStartIndexFromExtractedRegion(root));
            return extracted;
        } catch (Exception e) {
            log.warn("[douyin.comments.dom] extract_region failed regionKey={} error={}",
                    region.regionKey(), e.toString());
            return ExtractedComments.empty();
        }
    }

    private int nextDomStartIndexFromExtractedRegion(JsonNode extractRoot) {
        JsonNode diagnostics = extractRoot.path("results").path(0).path("payload").path("diagnostics");
        if (!diagnostics.isObject()) {
            return -1;
        }
        return diagnostics.path("nextStartIndex").asInt(-1);
    }

    private void startCommentNetworkCapture() {
        try {
            String result = browser.service_douyin_comment_network_main(
                    "start",
                    COMMENT_NETWORK_CAPTURE_MAX_PAGES,
                    COMMENT_NETWORK_CAPTURE_MAX_BODY_BYTES,
                    COMMENT_NETWORK_CAPTURE_TTL_MS);
            logCommentNetworkAction("start", result);
            commentNetworkCaptureActive = true;
        } catch (Exception e) {
            commentNetworkCaptureActive = false;
            log.warn("[douyin.comments.network] start failed: {}", e.toString());
            // Network observation is an enhancement path. DOM/region extraction remains the fallback.
        }
    }

    private void stopCommentNetworkCapture() {
        try {
            String result = browser.service_douyin_comment_network_main("stop", null, null, null);
            logCommentNetworkAction("stop", result);
        } catch (Exception e) {
            log.warn("[douyin.comments.network] stop failed: {}", e.toString());
            // Best-effort cleanup only.
        } finally {
            commentNetworkCaptureActive = false;
        }
    }

    private ExtractedComments drainNetworkComments(String videoKey, @Nullable NetworkCollectionState network) {
        try {
            String result = browser.service_douyin_comment_network_main("drain", null, null, null);
            logCommentNetworkAction("drain", result);
            JsonNode root = parse(result);
            if (!root.path("ok").asBoolean(false)) {
                if (network != null) {
                    network.recordInflight(0);
                }
                return ExtractedComments.empty();
            }
            JsonNode payload = root.path("results").path(0).path("payload");
            // pendingResponses=已收到响应头但响应体未读完(续拉在途);inflight=正在读取响应体的 promise。
            // 任一 >0 即代表续拉 XHR "在途未回",采集判到底前应再等一个 settle 而非提前收口。
            if (network != null) {
                network.recordInflight(
                        Math.max(0, payload.path("pendingResponses").asInt(0))
                                + Math.max(0, payload.path("inflight").asInt(0)));
            }
            JsonNode pages = payload.path("pages");
            if (!pages.isArray()) {
                return ExtractedComments.empty();
            }
            LinkedHashMap<String, DouyinCommentItem> comments = new LinkedHashMap<>();
            int declared = 0;
            int pageCount = 0;
            boolean terminalPage = false;
            String lastCursor = "";
            String lastNextCursor = "";
            List<String> pageKeys = new ArrayList<>();
            for (JsonNode page : pages) {
                DouyinCommentCollector.NetworkCommentPage parsed =
                        collector.commentsFromNetworkPage(page, videoKey);
                if (networkPageUsable(parsed)) {
                    pageCount++;
                    pageKeys.add(networkPageKey(page, parsed));
                    lastCursor = firstNonBlank(parsed.cursor(), lastCursor);
                    lastNextCursor = firstNonBlank(parsed.nextCursor(), lastNextCursor);
                    if (parsed.hasMoreKnown() && !parsed.hasMore()) {
                        terminalPage = true;
                    }
                }
                declared = Math.max(declared, parsed.declaredCommentCount());
                for (DouyinCommentItem item : parsed.comments()) {
                    if (!item.text().isBlank()) {
                        comments.putIfAbsent(item.commentKey(), item);
                    }
                }
            }
            return ExtractedComments.network(
                    new ArrayList<>(comments.values()),
                    declared,
                    pageCount,
                    terminalPage,
                    pageKeys,
                    lastCursor,
                    lastNextCursor);
        } catch (Exception e) {
            log.warn("[douyin.comments.network] drain failed: {}", e.toString());
            if (network != null) {
                network.recordInflight(0);
            }
            return ExtractedComments.empty();
        }
    }

    private void logCommentNetworkAction(String op, String rawJson) {
        // Intentionally quiet in production. The action result is parsed by the
        // caller when it affects behavior; verbose payload logging is only useful
        // during network capture debugging and can leak large request details.
    }

    private boolean networkPageUsable(DouyinCommentCollector.NetworkCommentPage page) {
        if (page == null) {
            return false;
        }
        return !page.comments().isEmpty()
                || page.declaredCommentCount() > 0
                || !page.cursor().isBlank()
                || !page.nextCursor().isBlank()
                || page.hasMoreKnown()
                || page.sourceUrl().toLowerCase(Locale.ROOT).contains("comment");
    }

    private String networkPageKey(JsonNode page, DouyinCommentCollector.NetworkCommentPage parsed) {
        String requestId = page == null ? "" : page.path("requestId").asText("");
        if (!requestId.isBlank()) {
            return "request:" + requestId;
        }
        if (parsed == null) {
            return "";
        }
        return "url:" + parsed.sourceUrl()
                + "|cursor:" + parsed.cursor()
                + "|next:" + parsed.nextCursor();
    }

    private void ensureCommentsPanelReady(RegionInfo region, String phase) {
        ensureCommentsPanelReady(region, observeMain("all"), phase);
    }

    private void ensureCommentsPanelReady(RegionInfo region, BrowserObservation observed, String phase) {
        if (commentsPanelReady(observed)
                || observed != null && collector.commentsReachedEnd(observed.tree())
                || extractedCommentPanelReady(region, observed == null ? "" : observed.url())) {
            return;
        }
        ClickPoint point = commentRegionScrollPoint(region, 0);
        parkMouseInCommentRegion(point, "recover_comments_region_park_" + phase);
        focusCommentRegion(point, "recover_comments_region_focus_" + phase);
        waitMs(300L);
        BrowserObservation recovered = observeMain("all");
        if (commentsPanelReady(recovered)
                || recovered != null && collector.commentsReachedEnd(recovered.tree())
                || extractedCommentPanelReady(region, recovered == null ? "" : recovered.url())) {
            return;
        }
        BrowserObservation latest = observeMain("all");
        throw new DouyinBrowserException("COMMENTS_PANEL_LOST",
                "评论区未确认打开，采集阶段拒绝重新打开/切换评论区。phase=" + phase
                        + ", url=" + latest.url()
                        + ", title=" + latest.title()
                        + ", signals=" + commentTriggerSignals(latest)
                        + ", tree=" + treeExcerpt(latest.tree()));
    }

    private RegionInfo withCommentPanelSafePoint(BrowserObservation obs, RegionInfo region) {
        int viewportW = Math.max(1, obs.viewportWidth());
        int viewportH = Math.max(1, obs.viewportHeight());
        if (region.source() != null && region.source().contains("comment-list")) {
            double safeX = clampDouble(
                    fixedCommentPanelWheelX(region),
                    region.x() + Math.min(72.0d, region.width() * 0.18d),
                    region.x() + region.width() - Math.min(120.0d, region.width() * 0.22d));
            double safeY = clampDouble(
                    fixedCommentPanelWheelY(region),
                    region.y() + Math.min(96.0d, region.height() * 0.20d),
                    region.y() + region.height() - Math.min(80.0d, region.height() * 0.16d));
            return new RegionInfo(
                    region.regionKey(),
                    region.x(),
                    region.y(),
                    region.width(),
                    region.height(),
                    safeX,
                    safeY,
                    region.source() + "+comment-list-safe-point");
        }
        double evidenceMinX = viewportW >= 1500 ? viewportW * 0.58d : viewportW * 0.45d;
        double interactionMinX = Math.max(Math.max(region.x(), evidenceMinX), region.x() + Math.min(140.0d, region.width() * 0.22d));
        double minSafeX = Math.min(viewportW - 80.0d, interactionMinX + 100.0d);
        double maxSafeX = Math.max(minSafeX, Math.min(viewportW - 96.0d, region.x() + region.width() - 48.0d));
        double safeX = clampDouble(fixedCommentPanelWheelX(region), minSafeX, maxSafeX);
        double safeY = clampDouble(fixedCommentPanelWheelY(region), Math.max(180.0d, viewportH * 0.28d), viewportH - 72.0d);
        return new RegionInfo(
                region.regionKey(),
                region.x(),
                region.y(),
                region.width(),
                region.height(),
                safeX,
                safeY,
                region.source() + "+safe-point");
    }

    private ScrollRegionEvidence scrollCommentRegion(RegionInfo region, int scrollIndex, boolean networkOnly) {
        if (networkOnly) {
            return scrollCommentRegionNetworkTrigger(region, scrollIndex);
        }
        ClickPoint point = commentRegionScrollPoint(region, scrollIndex);
        if (scrollIndex == 0 || scrollIndex % 25 == 0) {
            parkMouseInCommentRegion(point, "park_comments_region_before_scroll");
        }
        double amount = COMMENT_SCROLL_STEP_PX;
        JsonNode result;
        try {
            result = requireOkNode(browser.service_scroll_region_main(region.regionKey(), "down", amount,
                            COMMENT_WHEEL_SCROLL_DEADLINE_MS),
                    "scroll_comments_region");
        } catch (DouyinBrowserException e) {
            if (!"DEADLINE_EXCEEDED".equals(e.code())) {
                throw e;
            }
            parkMouseInCommentRegion(point, "park_comments_region_after_scroll_timeout");
            return ScrollRegionEvidence.regionOnlyFallback("scroll_region_deadline:no_keyboard_or_page_scroll_fallback");
        }
        ScrollRegionEvidence evidence = ScrollRegionEvidence.from(result);
        if (evidence.panelLostSignal()) {
            parkMouseInCommentRegion(point, "park_comments_region_after_panel_lost_signal");
        }
        if (!evidence.moved() && "scroll_container_not_found".equals(evidence.reason())) {
            parkMouseInCommentRegion(point, "park_comments_region_before_region_only_fallback");
            return ScrollRegionEvidence.regionOnlyFallback("scroll_container_not_found:no_keyboard_or_page_scroll_fallback");
        }
        return evidence;
    }

    private ScrollRegionEvidence scrollCommentRegionNetworkTrigger(RegionInfo region, int scrollIndex) {
        // 后台静默优先:用 DOM 区域滚动(scroll_region 的抖音评论专用容器滚动:
        // scrollBy/scrollTop += / dispatch WheelEvent,executeScript 注入、不依赖窗口
        // 活动tab/焦点),把评论虚拟列表往下滚以触发续拉 XHR。原先用 service_scroll_main
        // (CDP 鼠标滚轮)在后台 no-op、前台坐标也常落空,导致只抓到第一页(~20 条)。
        // DOM 区域滚动失败时再回退坐标滚轮(前台 CDP / 后台 scroll.ts DOM)。
        try {
            JsonNode res = requireOkNode(browser.service_scroll_region_main(
                            region.regionKey(),
                            "down",
                            COMMENT_NETWORK_SCROLL_STEP_PX,
                            COMMENT_NETWORK_WHEEL_SCROLL_DEADLINE_MS),
                    "scroll_comments_network_trigger");
            if (scrollIndex < 3 || scrollIndex % 20 == 0) {
                JsonNode payload = res.path("results").path(0).path("payload");
                log.info("[douyin.comments.network] region scroll #{}: mode={}, moved={}, scrollTop={}->{}, e2e={}",
                        scrollIndex,
                        payload.path("mode").asText(""),
                        payload.path("moved").asBoolean(false),
                        payload.path("scrollTopBefore").asText(payload.path("scrollTop").asText("")),
                        payload.path("scrollTopAfter").asText(""),
                        payload.path("containerE2E").asText(""));
            }
            return ScrollRegionEvidence.networkTrigger("network_only_dom_region_scroll");
        } catch (DouyinBrowserException e) {
            ClickPoint point = commentRegionScrollPoint(region, scrollIndex);
            try {
                requireOk(browser.service_scroll_main(
                                "down",
                                COMMENT_NETWORK_SCROLL_STEP_PX,
                                point.x(),
                                point.y(),
                                COMMENT_NETWORK_WHEEL_SCROLL_DEADLINE_MS),
                        "scroll_comments_network_trigger_fallback");
                return ScrollRegionEvidence.networkTrigger("network_only_point_wheel_fallback");
            } catch (DouyinBrowserException e2) {
                log.warn("[douyin.comments.network] scroll trigger failed (region={}, point={}); keep collector alive",
                        e.code(), e2.code());
                return ScrollRegionEvidence.wheelFallback("network_direct_wheel_" + e2.code().toLowerCase(Locale.ROOT));
            }
        }
    }

    private ClickPoint commentRegionScrollPoint(RegionInfo region, int scrollIndex) {
        double y = clampDouble(
                fixedCommentPanelWheelY(region),
                region.y() + Math.min(160.0d, region.height() * 0.28d),
                region.y() + region.height() - 72.0d);
        double rightPadding = region.source() != null && region.source().contains("comment-list")
                ? Math.min(140.0d, region.width() * 0.25d)
                : 64.0d;
        double x = clampDouble(
                fixedCommentPanelWheelX(region),
                region.x() + Math.min(88.0d, region.width() * 0.24d),
                region.x() + region.width() - rightPadding);
        return new ClickPoint(x, y);
    }

    private double fixedCommentPanelWheelX(RegionInfo region) {
        return region.x() + region.width() * 0.62d;
    }

    private double fixedCommentPanelWheelY(RegionInfo region) {
        return region.y() + region.height() * 0.42d;
    }

    private void parkMouseInCommentRegion(RegionInfo region, String step) {
        parkMouseInCommentRegion(new ClickPoint(region.safeX(), region.safeY()), step);
    }

    private void parkMouseInCommentRegion(ClickPoint point, String step) {
        if (DOM_ONLY_DEBUG) {
            return; // DOM-only:跳过纯 CDP 鼠标移动(避免鼠标在可见窗口里移到评论区)
        }
        requireOk(browser.service_hover_main(point.x(), point.y()),
                step);
    }

    private void focusCommentRegion(ClickPoint point, String step) {
        if (DOM_ONLY_DEBUG) {
            return; // DOM-only:跳过纯 CDP 鼠标移动
        }
        requireOk(browser.service_hover_main(point.x(), point.y()),
                step);
    }

    private double clampDouble(double value, double min, double max) {
        if (max < min) {
            return min;
        }
        return Math.max(min, Math.min(value, max));
    }

    private String firstNonBlank(String first, String second) {
        return first != null && !first.isBlank() ? first : second == null ? "" : second;
    }

    private BrowserObservation observeActive(String filter) {
        return parseObservation(browser.service_observe_active(filter));
    }

    private BrowserObservation observeTab(long tabId, String filter) {
        return parseObservation(browser.service_observe_tab(tabId, filter));
    }

    private BrowserObservation parseObservation(String raw) {
        JsonNode node = parse(raw);
        if (!node.path("ok").asBoolean(false)) {
            return BrowserObservation.failed(
                    node.path("code").asText("OBSERVE_FAILED"),
                    node.path("message").asText(""));
        }
        JsonNode viewport = node.path("viewport");
        return new BrowserObservation(
                true,
                node.path("url").asText(""),
                node.path("title").asText(""),
                node.path("tree").asText(""),
                viewport.path("w").asInt(1280),
                viewport.path("h").asInt(800),
                "",
                "");
    }

    private void requireOk(String raw, String step) {
        requireOkNode(raw, step);
    }

    private JsonNode requireOkNode(String raw, String step) {
        JsonNode node = parse(raw);
        if (!node.path("ok").asBoolean(false)) {
            throw new DouyinBrowserException(
                    node.path("code").asText(step.toUpperCase(Locale.ROOT) + "_FAILED"),
                    "browser action failed at " + step + ": "
                            + node.path("code").asText("UNKNOWN") + " - "
                            + node.path("message").asText(""));
        }
        return node;
    }

    private JsonNode parse(String raw) {
        try {
            return mapper.readTree(raw == null || raw.isBlank() ? "{}" : raw);
        } catch (Exception e) {
            throw new DouyinBrowserException("BROWSER_JSON_INVALID", e.getMessage());
        }
    }

    private boolean ok(JsonNode node) {
        return node != null && node.path("ok").asBoolean(false);
    }

    private JsonNode errorNode(String code, String message) {
        return mapper.createObjectNode()
                .put("ok", false)
                .put("code", code == null || code.isBlank() ? "UNKNOWN" : code)
                .put("message", message == null ? "" : message);
    }

    private String errorSummary(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return "no result";
        }
        return node.path("code").asText("UNKNOWN") + " - " + node.path("message").asText("");
    }

    private boolean rehoverFilterByText() {
        return ok(parse(browser.service_hover_text_main("筛选", "button", null)));
    }

    private void waitMs(long ms) {
        // These waits are Skill-internal settle delays between real browser actions.
        // Sending a WAIT action to the browser creates an unnecessary tab/session
        // dependency and has caused Douyin runs to fail with NO_TARGET_TAB/SEND_FAILED
        // while the page is navigating or Chrome is switching modal targets.
        sleepLocal(ms);
    }

    private void sleepLocal(long ms) {
        try {
            Thread.sleep(Math.max(0L, ms));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DouyinBrowserException("INTERRUPTED", "等待搜索重试时被中断");
        }
    }

    private boolean looksLikeCommentsOpen(String tree) {
        String normalized = tree == null ? "" : tree;
        List<TreeLine> lines = parseTreeLines(normalized);
        String compact = normalized.replaceAll("\\s+", "");
        if (!compact.contains("评论")) {
            return false;
        }
        if (compact.contains("全部评论")
                || compact.matches(".*\\d+条评论.*")
                || compact.contains("评论区")
                || compact.contains("评论详情")
                || compact.contains("相关回复")
                || normalized.contains("说点什么")
                || normalized.contains("写评论")
                || normalized.contains("发表评论")
                || normalized.contains("暂时没有评论")) {
            return true;
        }
        return hasDouyinCommentPanelStructure(lines);
    }

    private BrowserObservation pressDouyinCommentShortcutX(BrowserObservation current) {
        BrowserObservation observed = pressShortcutAndObserve("x", current, true);
        if (commentsPanelReady(observed)) {
            return observed;
        }
        return observed;
    }

    private boolean hasDouyinCommentPanelTabs(List<TreeLine> lines) {
        boolean detailsTab = false;
        boolean commentsTab = false;
        for (TreeLine line : lines) {
            String role = line.role().toLowerCase(Locale.ROOT);
            String name = line.name().replaceAll("\\s+", "");
            if (!("tab".equals(role) || "button".equals(role) || "text".equals(role)
                    || "statictext".equals(role) || "generic".equals(role))) {
                continue;
            }
            if (line.y() > 180.0d || line.w() > 180.0d || line.h() > 80.0d) {
                continue;
            }
            if ("详情".equals(name)) {
                detailsTab = true;
            } else if ("评论".equals(name) || name.matches("评论\\d*")) {
                commentsTab = true;
            }
        }
        return detailsTab && commentsTab;
    }

    private boolean hasDouyinCommentPanelStructure(List<TreeLine> lines) {
        long commentInputs = lines.stream()
                .filter(line -> isCommentPanelInput(line.name()))
                .count();
        if (commentInputs > 0) {
            return true;
        }
        if (hasVisibleCommentItemEvidence(lines)) {
            return true;
        }
        long replySignals = lines.stream()
                .filter(line -> {
                    String name = line.name();
                    return "回复".equals(name)
                            || name.matches(".*共\\s*\\d+\\s*条回复.*")
                            || name.matches(".*\\d+\\s*分钟前.*")
                            || name.matches(".*\\d+\\s*小时前.*")
                            || name.matches(".*\\d+\\s*天前.*");
                })
                .count();
        if (replySignals > 0 && hasDouyinCommentPanelTabs(lines)) {
            return true;
        }
        long authorSignals = lines.stream()
                .filter(line -> "link".equalsIgnoreCase(line.role()))
                .filter(line -> line.x() >= 0.0d && line.y() >= 80.0d)
                .filter(line -> line.w() <= 260.0d && line.h() <= 80.0d)
                .count();
        return replySignals >= 2 && authorSignals >= 2;
    }

    private boolean hasVisibleCommentItemEvidence(List<TreeLine> lines) {
        if (lines == null || lines.isEmpty()) {
            return false;
        }
        long endMarkers = lines.stream()
                .filter(line -> {
                    String name = line.name().replaceAll("\\s+", "");
                    return name.contains("没有更多评论")
                            || name.contains("暂时没有评论")
                            || name.contains("已展示全部评论");
                })
                .count();
        long authorSignals = lines.stream()
                .filter(line -> "link".equalsIgnoreCase(line.role()))
                .filter(line -> line.y() >= 80.0d)
                .filter(line -> line.w() <= 280.0d && line.h() <= 96.0d)
                .count();
        long bodySignals = lines.stream()
                .filter(this::isLikelyVisibleCommentBody)
                .count();
        if (authorSignals > 0 && bodySignals > 0) {
            return true;
        }
        if (bodySignals >= 2) {
            return true;
        }
        return endMarkers > 0 && (authorSignals > 0 || bodySignals > 0);
    }

    private boolean isLikelyVisibleCommentBody(TreeLine line) {
        if (line == null) {
            return false;
        }
        String role = line.role().toLowerCase(Locale.ROOT);
        String name = line.name() == null ? "" : line.name().trim();
        String compact = name.replaceAll("\\s+", "");
        if (compact.length() < 4 || line.y() < 100.0d) {
            return false;
        }
        if ("button".equals(role) || "tab".equals(role) || "link".equals(role)
                || "searchbox".equals(role) || "textbox".equals(role)) {
            return false;
        }
        if (isCommentPanelHeader(name) || isCommentPanelInput(name)
                || isInteractionLabel(compact)
                || compact.matches("^[\\d.]+([万wWkK千])?$")
                || compact.matches("^展开\\d+条回复$")
                || compact.matches("^共\\d+条回复$")) {
            return false;
        }
        return line.w() >= 80.0d || compact.length() >= 8;
    }

    private boolean isInteractionLabel(String compact) {
        return compact.equals("点赞")
                || compact.equals("收藏")
                || compact.equals("分享")
                || compact.equals("回复")
                || compact.equals("评论")
                || compact.equals("私信")
                || compact.equals("关注")
                || compact.equals("互相关注");
    }

    private BrowserObservation pressShortcutAndObserve(String key, BrowserObservation fallback, boolean focusVideoFirst) {
        if (focusVideoFirst) {
            ClickPoint focus = douyinVideoFocusPoint(fallback);
            if (focus != null) {
                tryOk(browser.service_click_main(focus.x(), focus.y()));
                sleepLocal(250L);
            }
        }
        if (!tryOk(browser.service_press_key_main(key))) {
            return fallback;
        }
        BrowserObservation observed = observeMain("all");
        if (commentsPanelReady(observed)) {
            return observed;
        }
        sleepLocal(800L);
        observed = observeMain("all");
        if (commentsPanelReady(observed)) {
            return observed;
        }
        sleepLocal(500L);
        return observeMain("all");
    }

    private BrowserObservation clickCommentTrigger(BrowserObservation current) {
        BrowserObservation observed = current;
        ClickPoint comments = findCommentTriggerPoint(
                observed.tree(), observed.viewportWidth(), observed.viewportHeight());
        if (comments == null) {
            return observed;
        }
        for (ClickPoint point : commentTriggerClickPoints(observed, comments)) {
            if (!tryOk(browser.service_click_main(point.x(), point.y()))) {
                continue;
            }
            waitMs(1_100L);
            observed = observeMain("all");
            if (commentsPanelReady(observed)) {
                return observed;
            }
        }
        return observed;
    }

    private BrowserObservation clickCommentsByVisibleText(BrowserObservation current) {
        BrowserObservation observed = current;
        for (String role : List.of("button", "tab", "generic")) {
            if (!tryOk(browser.service_click_text_main("评论", role, null))) {
                continue;
            }
            waitMs(1_100L);
            observed = observeMain("all");
            if (commentsPanelReady(observed)) {
                return observed;
            }
        }
        return observed;
    }

    private boolean tryOk(String raw) {
        try {
            return ok(parse(raw));
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private boolean isDouyinPage(String url) {
        return url != null && (url.contains("douyin.com") || url.contains("iesdouyin.com"));
    }

    private BrowserObservation waitForActiveAuthorProfile(DouyinCommentItem comment, int attempts, long waitMs) {
        BrowserObservation observed = observeActive("all");
        for (int i = 0; i < attempts; i++) {
            if (looksLikeDouyinUserProfile(observed, comment)) {
                return observed;
            }
            waitMs(waitMs);
            observed = observeActive("all");
        }
        return observed;
    }

    private BrowserObservation waitForAuthorProfileAnyTab(DouyinCommentItem comment, int attempts, long waitMs) {
        BrowserObservation last = observeActive("all");
        for (int i = 0; i < attempts; i++) {
            if (looksLikeDouyinUserProfile(last, comment)) {
                return last;
            }
            BrowserObservation main = observeMain("all");
            if (looksLikeDouyinUserProfile(main, comment)) {
                return main;
            }
            waitMs(waitMs);
            last = observeActive("all");
        }
        return last;
    }

    private OpenedAuthorProfile waitForAuthorProfileTabOrActive(long tabId,
                                                                 DouyinCommentItem comment,
                                                                 int attempts,
                                                                 long waitMs) {
        BrowserObservation observed = observeTab(tabId, "all");
        for (int i = 0; i < Math.max(1, attempts); i++) {
            if (looksLikeDouyinUserProfile(observed, comment)) {
                return new OpenedAuthorProfile(observed, tabId);
            }
            if (authorProfileObservationTerminalFailure(observed)) {
                return new OpenedAuthorProfile(observed, tabId);
            }
            if (isBlankObservation(observed)) {
                // [出路③] 后台 tab 的 CDP a11y observe 必然返回空(snapshot blank)——这是 Chrome 对
                // 非活动 tab 的硬限制。但 open_author 已确认该 tab 导航到目标作者主页(/user/，
                // readyState complete)，且后台 DOM 仍可点击(executeScript 不受 hidden 影响、实测坐标
                // 不塌缩、抖音接受合成点击)。故不再 fallback 到用户当前 active/main tab(那会跑到错的
                // 页面、还逼用户切过去)，改为坚持该后台 tabId：用已知作者 url 构造 observation
                // (tree 空不影响后续 DOM 触达；looksLikeDouyinUserProfile 仅凭 url token 匹配即通过)。
                String authorUrl = comment == null ? null : comment.authorProfileUrl();
                if (authorUrl != null && !authorUrl.isBlank()) {
                    log.info("[douyin.lead] author profile tab {} snapshot blank (background); keeping tab for DOM-only engagement, url={}",
                            tabId, authorUrl);
                    return new OpenedAuthorProfile(
                            new BrowserObservation(true, authorUrl, "", "", 1280, 800, "", ""),
                            tabId);
                }
            }
            waitMs(waitMs);
            observed = observeTab(tabId, "all");
        }
        return new OpenedAuthorProfile(observed, tabId);
    }

    private boolean authorProfileObservationTerminalFailure(BrowserObservation observed) {
        if (observed == null || observed.ok()) {
            return false;
        }
        String code = observed.code() == null ? "" : observed.code();
        String message = observed.message() == null ? "" : observed.message().toLowerCase(Locale.ROOT);
        return "SESSION_DETACHED".equals(code)
                || message.contains("target_closed");
    }

    private boolean isBlankObservation(BrowserObservation obs) {
        if (obs == null) {
            return true;
        }
        return (obs.url() == null || obs.url().isBlank())
                && (obs.title() == null || obs.title().isBlank())
                && (obs.tree() == null || obs.tree().isBlank());
    }

    private List<ClickPoint> authorOpenClickPoints(DouyinCommentItem.ClickTarget target) {
        if (target == null || !target.hasPoint()) {
            return List.of();
        }
        double x = target.x();
        double y = target.y();
        List<ClickPoint> points = new ArrayList<>();
        points.add(new ClickPoint(x, y));
        points.add(new ClickPoint(x - 24.0d, y));
        points.add(new ClickPoint(x + 24.0d, y));
        points.add(new ClickPoint(x, y - 14.0d));
        points.add(new ClickPoint(x, y + 14.0d));
        return points;
    }

    private boolean looksLikeDouyinUserProfile(BrowserObservation obs, DouyinCommentItem comment) {
        if (obs == null || !isDouyinPage(obs.url())) {
            return false;
        }
        String url = obs.url() == null ? "" : obs.url().toLowerCase(Locale.ROOT);
        String title = obs.title() == null ? "" : obs.title();
        String tree = obs.tree() == null ? "" : obs.tree();
        String author = comment == null ? "" : (comment.authorName() == null ? "" : comment.authorName().trim());
        String expectedProfileToken = comment == null ? "" : profileIdentityToken(comment.authorProfileUrl());
        String observedProfileToken = profileIdentityToken(obs.url());
        boolean expectedUrlMatches = !expectedProfileToken.isBlank()
                && expectedProfileToken.equalsIgnoreCase(observedProfileToken);
        if (!expectedProfileToken.isBlank()
                && !observedProfileToken.isBlank()
                && !expectedUrlMatches) {
            return false;
        }
        boolean profileUrl = url.contains("/user/") || url.contains("sec_uid") || url.contains("modal_id=user");
        boolean profileSignals = tree.contains("获赞")
                || tree.contains("粉丝")
                || tree.contains("关注")
                || tree.contains("TA的作品")
                || tree.contains("作品");
        boolean authorVisible = expectedUrlMatches || author.isBlank() || tree.contains(author) || title.contains(author);
        boolean wrongGlobalPage = !profileUrl && (url.contains("/follow")
                || url.contains("/search")
                || url.contains("/video/")
                || tree.contains("搜索你感兴趣的内容"));
        return !wrongGlobalPage && authorVisible && (profileUrl || profileSignals);
    }

    private String profileIdentityToken(String profileUrl) {
        if (profileUrl == null || profileUrl.isBlank()) {
            return "";
        }
        String raw = profileUrl.trim();
        int idx = raw.toLowerCase(Locale.ROOT).indexOf("/user/");
        if (idx < 0) {
            return "";
        }
        String token = raw.substring(idx + "/user/".length());
        int end = token.length();
        for (String marker : List.of("?", "#", "/")) {
            int markerIndex = token.indexOf(marker);
            if (markerIndex >= 0) {
                end = Math.min(end, markerIndex);
            }
        }
        return token.substring(0, end).trim();
    }

    private BrowserObservation waitForSearchVerified(String keyword, int attempts, long waitMs) {
        BrowserObservation observed = observeMain("all");
        for (int i = 0; i < attempts; i++) {
            if (searchVerified(observed, keyword)) {
                return observed;
            }
            waitMs(waitMs);
            observed = observeMain("all");
        }
        return observed;
    }

    private SearchTarget waitForSearchBoxTarget(int attempts, long waitMs) {
        BrowserObservation observed = observeMain("all");
        for (int i = 0; i < attempts; i++) {
            ClickPoint point = findDouyinSearchBoxPoint(observed.tree());
            if (point != null) {
                return new SearchTarget(point, observed);
            }
            waitMs(waitMs);
            observed = observeMain("all");
        }
        return new SearchTarget(null, observed);
    }

    private BrowserObservation waitForSearchTextEntered(String keyword, int attempts, long waitMs) {
        BrowserObservation observed = observeMain("all");
        for (int i = 0; i < attempts; i++) {
            if (searchTextEntered(observed, keyword)) {
                return observed;
            }
            waitMs(waitMs);
            observed = observeMain("all");
        }
        return observed;
    }

    private void submitSearchWithEnterAndVerify(String keyword) {
        JsonNode press = parse(browser.service_press_key_main("Enter"));
        if (ok(press)) {
            BrowserObservation observed = waitForSearchVerified(keyword, 4, 550L);
            if (searchVerified(observed, keyword)) {
                return;
            }
            SearchTarget retryTarget = waitForSearchBoxTarget(2, 250L);
            if (retryTarget.point() != null) {
                TypePayload.FocusTarget focus = new TypePayload.FocusTarget(retryTarget.point().x(), retryTarget.point().y());
                if (tryOk(browser.service_type_main(keyword + "\n", focus))) {
                    observed = waitForSearchVerified(keyword, 5, 650L);
                    if (searchVerified(observed, keyword)) {
                        return;
                    }
                }
                ClickPoint submit = findTopSearchSubmitButton(observed.tree(), retryTarget.point());
                if (submit != null && tryOk(browser.service_click_main(submit.x(), submit.y()))) {
                    observed = waitForSearchVerified(keyword, 6, 700L);
                    if (searchVerified(observed, keyword)) {
                        return;
                    }
                }
            }
            throw new DouyinBrowserException("SEARCH_NOT_VERIFIED",
                    "已输入关键词并提交，但没有观察到 " + keyword + " 搜索结果或筛选入口。url="
                            + observed.url() + ", title=" + observed.title()
                            + ", signals=" + searchSignals(observed, keyword));
        }
        String code = press.path("code").asText("");
        String message = press.path("message").asText("");
        if (!"SESSION_DETACHED".equals(code) && !message.contains("SESSION_DETACHED")
                && !message.contains("target_closed")) {
            BrowserObservation observed = observeMain("all");
            ClickPoint submit = findTopSearchSubmitButton(observed.tree(), findDouyinSearchBoxPoint(observed.tree()));
            if (submit != null && tryOk(browser.service_click_main(submit.x(), submit.y()))) {
                observed = waitForSearchVerified(keyword, 6, 700L);
                if (searchVerified(observed, keyword)) {
                    return;
                }
            }
            throw new DouyinBrowserException(code.isBlank() ? "SEARCH_SUBMIT_FAILED" : code,
                    "browser action failed at submit_search_enter: " + errorSummary(press));
        }

        sleepLocal(1200L);
        BrowserObservation observed = waitForSearchVerified(keyword, 8, 900L);
        if (!searchVerified(observed, keyword)) {
            throw new DouyinBrowserException("SESSION_DETACHED",
                    "按回车提交搜索时调试会话断开，且恢复观察后未确认搜索结果。url="
                            + observed.url() + ", title=" + observed.title()
                            + ", signals=" + searchSignals(observed, keyword));
        }
    }

    @Nullable
    ClickPoint findTopSearchSubmitButton(String tree, @Nullable ClickPoint searchBox) {
        if (tree == null || tree.isBlank()) {
            return null;
        }
        List<TreeLine> lines = parseTreeLines(tree);
        double anchorY = searchBox == null ? 32.0d : searchBox.y();
        double anchorX = searchBox == null ? 0.0d : searchBox.x();
        return lines.stream()
                .filter(line -> line.role().equalsIgnoreCase("button")
                        || line.role().equalsIgnoreCase("generic")
                        || line.role().equalsIgnoreCase("text"))
                .filter(line -> line.name().replaceAll("\\s+", "").equals("搜索"))
                .filter(line -> !looksLikeAiSearchLine(line.name()))
                .filter(line -> line.y() <= 90.0d)
                .filter(line -> Math.abs((line.y() + line.h() / 2.0d) - anchorY) <= 42.0d)
                .filter(line -> searchBox == null || line.x() >= anchorX)
                .min(Comparator.comparingDouble((TreeLine line) -> Math.abs(line.y() + line.h() / 2.0d - anchorY))
                        .thenComparingDouble(line -> searchBox == null ? line.x() : Math.abs(line.x() - anchorX)))
                .map(TreeLine::point)
                .orElse(null);
    }

    private BrowserObservation waitForMostLikedSortVerified(String keyword, int attempts, long waitMs) {
        BrowserObservation observed = observeMain("all");
        for (int i = 0; i < attempts; i++) {
            if (mostLikedSortAcceptedAfterExplicitSelection(observed, keyword)) {
                return observed;
            }
            waitMs(waitMs);
            observed = observeMain("all");
        }
        throw new DouyinBrowserException("SORT_NOT_CONFIRMED",
                "已点击「最多点赞」，但没有确认排序已生效。url=" + observed.url()
                        + ", title=" + observed.title()
                        + ", signals=" + sortSignals(observed, keyword)
                        + ", tree=" + treeExcerpt(observed.tree()));
    }

    private BrowserObservation ensurePlainSearchResultPage(BrowserObservation observed, String keyword) {
        BrowserObservation current = observed == null ? observeMain("all") : observed;
        if (!hasVideoModalUrl(current.url())) {
            return current;
        }
        tryOk(browser.service_press_key_main("Escape"));
        sleepLocal(500L);
        current = observeMain("all");
        if (!hasVideoModalUrl(current.url()) && searchVerified(current, keyword)) {
            return current;
        }
        String encodedKeyword = URLEncoder.encode(keyword == null ? "" : keyword.trim(), StandardCharsets.UTF_8);
        if (!encodedKeyword.isBlank()) {
            tryOk(browser.extension_browser_navigate(
                    "https://www.douyin.com/jingxuan/search/" + encodedKeyword,
                    "domcontentloaded",
                    null));
            sleepLocal(900L);
            current = waitForSearchVerified(keyword, 4, 650L);
        }
        return current;
    }

    private boolean hasVideoModalUrl(@Nullable String url) {
        String lower = url == null ? "" : url.toLowerCase(Locale.ROOT);
        return lower.contains("modal_id=") || lower.contains("aweme_id=") || lower.contains("/video/");
    }

    private BrowserObservation openFilterPanel(BrowserObservation searchPage, String keyword, DouyinSortSpec sort) {
        BrowserObservation observed = searchPage == null ? observeMain("all") : searchPage;
        TreeLine filter = findFilterTriggerLine(observed.tree());
        if (filter == null) {
            filter = waitForFilterTriggerLine(5, 500L);
            observed = observeMain("all");
        }
        if (filter == null) {
            JsonNode hoverByText = parse(browser.service_hover_text_main("筛选", "button", null));
            waitMs(FILTER_PANEL_SETTLE_DELAY_MS);
            observed = observeMain("all");
            if (filterPanelLooksOpen(observed.tree())) {
                return observed;
            }
            JsonNode clickByText = parse(browser.service_click_text_main("筛选", "button", null));
            waitMs(FILTER_PANEL_SETTLE_DELAY_MS);
            observed = observeMain("all");
            if (filterPanelLooksOpen(observed.tree())) {
                return observed;
            }
            throw new DouyinBrowserException("FILTER_NOT_FOUND",
                    "未找到筛选入口或无法打开筛选面板: hover=" + errorSummary(hoverByText)
                            + ", click=" + errorSummary(clickByText)
                            + ", signals=" + sortSignals(observed, keyword, sort));
        }

        TreeLine filterLine = filter;
        List<ClickPoint> filterPoints = filterTriggerPoints(filterLine, observed.viewportWidth(), observed.viewportHeight());
        ClickPoint filterPoint = filterPoints.getFirst();
        List<Supplier<Boolean>> attempts = new ArrayList<>();
        for (ClickPoint point : filterPoints) {
            attempts.add(() -> tryOk(browser.service_hover_main(point.x(), point.y())));
            attempts.add(() -> tryOk(browser.service_click_main(point.x(), point.y())));
        }
        attempts.addAll(List.of(
                () -> tryOk(browser.service_hover_text_main("筛选", "button", null)),
                () -> tryOk(browser.service_click_text_main("筛选", "button", null)),
                () -> tryOk(browser.service_hover_text_main("筛选", "generic", null)),
                () -> tryOk(browser.service_click_text_main("筛选", "generic", null))));
        for (int i = 0; i < attempts.size(); i++) {
            boolean actionOk = attempts.get(i).get();
            waitMs(FILTER_PANEL_SETTLE_DELAY_MS);
            observed = observeMain("all");
            if (filterPanelLooksOpen(observed.tree())) {
                return observed;
            }
            if (sortVerified(observed, keyword, sort)) {
                return observed;
            }
            if (!actionOk && i >= 2) {
                waitMs(250L);
            }
        }

        if (sortVerified(observed, keyword, sort)) {
            return observed;
        }

        throw new DouyinBrowserException("FILTER_PANEL_NOT_OPEN",
                "已定位筛选入口但无法确认面板打开。filter=(" + filterPoint.x() + "," + filterPoint.y() + ")"
                        + ", filterLine=" + filterLine.name() + "@{" + filterLine.x() + "," + filterLine.y()
                        + " " + filterLine.w() + "x" + filterLine.h() + "}"
                        + ", signals=" + sortSignals(observed, keyword, sort)
                        + ", tree=" + treeExcerpt(observed.tree()));
    }

    private BrowserObservation selectSortOption(BrowserObservation panel, String keyword, DouyinSortSpec sort) {
        BrowserObservation observed = panel == null ? observeMain("all") : panel;
        if (sortVerified(observed, keyword, sort)) {
            rememberSortedVideoSnapshot(keyword, observed);
            return observed;
        }
        for (int attempt = 0; attempt < 3; attempt++) {
            ClickPoint option = findSortOptionPoint(observed.tree(), sort.labels());
            if (option != null) {
                requireOk(browser.service_hover_main(option.x(), option.y()), "hover_" + sort.code());
                requireOk(browser.service_click_main(option.x(), option.y()), "select_" + sort.code());
                log.info("[douyin.lead] clicked sort option by a11y: sort={}, label={}, x={}, y={}",
                        sort.code(), sort.primaryLabel(), option.x(), option.y());
                waitMs(SORT_SELECT_SETTLE_DELAY_MS);
                return waitForSortVerifiedAfterSelection(keyword, sort, 14, 800L);
            }

            for (String role : List.of("button", "menuitem", "option", "generic")) {
                JsonNode textClick = parse(browser.service_click_text_main(sort.primaryLabel(), role, null));
                if (ok(textClick)) {
                    log.info("[douyin.lead] clicked sort option by text: sort={}, label={}, role={}",
                            sort.code(), sort.primaryLabel(), role);
                    waitMs(SORT_SELECT_SETTLE_DELAY_MS);
                    return waitForSortVerifiedAfterSelection(keyword, sort, 14, 800L);
                }
            }

            observed = openFilterPanel(observed, keyword, sort);
        }
        throw new DouyinBrowserException("SORT_OPTION_NOT_FOUND",
                "筛选面板已打开，但没有定位/点击到「" + sort.displayName() + "」。filterPanelVisible="
                        + filterPanelLooksOpen(observed.tree())
                        + ", signals=" + sortSignals(observed, keyword, sort)
                        + ", tree=" + treeExcerpt(observed.tree()));
    }

    private BrowserObservation waitForSortVerifiedAfterSelection(String keyword, DouyinSortSpec sort, int attempts, long waitMs) {
        BrowserObservation observed = observeMain("all");
        for (int i = 0; i < attempts; i++) {
            if (sortAcceptedAfterExplicitSelection(observed, keyword, sort)) {
                rememberSortedVideoSnapshot(keyword, observed);
                return observed;
            }
            List<VideoResultTarget> targets = visualOrderVideoTargets(
                    observed.tree(), observed.viewportWidth(), observed.viewportHeight());
            if (searchVerified(observed, keyword)
                    && observed.tree() != null
                    && containsAnyLabel(observed.tree(), sort.labels())
                    && targets.isEmpty()) {
                waitMs(waitMs);
                observed = observeMain("all");
                continue;
            }
            waitMs(waitMs);
            observed = observeMain("all");
        }
        if (sortAcceptedAfterExplicitSelection(observed, keyword, sort)) {
            rememberSortedVideoSnapshot(keyword, observed);
            return observed;
        }
        clearSortedVideoSnapshot();
        throw new DouyinBrowserException("SORT_NOT_CONFIRMED",
                "已点击「" + sort.displayName() + "」，但没有确认排序已生效。url=" + observed.url()
                        + ", title=" + observed.title()
                        + ", signals=" + sortSignals(observed, keyword, sort)
                        + ", tree=" + treeExcerpt(observed.tree()));
    }

    boolean mostLikedSortVerifiedAfterExplicitSelection(BrowserObservation observed, String keyword) {
        if (!searchVerified(observed, keyword)) {
            return false;
        }
        List<VideoResultTarget> targets = visualOrderVideoTargets(
                observed.tree(), observed.viewportWidth(), observed.viewportHeight());
        if (targets.size() >= 2) {
            return firstVisibleRowLooksLikeMostLiked(targets)
                    || firstTwoVisibleResultsLookMostLiked(targets);
        }
        return mostLikedSelectedSignal(observed.tree()) || urlHasSortSignal(observed.url());
    }

    boolean sortVerifiedAfterExplicitSelection(BrowserObservation observed, String keyword, DouyinSortSpec sort) {
        if (sort.isMostLiked()) {
            return mostLikedSortVerifiedAfterExplicitSelection(observed, keyword);
        }
        if (!searchVerified(observed, keyword)) {
            return false;
        }
        return strictSortSelectedSignal(observed.tree(), sort.labels()) || urlHasLatestSortSignal(observed.url());
    }

    boolean mostLikedSortAcceptedAfterExplicitSelection(BrowserObservation observed, String keyword) {
        if (mostLikedSortVerifiedAfterExplicitSelection(observed, keyword)) {
            return true;
        }
        if (!searchVerified(observed, keyword)) {
            return false;
        }
        String tree = observed.tree() == null ? "" : observed.tree();
        List<VideoResultTarget> targets = visualOrderVideoTargets(
                tree, observed.viewportWidth(), observed.viewportHeight());
        if (canRankVisibleResultsByLikes(targets)) {
            return false;
        }
        return searchResultsPresentAfterSortClick(tree, targets);
    }

    boolean sortAcceptedAfterExplicitSelection(BrowserObservation observed, String keyword, DouyinSortSpec sort) {
        if (sort.isMostLiked()) {
            return mostLikedSortAcceptedAfterExplicitSelection(observed, keyword);
        }
        if (sortVerifiedAfterExplicitSelection(observed, keyword, sort)) {
            return true;
        }
        if (!searchVerified(observed, keyword)) {
            return false;
        }
        String tree = observed.tree() == null ? "" : observed.tree();
        List<VideoResultTarget> targets = visualOrderVideoTargets(
                tree, observed.viewportWidth(), observed.viewportHeight());
        return searchResultsPresentAfterSortClick(tree, targets);
    }

    private boolean searchResultsPresentAfterSortClick(String tree, List<VideoResultTarget> targets) {
        if (tree == null || tree.isBlank()) {
            return false;
        }
        if (targets != null && !targets.isEmpty()) {
            return true;
        }
        if (tree.contains("播放")) {
            return true;
        }
        return parseTreeLines(tree).stream()
                .filter(line -> line.y() >= 120.0d)
                .anyMatch(line -> parseLikeCount(line.name()) > 0.0d
                        && line.x() >= resultLeftX(1_280)
                        && line.w() >= 24.0d);
    }

    /**
     * 【搜索/排序后等结果就绪】进入点视频之前,等"结果区真正出现视频卡"再继续。轻量、无点击副作用:
     *   - 前台(a11y 树可用):用 {@link #videoCandidatesFromObservation} 检测视频卡,命中即提前退出;
     *   - 后台(a11y 树为空,检测不出卡):退而给一个排序重载就绪宽限(SORT_RELOAD_SETTLE_MS),
     *     让结果区有时间懒加载,随后由 douyin_open_video 在页内直接探测/点击。
     * 带总上限(慢系数放大后的轮询次数),命中或达上限即返回,绝不无限等待。不调 douyin_open_video,
     * 避免在"就绪等待"阶段就产生点开视频的副作用。
     */
    private void waitForVideoResultListReady() {
        int attempts = slowAttempts(6);
        long stepMs = slowMs(600L);
        boolean a11yEverUsable = false;
        for (int i = 0; i < attempts; i++) {
            BrowserObservation observed = observeMain("all");
            if (looksLikeVideoOpenHard(observed)) {
                // 已经在视频页(复用既有页),无需再等结果列表。
                return;
            }
            boolean a11yUsable = !isBlankObservation(observed);
            a11yEverUsable = a11yEverUsable || a11yUsable;
            if (a11yUsable && !videoCandidatesFromObservation(observed).isEmpty()) {
                log.info("[douyin.lead] video result list ready (cards visible) after {} settle rounds", i);
                return;
            }
            waitMs(stepMs);
        }
        if (!a11yEverUsable) {
            // 全程 a11y 空(后台 tab):无法据 observe 判就绪,给排序重载一个固定就绪宽限再继续。
            log.info("[douyin.lead] video result readiness unverifiable (background blank a11y); "
                    + "applying sort-reload settle {}ms before opening video", SORT_RELOAD_SETTLE_MS);
            waitMs(SORT_RELOAD_SETTLE_MS);
        } else {
            log.info("[douyin.lead] video result list not confirmed within {} settle rounds; proceeding to open", attempts);
        }
    }

    private VideoCandidates waitForVideoTargets(int attempts, long waitMs) {
        BrowserObservation observed = observeMain("all");
        VideoCandidates candidates = videoCandidatesFromObservation(observed);
        String lastDebug = candidates.debug();
        for (int i = 0; i < attempts; i++) {
            if (!candidates.isEmpty()) {
                return candidates;
            }
            waitMs(waitMs);
            observed = observeMain("all");
            candidates = videoCandidatesFromObservation(observed);
            lastDebug = candidates.debug();
        }
        return new VideoCandidates(List.of(), observed, lastDebug);
    }

    private VideoCandidates videoCandidatesFromObservation(BrowserObservation observed) {
        if (observed == null) {
            return new VideoCandidates(List.of(), BrowserObservation.failed("NO_OBSERVATION", "no observation"), "");
        }
        DomVideoTargets dom = domVideoTargets(observed);
        String debug = dom.debug();
        List<VideoResultTarget> targets = dom.targets();
        if (targets.isEmpty()) {
            targets = visualOrderVideoTargets(
                    observed.tree(), observed.viewportWidth(), observed.viewportHeight());
            if (!targets.isEmpty()) {
                debug = debug + "; using a11y_structured_fallback count=" + targets.size();
            }
        }
        return new VideoCandidates(targets, observed, debug);
    }

    private void rememberSortedVideoSnapshot(String keyword, BrowserObservation observed) {
        VideoCandidates candidates = videoCandidatesFromObservation(observed);
        if (candidates.isEmpty()) {
            clearSortedVideoSnapshot();
            return;
        }
        candidates = new VideoCandidates(
                orderVideoTargetsByRows(candidates.targets()),
                candidates.observation(),
                candidates.debug() + "; order=visual_after_sort");
        lastSortedVideoSnapshot = new SortedVideoSnapshot(
                keyword == null ? "" : keyword.trim().toLowerCase(Locale.ROOT),
                candidates,
                System.currentTimeMillis());
    }

    private void clearSortedVideoSnapshot() {
        lastSortedVideoSnapshot = SortedVideoSnapshot.empty();
    }

    private VideoCandidates sortedVideoSnapshotCandidates(BrowserObservation current) {
        SortedVideoSnapshot snapshot = lastSortedVideoSnapshot;
        if (snapshot.isEmpty()) {
            return new VideoCandidates(List.of(), BrowserObservation.failed("NO_SORTED_SNAPSHOT", "no sorted snapshot"),
                    "no sorted snapshot");
        }
        current = current == null ? observeMain("all") : current;
        if (System.currentTimeMillis() - snapshot.capturedAtMs() > SORTED_VIDEO_SNAPSHOT_TTL_MS) {
            clearSortedVideoSnapshot();
            return new VideoCandidates(List.of(), current, "sorted snapshot expired");
        }
        if (!canReuseSearchResultsForSorting(current, snapshot.keyword())) {
            return new VideoCandidates(List.of(), current, "sorted snapshot invalid for current page");
        }
        VideoCandidates candidates = snapshot.candidates();
        return new VideoCandidates(
                candidates.targets(),
                candidates.observation(),
                candidates.debug() + "; using sorted_snapshot keyword=" + snapshot.keyword());
    }

    private DomVideoTargets domVideoTargets(BrowserObservation observed) {
        if (observed == null || !observed.ok()) {
            return new DomVideoTargets(List.of(), "no observation");
        }
        RegionInfo region = searchResultsRegion(observed);
        try {
            if (!tryOk(browser.service_register_region_main(
                    region.regionKey(), region.x(), region.y(), region.width(), region.height(), region.source()))) {
                return new DomVideoTargets(List.of(), "register_region failed");
            }
            JsonNode root = parse(browser.service_extract_region_main(region.regionKey(), 80));
            if (!root.path("ok").asBoolean(false)) {
                return new DomVideoTargets(List.of(), "extract_region failed: " + errorSummary(root));
            }
            JsonNode items = root.path("results").path(0).path("payload").path("items");
            if (!items.isArray() || items.isEmpty()) {
                return new DomVideoTargets(List.of(), "extract_region returned no items");
            }
            List<VideoResultTarget> out = new ArrayList<>();
            int order = 0;
            for (JsonNode item : items) {
                String itemType = item.path("itemType").asText("");
                String title = cleanExtractedVideoTitle(item.path("text").asText(""));
                String href = bestVideoHref(item);
                if (!isExtractedVideoResultCandidate(itemType, title, href)) {
                    continue;
                }
                Bounds bounds = boundsFromExtractedItem(item.path("bbox"));
                if (bounds == null || bounds.width() < 40.0d || bounds.height() < 30.0d) {
                    continue;
                }
                TreeLine line = new TreeLine("dom", title, bounds);
                out.add(new VideoResultTarget(
                        line,
                        line,
                        title,
                        parseLikeCount(title),
                        1_000 - order++,
                        href,
                        domExtractSource(itemType, href)));
            }
            List<VideoResultTarget> ordered = orderVideoTargetsByRows(out);
            return new DomVideoTargets(ordered, "dom items=" + items.size()
                    + ", videoCandidates=" + out.size()
                    + ", order=visual_bbox"
                    + ", sample=" + extractedItemsDebug(items));
        } catch (Exception ignored) {
            return new DomVideoTargets(List.of(), "dom extraction exception: " + ignored.getMessage());
        }
    }

    private String extractedItemsDebug(JsonNode items) {
        if (items == null || !items.isArray()) {
            return "no extracted items";
        }
        List<String> sample = new ArrayList<>();
        int count = 0;
        for (JsonNode item : items) {
            if (count++ >= 6) {
                break;
            }
            sample.add("{type=" + item.path("itemType").asText("")
                    + ", tag=" + item.path("tag").asText("")
                    + ", href=" + item.path("href").asText("")
                    + ", text=" + cleanExtractedVideoTitle(item.path("text").asText("")).replace("\"", "'") + "}");
        }
        return sample.toString();
    }

    private boolean isExtractedVideoResultCandidate(String itemType, String title, @Nullable String href) {
        boolean hasVideoHref = looksLikeDouyinVideoHref(href);
        if (!hasVideoHref && !"douyin_video_result".equals(itemType)) {
            return false;
        }
        if ("douyin_video_result".equals(itemType)) {
            return (title != null
                    && title.length() >= 8
                    && !isVideoControlText(title)
                    && !isNavOnlyText(title)
                    && (title.toLowerCase(Locale.ROOT).contains("openclaw")
                    || title.contains("#")
                    || title.contains("龙虾")
                    || parseLikeCount(title) > 0.0d));
        }
        String normalized = title == null ? "" : title.toLowerCase(Locale.ROOT);
        return title != null
                && title.length() >= 8
                && !isVideoControlText(title)
                && !isNavOnlyText(title)
                && (normalized.contains("openclaw")
                || title.contains("#")
                || title.contains("龙虾")
                    || parseLikeCount(title) > 0.0d);
    }

    private String domExtractSource(String itemType, @Nullable String href) {
        if (!"douyin_video_result".equals(itemType)) {
            return "dom_extract_generic";
        }
        return looksLikeDouyinVideoHref(href) ? "dom_extract" : "dom_extract_clickable";
    }

    private RegionInfo searchResultsRegion(BrowserObservation observed) {
        int viewportW = Math.max(1, observed.viewportWidth());
        int viewportH = Math.max(1, observed.viewportHeight());
        return new RegionInfo(
                "douyin.search_results",
                0.0d,
                0.0d,
                viewportW,
                viewportH,
                viewportW / 2.0d,
                viewportH / 2.0d,
                "search-results-dom");
    }

    boolean searchTextEntered(BrowserObservation observed, String keyword) {
        if (observed == null || !observed.ok()) {
            return false;
        }
        String key = keyword == null ? "" : keyword.trim().toLowerCase(Locale.ROOT);
        if (key.isBlank()) {
            return false;
        }
        List<TreeLine> lines = parseTreeLines(observed.tree());
        boolean strictInputMatch = lines.stream()
                .filter(line -> isSearchEditableRole(line.role()))
                .filter(line -> !looksLikeAiSearchLine(line.name()))
                .anyMatch(line -> line.name().toLowerCase(Locale.ROOT).contains(key));
        if (strictInputMatch) {
            return true;
        }
        return keywordVisibleInSearchSurface(lines, key, observed.viewportHeight());
    }

    private boolean keywordVisibleInSearchSurface(List<TreeLine> lines, String key, int viewportHeight) {
        if (lines == null || lines.isEmpty() || key == null || key.isBlank()) {
            return false;
        }
        double maxTopSearchY = Math.min(210.0d, Math.max(150.0d, viewportHeight * 0.28d));
        boolean searchSurfaceVisible = lines.stream()
                .filter(line -> line.y() <= maxTopSearchY)
                .filter(line -> !looksLikeAiSearchLine(line.name()))
                .anyMatch(line -> isSearchEditableRole(line.role())
                        || looksLikeSearchInputName(line.name())
                        || ("Button".equalsIgnoreCase(line.role()) && line.name().contains("搜索")));
        if (!searchSurfaceVisible) {
            return false;
        }
        return lines.stream()
                .filter(line -> line.y() <= maxTopSearchY)
                .filter(line -> line.x() >= 120.0d)
                .filter(line -> line.h() <= 90.0d)
                .filter(line -> !looksLikeAiSearchLine(line.name()))
                .anyMatch(line -> line.name().toLowerCase(Locale.ROOT).contains(key));
    }

    private boolean isSearchEditableRole(String role) {
        return role != null && (role.equalsIgnoreCase("Searchbox")
                || role.equalsIgnoreCase("Textbox")
                || role.equalsIgnoreCase("Combobox")
                || role.equalsIgnoreCase("Generic"));
    }

    private JsonNode focusSearchBoxByGrounding() {
        JsonNode last = errorNode("GROUNDING_MISS", "no focus attempt");
        for (SearchGrounding grounding : List.of(
                new SearchGrounding("搜索你感兴趣的内容", "searchbox"),
                new SearchGrounding("搜索你感兴趣的内容", "textbox"),
                new SearchGrounding("搜索你感兴趣的内容", "combobox"),
                new SearchGrounding("搜索你感兴趣的内容", "generic"),
                new SearchGrounding("搜索", "searchbox"),
                new SearchGrounding("搜索", "textbox"),
                new SearchGrounding("搜索", "combobox"),
                new SearchGrounding("搜索", "generic"))) {
            last = tryFocusSearchBoxByGrounding(grounding);
            if (ok(last)) {
                return last;
            }
        }
        return last;
    }

    private JsonNode tryFocusSearchBoxByGrounding(SearchGrounding grounding) {
        try {
            return parse(browser.service_click_text_main(grounding.hintText(), grounding.role(), null));
        } catch (DouyinBrowserException ignored) {
            return errorNode("GROUNDING_EXCEPTION", ignored.getMessage());
        }
    }

    @Nullable
    private ClickPoint waitForPointByAnyText(String needle, int attempts, long waitMs) {
        BrowserObservation observed = observeMain("all");
        for (int i = 0; i < attempts; i++) {
            ClickPoint point = findPointByAnyText(observed.tree(), needle);
            if (point != null) {
                return point;
            }
            waitMs(waitMs);
            observed = observeMain("all");
        }
        return null;
    }

    @Nullable
    private ClickPoint waitForFilterTriggerPoint(int attempts, long waitMs) {
        TreeLine line = waitForFilterTriggerLine(attempts, waitMs);
        return line == null ? null : line.point();
    }

    @Nullable
    private TreeLine waitForFilterTriggerLine(int attempts, long waitMs) {
        BrowserObservation observed = observeMain("all");
        for (int i = 0; i < attempts; i++) {
            TreeLine line = findFilterTriggerLine(observed.tree());
            if (line != null) {
                return line;
            }
            waitMs(waitMs);
            observed = observeMain("all");
        }
        return null;
    }

    private ClickPoint douyinVideoFocusPoint(BrowserObservation observed) {
        int viewportW = Math.max(1, observed.viewportWidth());
        int viewportH = Math.max(1, observed.viewportHeight());
        return parseTreeLines(observed.tree()).stream()
                .filter(line -> line.name().contains("暂停") || line.name().contains("播放"))
                .filter(line -> line.x() < viewportW * 0.78d)
                .max(Comparator.comparingDouble(TreeLine::y))
                .map(TreeLine::point)
                .orElseGet(() -> new ClickPoint(
                        Math.max(160.0d, Math.min(viewportW * 0.42d, viewportW - 96.0d)),
                        Math.max(120.0d, Math.min(viewportH * 0.52d, viewportH - 96.0d))));
    }

    @Nullable
    ClickPoint findCommentTriggerPoint(String tree, int viewportWidth, int viewportHeight) {
        List<TreeLine> lines = parseTreeLines(tree);
        TreeLine explicit = lines.stream()
                .filter(line -> line.name().contains("评论"))
                .filter(line -> !isCommentPanelHeader(line.name()))
                .filter(line -> !isCommentPanelInput(line.name()))
                .filter(line -> !isProbablyLongCommentText(line))
                .map(line -> new CommentTriggerCandidate(line, scoreCommentTrigger(line, viewportWidth, viewportHeight)))
                .filter(candidate -> candidate.score() >= 45)
                .max(Comparator.comparingInt(CommentTriggerCandidate::score))
                .map(CommentTriggerCandidate::line)
                .orElse(null);
        if (explicit != null) {
            return explicit.point();
        }
        TreeLine inferred = inferredCommentTriggerFromActionBar(lines, viewportWidth);
        if (inferred != null) {
            return inferred.point();
        }
        TreeLine rightRailCount = inferredCommentCountFromRightRail(lines, viewportWidth, viewportHeight);
        return rightRailCount == null ? null : pointAboveRightRailCount(rightRailCount);
    }

    @Nullable
    private TreeLine inferredCommentTriggerFromActionBar(List<TreeLine> lines, int viewportWidth) {
        List<TreeLine> actionSignals = lines.stream()
                .filter(line -> isInteractionActionSignal(line, viewportWidth))
                .toList();
        if (actionSignals.isEmpty()) {
            return null;
        }
        double actionX = actionSignals.stream()
                .mapToDouble(line -> line.x() + line.w() / 2.0d)
                .max()
                .orElse(0.0d);
        List<TreeLine> cluster = lines.stream()
                .filter(line -> Math.abs((line.x() + line.w() / 2.0d) - actionX) <= 150.0d)
                .filter(line -> line.y() >= 120.0d && line.w() <= 280.0d && line.h() <= 140.0d)
                .toList();
        TreeLine like = firstActionLine(cluster, "点赞", "赞");
        TreeLine collect = firstActionLine(cluster, "收藏");
        TreeLine share = firstActionLine(cluster, "分享");
        if (like == null && collect == null && share == null) {
            return null;
        }

        TreeLine numericBetween = cluster.stream()
                .filter(line -> line.name().matches("^[\\d.]+\\s*([万wW])?$"))
                .filter(line -> like == null || line.y() > like.y())
                .filter(line -> collect == null || line.y() < collect.y())
                .min(Comparator.comparingDouble(TreeLine::y))
                .orElse(null);
        if (numericBetween != null) {
            return new TreeLine(numericBetween.role(), "评论 " + numericBetween.name(), numericBetween.bounds());
        }

        double y;
        if (like != null && collect != null) {
            y = ((like.y() + like.h() / 2.0d) + (collect.y() + collect.h() / 2.0d)) / 2.0d;
        } else if (like != null && share != null) {
            y = (like.y() + like.h() / 2.0d)
                    + ((share.y() + share.h() / 2.0d) - (like.y() + like.h() / 2.0d)) / 3.0d;
        } else {
            return null;
        }
        return new TreeLine("button", "评论", new Bounds(actionX - 36.0d, y - 24.0d, 72.0d, 48.0d));
    }

    private boolean isInteractionActionSignal(TreeLine line, int viewportWidth) {
        String name = line.name();
        return line.x() >= sideActionMinX(viewportWidth)
                && line.y() >= 120.0d
                && line.w() <= 280.0d
                && line.h() <= 140.0d
                && (name.contains("点赞")
                || name.contains("收藏")
                || name.contains("分享")
                || name.matches("^[\\d.]+\\s*([万wW])?$"));
    }

    @Nullable
    private TreeLine firstActionLine(List<TreeLine> lines, String... labels) {
        return lines.stream()
                .filter(line -> {
                    for (String label : labels) {
                        if (line.name().contains(label)) {
                            return true;
                        }
                    }
                    return false;
                })
                .min(Comparator.comparingDouble(TreeLine::y))
                .orElse(null);
    }

    private int scoreCommentTrigger(TreeLine line, int viewportWidth, int viewportHeight) {
        String role = line.role().toLowerCase(Locale.ROOT);
        String name = line.name();
        boolean sideAction = line.x() >= sideActionMinX(viewportWidth);
        int score = 0;
        if ("button".equals(role)) {
            score += 80;
        } else if ("link".equals(role) || "tab".equals(role)) {
            score += 55;
        } else if (sideAction) {
            score += 35;
        } else {
            score += 10;
        }
        if ("评论".equals(name) || name.matches("评论\\s*[\\d.万wW]*")) {
            score += 55;
        } else if (name.contains("查看评论") || name.contains("打开评论")) {
            score += 40;
        } else if (name.contains("评论")) {
            score += 18;
        }
        if (sideAction) score += 25;
        if (line.y() >= 120.0d && line.y() <= Math.max(240.0d, viewportHeight - 80.0d)) score += 15;
        if (line.w() <= 220.0d && line.h() <= 100.0d) score += 10;
        return score;
    }

    private List<ClickPoint> commentTriggerClickPoints(BrowserObservation observed, ClickPoint center) {
        List<ClickPoint> points = new ArrayList<>();
        addDistinctPoint(points, center.x(), center.y());
        TreeLine rightRailCount = inferredCommentCountFromRightRail(
                parseTreeLines(observed.tree()), observed.viewportWidth(), observed.viewportHeight());
        if (rightRailCount != null) {
            ClickPoint icon = pointAboveRightRailCount(rightRailCount);
            addDistinctPoint(points, icon.x(), icon.y());
        }
        return points;
    }

    private void addDistinctPoint(List<ClickPoint> points, double x, double y) {
        boolean exists = points.stream()
                .anyMatch(point -> Math.abs(point.x() - x) < 2.0d && Math.abs(point.y() - y) < 2.0d);
        if (!exists) {
            points.add(new ClickPoint(x, y));
        }
    }

    private int sideActionMinX(int viewportWidth) {
        int width = Math.max(1, viewportWidth);
        int min = Math.max(160, (int) Math.round(width * 0.45d));
        int max = Math.max(min, width - 80);
        int preferred = (int) Math.round(width * 0.55d);
        return Math.max(min, Math.min(preferred, max));
    }

    private boolean isCommentPanelHeader(String name) {
        String trimmed = name == null ? "" : name.replaceAll("\\s+", "");
        return trimmed.matches("^全部评论[（(]?\\d*[）)]?$")
                || trimmed.matches("^\\d+条评论$")
                || trimmed.equals("评论区")
                || trimmed.equals("评论详情")
                || trimmed.equals("回复详情")
                || trimmed.equals("相关回复");
    }

    private boolean isCommentPanelInput(String name) {
        String trimmed = name == null ? "" : name;
        return trimmed.contains("说点什么")
                || trimmed.contains("写评论")
                || trimmed.contains("发表评论")
                || trimmed.contains("友好评论")
                || trimmed.contains("善意评论");
    }

    private boolean isProbablyLongCommentText(TreeLine line) {
        String name = line.name();
        return name.length() > 32
                && line.w() > 180.0d
                && !("button".equalsIgnoreCase(line.role())
                || "tab".equalsIgnoreCase(line.role())
                || "link".equalsIgnoreCase(line.role()));
    }

    private String commentTriggerSignals(BrowserObservation observed) {
        List<TreeLine> lines = parseTreeLines(observed.tree());
        long explicit = lines.stream()
                .filter(line -> line.name().contains("评论"))
                .filter(line -> !isCommentPanelHeader(line.name()))
                .count();
        long actionSignals = lines.stream()
                .filter(line -> isInteractionActionSignal(line, observed.viewportWidth()))
                .count();
        return "commentsOpen=" + looksLikeCommentsOpen(observed.tree())
                + ", explicitCommentSignals=" + explicit
                + ", actionSignals=" + actionSignals
                + ", viewport=" + observed.viewportWidth() + "x" + observed.viewportHeight()
                + ", treeChars=" + (observed.tree() == null ? 0 : observed.tree().length());
    }

    @Nullable
    private TreeLine inferredCommentCountFromRightRail(List<TreeLine> lines, int viewportWidth, int viewportHeight) {
        List<TreeLine> numeric = rightRailNumericLines(lines, viewportWidth, viewportHeight);
        if (numeric.size() >= 2) {
            return numeric.get(1);
        }
        TreeLine like = firstNearbyLabel(lines, viewportWidth, "点赞", "赞");
        TreeLine collect = firstNearbyLabel(lines, viewportWidth, "收藏");
        if (like == null || collect == null) {
            return null;
        }
        return numeric.stream()
                .filter(line -> line.y() > like.y())
                .filter(line -> line.y() < collect.y())
                .findFirst()
                .orElse(null);
    }

    private List<TreeLine> rightRailNumericLines(List<TreeLine> lines, int viewportWidth, int viewportHeight) {
        if (lines == null || lines.isEmpty()) {
            return List.of();
        }
        return lines.stream()
                .filter(line -> line.x() >= sideActionMinX(viewportWidth))
                .filter(line -> line.y() >= 120.0d && line.y() <= Math.max(260.0d, viewportHeight * 0.92d))
                .filter(line -> line.w() <= 140.0d && line.h() <= 90.0d)
                .filter(line -> line.name().replaceAll("\\s+", "").matches("^\\d+(?:\\.\\d+)?([万wWkK千])?$"))
                .sorted(Comparator.comparingDouble(TreeLine::y))
                .toList();
    }

    private ClickPoint pointAboveRightRailCount(TreeLine countLine) {
        double x = countLine.x() + countLine.w() / 2.0d;
        double gap = Math.max(32.0d, Math.min(58.0d, countLine.h() * 2.2d));
        double y = Math.max(120.0d, countLine.y() - gap);
        return new ClickPoint(x, y);
    }

    @Nullable
    private TreeLine firstNearbyLabel(List<TreeLine> lines, int viewportWidth, String... labels) {
        return lines.stream()
                .filter(line -> line.x() >= sideActionMinX(viewportWidth))
                .filter(line -> {
                    for (String label : labels) {
                        if (line.name().contains(label)) {
                            return true;
                        }
                    }
                    return false;
                })
                .min(Comparator.comparingDouble(TreeLine::y))
                .orElse(null);
    }

    boolean searchVerified(BrowserObservation observed, String keyword) {
        if (observed == null || !observed.ok()) {
            return false;
        }
        String url = observed.url() == null ? "" : observed.url().toLowerCase(Locale.ROOT);
        if (url.contains("/aisearch")) {
            return false;
        }
        String tree = observed.tree() == null ? "" : observed.tree();
        String key = keyword == null ? "" : keyword.trim().toLowerCase(Locale.ROOT);
        if (key.isBlank()) {
            return false;
        }
        String lowerTree = tree.toLowerCase(Locale.ROOT);
        boolean searchUrlVisible = searchUrlContainsKeyword(observed.url(), keyword);
        boolean keywordVisible = lowerTree.contains(key);
        List<VideoResultTarget> targets = visualOrderVideoTargets(tree, observed.viewportWidth(), observed.viewportHeight());
        boolean resultSignal = tree.contains("筛选")
                || tree.contains("最多点赞")
                || tree.contains("综合排序")
                || tree.contains("播放")
                || lowerTree.contains("#" + key)
                || !targets.isEmpty();
        boolean emptyShell = tree.contains("搜索你感兴趣的内容")
                && !tree.contains("筛选")
                && !tree.contains("播放")
                && !tree.contains("最多点赞")
                && targets.isEmpty();
        return searchUrlVisible && keywordVisible && resultSignal && !emptyShell;
    }

    boolean canReuseSearchResultsForSorting(BrowserObservation observed, String keyword) {
        if (!searchVerified(observed, keyword)) {
            return false;
        }
        String url = observed.url() == null ? "" : observed.url().toLowerCase(Locale.ROOT);
        if (url.contains("modal_id=") || url.contains("aweme_id=") || url.contains("/video/")) {
            return false;
        }
        return !looksLikeCommentsOpen(observed.tree());
    }

    boolean mostLikedSortVerified(BrowserObservation observed, String keyword) {
        if (!searchVerified(observed, keyword)) {
            return false;
        }
        String tree = observed.tree() == null ? "" : observed.tree();
        String normalizedTree = tree.replaceAll("\\s+", "");
        String lowerUrl = observed.url() == null ? "" : observed.url().toLowerCase(Locale.ROOT);
        boolean selectedSignal = DOUYIN_LIKE_SORT_LABELS.stream()
                .map(label -> label.replaceAll("\\s+", ""))
                .anyMatch(label -> normalizedTree.contains(label + "已选")
                        || normalizedTree.contains(label + "选中")
                        || normalizedTree.contains("当前排序" + label)
                        || normalizedTree.contains("排序" + label)
                        || normalizedTree.contains("按" + label));
        boolean urlSignal = lowerUrl.contains("like")
                || lowerUrl.contains("digg")
                || lowerUrl.contains("sort")
                || lowerUrl.contains("order");
        List<VideoResultTarget> visualTargets = visualOrderVideoTargets(tree, observed.viewportWidth(), observed.viewportHeight());
        boolean resultEvidence = firstVisibleRowLooksLikeMostLiked(visualTargets);
        boolean explicitSortSignal = selectedSignal || urlSignal;
        if (visualTargets.size() >= 2) {
            return explicitSortSignal && resultEvidence;
        }
        return explicitSortSignal;
    }

    boolean sortVerified(BrowserObservation observed, String keyword, DouyinSortSpec sort) {
        if (sort.isMostLiked()) {
            return mostLikedSortVerified(observed, keyword);
        }
        if (!searchVerified(observed, keyword)) {
            return false;
        }
        return strictSortSelectedSignal(observed.tree(), sort.labels()) || urlHasLatestSortSignal(observed.url());
    }

    private boolean mostLikedSortVerifiedByResultEvidence(BrowserObservation observed, String keyword) {
        if (!searchVerified(observed, keyword)) {
            return false;
        }
        List<VideoResultTarget> visualTargets = visualOrderVideoTargets(
                observed.tree(), observed.viewportWidth(), observed.viewportHeight());
        return visualTargets.size() >= 3 && firstVisibleRowLooksLikeMostLiked(visualTargets);
    }

    private boolean firstVisibleRowLooksLikeMostLiked(List<VideoResultTarget> visualTargets) {
        if (visualTargets == null || visualTargets.size() < 2) {
            return false;
        }
        List<VideoResultTarget> firstRun = firstVisibleLikeRun(visualTargets);
        if (firstRun.size() < 2) {
            return false;
        }
        return likesAreNonIncreasing(firstRun);
    }

    private List<VideoResultTarget> firstVisibleLikeRun(List<VideoResultTarget> visualTargets) {
        List<VideoResultTarget> likedTargets = visualTargets.stream()
                .filter(target -> target.likeCount() > 0.0d)
                .toList();
        if (likedTargets.size() < 2) {
            return List.of();
        }
        List<VideoResultTarget> run = new ArrayList<>();
        VideoResultTarget first = likedTargets.getFirst();
        run.add(first);
        double firstTop = cardVisualTop(first);
        double previousLeft = cardVisualLeft(first);
        for (int i = 1; i < likedTargets.size() && run.size() < 6; i++) {
            VideoResultTarget current = likedTargets.get(i);
            double currentLeft = cardVisualLeft(current);
            double currentTop = cardVisualTop(current);
            boolean continuesLeftToRight = currentLeft + 24.0d >= previousLeft;
            boolean sameVisualBand = Math.abs(currentTop - firstTop) <= sortEvidenceTopTolerance(first, current);
            boolean singleColumnContinuation = Math.abs(currentLeft - previousLeft) <= 90.0d
                    && Math.abs(currentTop - firstTop) <= 900.0d;
            if (!(continuesLeftToRight && sameVisualBand) && !singleColumnContinuation) {
                break;
            }
            run.add(current);
            previousLeft = currentLeft;
        }
        return run;
    }

    private double sortEvidenceTopTolerance(VideoResultTarget first, VideoResultTarget current) {
        double firstHeight = Math.max(first.clickLine().h(), first.evidenceLine().h());
        double currentHeight = Math.max(current.clickLine().h(), current.evidenceLine().h());
        double height = Math.max(firstHeight, currentHeight);
        return Math.max(96.0d, Math.min(220.0d, height * 2.4d));
    }

    private boolean likesAreNonIncreasing(List<VideoResultTarget> targets) {
        for (int i = 1; i < targets.size(); i++) {
            double previous = targets.get(i - 1).likeCount();
            double current = targets.get(i).likeCount();
            if (previous + 1.0d < current) {
                return false;
            }
        }
        return true;
    }

    private boolean firstTwoVisibleResultsLookMostLiked(List<VideoResultTarget> visualTargets) {
        List<VideoResultTarget> likedTargets = visualTargets.stream()
                .filter(target -> target.likeCount() > 0.0d)
                .limit(2)
                .toList();
        return likedTargets.size() == 2 && likesAreNonIncreasing(likedTargets);
    }

    private boolean canRankVisibleResultsByLikes(List<VideoResultTarget> visualTargets) {
        if (visualTargets == null) {
            return false;
        }
        return visualTargets.stream()
                .filter(target -> target.likeCount() > 0.0d)
                .count() >= 2;
    }

    private List<VideoResultTarget> rankMostLikedTargets(List<VideoResultTarget> targets) {
        if (targets == null || targets.isEmpty()) {
            return List.of();
        }
        return targets.stream()
                .sorted(Comparator
                        .comparingDouble(VideoResultTarget::likeCount).reversed()
                        .thenComparing(Comparator.comparingInt(VideoResultTarget::score).reversed())
                        .thenComparingDouble(this::cardVisualTop)
                        .thenComparingDouble(this::cardVisualLeft))
                .toList();
    }

    private String searchSignals(BrowserObservation observed, String keyword) {
        if (observed == null) {
            return "no_observation";
        }
        String tree = observed.tree() == null ? "" : observed.tree();
        String lowerTree = tree.toLowerCase(Locale.ROOT);
        String key = keyword == null ? "" : keyword.trim().toLowerCase(Locale.ROOT);
        return "keywordVisible=" + (!key.isBlank() && lowerTree.contains(key))
                + ", searchUrl=" + searchUrlContainsKeyword(observed.url(), keyword)
                + ", filterVisible=" + tree.contains("筛选")
                + ", mostLikedVisible=" + tree.contains("最多点赞")
                + ", playVisible=" + tree.contains("播放")
                + ", treeChars=" + tree.length();
    }

    private boolean searchUrlContainsKeyword(@Nullable String url, String keyword) {
        String key = keyword == null ? "" : keyword.trim().toLowerCase(Locale.ROOT);
        if (url == null || url.isBlank() || key.isBlank()) {
            return false;
        }
        String lowerUrl = url.toLowerCase(Locale.ROOT);
        if (searchPathContains(lowerUrl, key)) {
            return true;
        }
        String encoded = URLEncoder.encode(keyword.trim(), StandardCharsets.UTF_8)
                .replace("+", "%20")
                .toLowerCase(Locale.ROOT);
        if (searchPathContains(lowerUrl, encoded)) {
            return true;
        }
        try {
            return searchPathContains(URLDecoder.decode(url, StandardCharsets.UTF_8).toLowerCase(Locale.ROOT), key);
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    private boolean searchPathContains(String url, String key) {
        return url.contains("/search/" + key) || url.contains("/jingxuan/search/" + key);
    }

    private String sortSignals(BrowserObservation observed, String keyword) {
        return sortSignals(observed, keyword, DouyinSortSpec.comprehensive());
    }

    private String sortSignals(BrowserObservation observed, String keyword, DouyinSortSpec sort) {
        if (observed == null) {
            return "no_observation";
        }
        List<VideoResultTarget> targets = visualOrderVideoTargets(
                observed.tree(), observed.viewportWidth(), observed.viewportHeight());
        String topTargets = targets.stream()
                .limit(4)
                .map(VideoResultTarget::debugSummary)
                .toList()
                .toString();
        String tree = observed.tree() == null ? "" : observed.tree();
        return searchSignals(observed, keyword)
                + ", requestedSort=" + sort.code()
                + ", sortSelected=" + sortSelectedSignal(tree, sort.labels())
                + ", urlSortSignal=" + urlHasSortSignal(observed.url())
                + ", urlLatestSortSignal=" + urlHasLatestSortSignal(observed.url())
                + ", resultEvidence=" + firstVisibleRowLooksLikeMostLiked(targets)
                + ", acceptedAfterClick=" + sortAcceptedAfterExplicitSelection(observed, keyword, sort)
                + ", targetCount=" + targets.size()
                + ", topTargets=" + topTargets;
    }

    private boolean mostLikedSelectedSignal(String tree) {
        return sortSelectedSignal(tree, DOUYIN_LIKE_SORT_LABELS);
    }

    private boolean sortSelectedSignal(String tree, List<String> labels) {
        String normalizedTree = tree == null ? "" : tree.replaceAll("\\s+", "");
        return labels.stream()
                .map(label -> label.replaceAll("\\s+", ""))
                .anyMatch(label -> normalizedTree.contains(label + "已选")
                        || normalizedTree.contains(label + "选中")
                        || normalizedTree.contains("当前排序" + label)
                        || normalizedTree.contains("排序" + label)
                        || normalizedTree.contains("按" + label));
    }

    private boolean strictSortSelectedSignal(String tree, List<String> labels) {
        String normalizedTree = tree == null ? "" : tree.replaceAll("\\s+", "");
        return labels.stream()
                .map(label -> label.replaceAll("\\s+", ""))
                .anyMatch(label -> normalizedTree.contains(label + "已选")
                        || normalizedTree.contains(label + "已选择")
                        || normalizedTree.contains(label + "选中")
                        || normalizedTree.contains("当前排序" + label)
                        || normalizedTree.contains("当前选择" + label));
    }

    private boolean urlHasSortSignal(String url) {
        String lowerUrl = url == null ? "" : url.toLowerCase(Locale.ROOT);
        return lowerUrl.contains("like")
                || lowerUrl.contains("digg")
                || lowerUrl.contains("sort")
                || lowerUrl.contains("order");
    }

    private boolean urlHasLatestSortSignal(String url) {
        String lowerUrl = url == null ? "" : url.toLowerCase(Locale.ROOT);
        return lowerUrl.contains("publish")
                || lowerUrl.contains("time")
                || lowerUrl.contains("latest")
                || lowerUrl.contains("create")
                || lowerUrl.contains("newest");
    }

    private String treeExcerpt(String tree) {
        if (tree == null || tree.isBlank()) {
            return "";
        }
        String normalized = tree.replaceAll("\\s+", " ").trim();
        return normalized.length() <= 600 ? normalized : normalized.substring(0, 600) + "...";
    }

    @Nullable
    private ClickPoint findPointByAnyText(String tree, String... needles) {
        if (tree == null || tree.isBlank()) {
            return null;
        }
        for (String line : tree.split("\\R")) {
            for (String needle : needles) {
                if (needle != null && !needle.isBlank() && line.contains(needle)) {
                    ClickPoint point = ClickPoint.fromTreeLine(line);
                    if (point != null) {
                        return point;
                    }
                }
            }
        }
        return null;
    }

    @Nullable
    private ClickPoint findProfileActionPoint(BrowserObservation obs, String... needles) {
        if (obs == null || obs.tree() == null || obs.tree().isBlank()) {
            return null;
        }
        int viewportW = Math.max(1, obs.viewportWidth());
        int viewportH = Math.max(1, obs.viewportHeight());
        double minContentX = Math.max(180.0d, viewportW * 0.16d);
        return parseTreeLines(obs.tree()).stream()
                .filter(line -> matchesProfileActionNeedle(line, needles))
                .filter(line -> line.x() >= minContentX)
                .filter(line -> line.y() >= 70.0d && line.y() <= Math.max(360.0d, viewportH * 0.72d))
                .filter(line -> !line.role().equalsIgnoreCase("Searchbox"))
                .max(Comparator.comparingInt(line -> scoreProfileActionLine(line, needles)))
                .map(TreeLine::point)
                .orElse(null);
    }

    private boolean matchesProfileActionNeedle(TreeLine line, String... needles) {
        if (line == null || line.name() == null || line.name().isBlank()) {
            return false;
        }
        String compactName = line.name().replaceAll("\\s+", "");
        String lowerName = compactName.toLowerCase(Locale.ROOT);
        if (lowerName.contains("下载") || lowerName.contains("客户端")
                || lowerName.contains("桌面快捷") || lowerName.contains("download")) {
            return false;
        }
        for (String needle : needles) {
            String compactNeedle = needle == null ? "" : needle.replaceAll("\\s+", "");
            if (!compactNeedle.isBlank() && compactName.equalsIgnoreCase(compactNeedle)) {
                return true;
            }
        }
        return false;
    }

    private int scoreProfileActionLine(TreeLine line, String... needles) {
        String role = line.role().toLowerCase(Locale.ROOT);
        String name = line.name().replaceAll("\\s+", "");
        int score = 0;
        if (name.contains("下载") || name.contains("客户端") || name.contains("桌面快捷")
                || name.toLowerCase(Locale.ROOT).contains("download")) {
            score -= 1_000;
        }
        if (role.equals("button")) score += 90;
        if (role.equals("text") || role.equals("statictext")) score += 30;
        for (String needle : needles) {
            if (needle != null && !needle.isBlank() && name.equals(needle.replaceAll("\\s+", ""))) {
                score += 80;
            }
        }
        if (line.w() >= 42.0d && line.w() <= 180.0d && line.h() >= 22.0d && line.h() <= 64.0d) {
            score += 50;
        }
        if (name.contains("已关注") || name.contains("互相关注")) {
            score += 60;
        }
        return score;
    }

    @Nullable
    ClickPoint findFilterTriggerPoint(String tree) {
        TreeLine line = findFilterTriggerLine(tree);
        return line == null ? null : line.point();
    }

    @Nullable
    TreeLine findFilterTriggerLine(String tree) {
        return parseTreeLines(tree).stream()
                .filter(line -> containsAnyLabel(line.name(), List.of("筛选")))
                .filter(line -> !containsAnyLabel(line.name(), DOUYIN_SORT_PANEL_LABELS))
                .max(Comparator.comparingInt(this::scoreFilterTrigger))
                .orElse(null);
    }

    List<ClickPoint> filterTriggerPoints(TreeLine line, int viewportWidth, int viewportHeight) {
        if (line == null) {
            return List.of();
        }
        List<ClickPoint> points = new ArrayList<>();
        addPointWithinViewport(points, line.point(), viewportWidth, viewportHeight);
        addPointWithinViewport(points, new ClickPoint(
                line.x() + Math.max(8.0d, Math.min(line.w() * 0.78d, line.w() - 4.0d)),
                line.y() + line.h() / 2.0d), viewportWidth, viewportHeight);
        addPointWithinViewport(points, new ClickPoint(
                line.x() + line.w() / 2.0d,
                line.y() + Math.max(6.0d, Math.min(line.h() * 0.82d, line.h() + 10.0d))),
                viewportWidth, viewportHeight);
        addPointWithinViewport(points, new ClickPoint(
                line.x() + line.w() + Math.min(24.0d, Math.max(8.0d, line.w() * 0.35d)),
                line.y() + line.h() / 2.0d), viewportWidth, viewportHeight);
        return points;
    }

    private void addPointWithinViewport(List<ClickPoint> points, ClickPoint point, int viewportWidth, int viewportHeight) {
        if (point == null) {
            return;
        }
        int w = Math.max(1, viewportWidth);
        int h = Math.max(1, viewportHeight);
        if (point.x() < 0.0d || point.y() < 0.0d || point.x() >= w || point.y() >= h) {
            return;
        }
        boolean duplicate = points.stream()
                .anyMatch(existing -> Math.abs(existing.x() - point.x()) < 2.0d
                        && Math.abs(existing.y() - point.y()) < 2.0d);
        if (!duplicate) {
            points.add(point);
        }
    }

    @Nullable
    ClickPoint findSortOptionPoint(String tree, List<String> labels) {
        return parseTreeLines(tree).stream()
                .filter(line -> containsAnyLabel(line.name(), labels))
                .filter(line -> !isProbablySearchResultSortText(line))
                .filter(line -> !isAmbiguousCombinedSortLine(line.name(), labels))
                .map(line -> new SortOption(line, matchedLabel(line.name(), labels), scoreSortOption(line, labels)))
                .filter(option -> option.score() >= 55)
                .max(Comparator.comparingInt(SortOption::score))
                .map(option -> option.line().point())
                .orElse(null);
    }

    private int scoreFilterTrigger(TreeLine line) {
        String role = line.role().toLowerCase(Locale.ROOT);
        int score = 0;
        if ("button".equals(role) || "menuitem".equals(role)) score += 70;
        else if ("link".equals(role) || "tab".equals(role)) score += 50;
        else if ("generic".equals(role) || "text".equals(role) || "statictext".equals(role)) score += 30;
        else score += 10;
        if ("筛选".equals(line.name())) score += 60;
        else if (line.name().contains("筛选")) score += 35;
        if (line.y() <= 180) score += 35;
        else if (line.y() <= 320) score += 10;
        else score -= 25;
        if (line.x() >= 160) score += 15;
        if (line.w() >= 20 && line.w() <= 180 && line.h() >= 14 && line.h() <= 90) score += 15;
        return score;
    }

    private int scoreSortOption(TreeLine line, List<String> labels) {
        String role = line.role().toLowerCase(Locale.ROOT);
        int score = 0;
        if ("button".equals(role) || "menuitem".equals(role) || "option".equals(role)) score += 70;
        else if ("generic".equals(role) || "text".equals(role) || "statictext".equals(role)) score += 45;
        else score += 15;
        if (labels.stream().anyMatch(label -> label.equals(line.name()))) score += 45;
        else if (containsAnyLabel(line.name(), labels)) score += 30;
        if (line.y() <= 420) score += 20;
        if (line.x() >= 150) score += 10;
        if (line.w() >= 20 && line.w() <= 260 && line.h() >= 14 && line.h() <= 90) score += 10;
        return score;
    }

    private boolean isProbablySearchResultSortText(TreeLine line) {
        String role = line.role().toLowerCase(Locale.ROOT);
        if ("article".equals(role) || "link".equals(role)) {
            return true;
        }
        String name = line.name();
        return name.length() > 40
                || name.contains("点赞最多的")
                || name.contains("获得最多点赞")
                || name.contains("点赞量最高")
                || name.contains("最新发布的")
                || name.contains("发布时间最新");
    }

    private boolean isAmbiguousCombinedSortLine(String name, List<String> targetLabels) {
        if (name == null || name.isBlank()) {
            return false;
        }
        String normalized = name.replaceAll("\\s+", "");
        boolean exactTarget = targetLabels.stream()
                .map(label -> label == null ? "" : label.replaceAll("\\s+", ""))
                .filter(label -> !label.isBlank())
                .anyMatch(normalized::equals);
        if (exactTarget || strictSortSelectedSignal(name, targetLabels)) {
            return false;
        }
        long matchedSortLabels = DOUYIN_SORT_PANEL_LABELS.stream()
                .map(label -> label == null ? "" : label.replaceAll("\\s+", ""))
                .filter(label -> !label.isBlank())
                .filter(normalized::contains)
                .distinct()
                .count();
        return matchedSortLabels >= 2;
    }

    private boolean filterPanelLooksOpen(String tree) {
        String normalized = tree == null ? "" : tree;
        return containsAnyLabel(normalized, DOUYIN_SORT_PANEL_LABELS)
                || normalized.contains("排序依据")
                || normalized.contains("综合排序")
                || normalized.contains("最新发布")
                || normalized.contains("发布时间")
                || normalized.contains("全部时间")
                || normalized.contains("一周内")
                || normalized.contains("半年内")
                || normalized.contains("筛选条件");
    }

    private boolean containsAnyLabel(String value, List<String> labels) {
        if (value == null || value.isBlank()) {
            return false;
        }
        String normalized = value.replaceAll("\\s+", "");
        return labels.stream()
                .map(label -> label == null ? "" : label.replaceAll("\\s+", ""))
                .filter(label -> !label.isBlank())
                .anyMatch(normalized::contains);
    }

    private String matchedLabel(String value, List<String> labels) {
        if (value == null) {
            return labels.isEmpty() ? "" : labels.getFirst();
        }
        String normalized = value.replaceAll("\\s+", "");
        return labels.stream()
                .map(label -> label == null ? "" : label.replaceAll("\\s+", ""))
                .filter(label -> !label.isBlank())
                .filter(normalized::contains)
                .findFirst()
                .orElse(labels.isEmpty() ? "" : labels.getFirst());
    }

    private List<TreeLine> parseTreeLines(String tree) {
        if (tree == null || tree.isBlank()) {
            return List.of();
        }
        List<TreeLine> lines = new ArrayList<>();
        for (String raw : tree.split("\\R")) {
            Matcher matcher = TREE_LINE_PATTERN.matcher(raw);
            if (!matcher.matches()) {
                continue;
            }
            if (matcher.group(3) == null) {
                continue;
            }
            Bounds bounds = Bounds.fromTreeLine(raw);
            if (bounds == null) {
                continue;
            }
            lines.add(new TreeLine(
                    matcher.group(1),
                    matcher.group(2) == null ? "" : matcher.group(2).trim(),
                    bounds));
        }
        return lines;
    }

    @Nullable
    ClickPoint findDouyinSearchBoxPoint(String tree) {
        if (tree == null || tree.isBlank()) {
            return null;
        }
        List<ScoredPoint> candidates = new ArrayList<>();
        for (String line : tree.split("\\R")) {
            String trimmed = line == null ? "" : line.trim();
            if (looksLikeAiSearchLine(trimmed) || !looksLikeSearchInputLine(trimmed)) {
                continue;
            }
            ClickPoint point = ClickPoint.fromTreeLine(trimmed);
            Bounds bounds = Bounds.fromTreeLine(trimmed);
            if (point == null || bounds == null) {
                continue;
            }
            if (trimmed.startsWith("Button")
                    && bounds.width() < 140
                    && !trimmed.contains("搜索你感兴趣的内容")
                    && !trimmed.contains("搜索视频")) {
                continue;
            }
            int score = 0;
            if (trimmed.startsWith("Searchbox") || trimmed.startsWith("Textbox")) score += 80;
            if (trimmed.startsWith("Combobox")) score += 75;
            if (trimmed.startsWith("Generic")) score += 50;
            if (trimmed.contains("搜索你感兴趣的内容")) score += 60;
            if (trimmed.contains("搜索视频") || trimmed.contains("搜索")) score += 20;
            if (bounds.y() <= 140) score += 30;
            if (bounds.width() >= 140) score += 20;
            if (bounds.x() >= 160) score += 10;
            candidates.add(new ScoredPoint(point, score));
        }
        return candidates.stream()
                .max(java.util.Comparator.comparingInt(ScoredPoint::score))
                .map(ScoredPoint::point)
                .orElse(null);
    }

    private boolean looksLikeSearchInputLine(String line) {
        if (line == null || line.isBlank()) {
            return false;
        }
        String lower = line.toLowerCase(Locale.ROOT);
        boolean searchNamed = looksLikeSearchInputName(line) || lower.contains("search");
        if (!searchNamed) {
            return false;
        }
        return line.startsWith("Searchbox")
                || line.startsWith("Textbox")
                || line.startsWith("Combobox")
                || line.startsWith("Generic")
                || line.startsWith("Button");
    }

    private boolean looksLikeSearchInputName(String name) {
        if (name == null || name.isBlank()) {
            return false;
        }
        String lower = name.toLowerCase(Locale.ROOT);
        return name.contains("搜索你感兴趣的内容")
                || name.contains("搜索视频")
                || name.contains("搜索")
                || lower.contains("search");
    }

    private boolean looksLikeAiSearchLine(String line) {
        if (line == null) {
            return false;
        }
        String normalized = line.replaceAll("\\s+", "");
        String lower = normalized.toLowerCase(Locale.ROOT);
        return normalized.contains("AI搜索")
                || normalized.contains("智能搜索")
                || normalized.contains("问AI")
                || lower.contains("aisearch")
                || lower.contains("askai");
    }

    List<ClickPoint> findVideoCandidatePoints(String tree, int viewportWidth, int viewportHeight) {
        return visualOrderVideoTargets(tree, viewportWidth, viewportHeight).stream()
                .map(target -> target.clickLine().point())
                .toList();
    }

    List<VideoResultTarget> rankedVideoTargets(String tree, int viewportWidth, int viewportHeight) {
        if (tree == null || tree.isBlank()) {
            return List.of();
        }
        return videoTargets(tree, viewportWidth, viewportHeight).stream()
                .sorted(Comparator
                        .comparingDouble(VideoResultTarget::likeCount).reversed()
                        .thenComparing(Comparator.comparingInt(VideoResultTarget::score).reversed())
                        .thenComparingDouble(target -> target.evidenceLine().y())
                        .thenComparingDouble(target -> target.evidenceLine().x()))
                .toList();
    }

    List<VideoResultTarget> visualOrderVideoTargets(String tree, int viewportWidth, int viewportHeight) {
        if (tree == null || tree.isBlank()) {
            return List.of();
        }
        return orderVideoTargetsByRows(videoTargets(tree, viewportWidth, viewportHeight));
    }

    private double cardVisualTop(VideoResultTarget target) {
        return Math.min(target.clickLine().y(), target.evidenceLine().y());
    }

    private double cardVisualLeft(VideoResultTarget target) {
        return Math.min(target.clickLine().x(), target.evidenceLine().x());
    }

    private List<VideoResultTarget> orderVideoTargetsByRows(List<VideoResultTarget> targets) {
        if (targets == null || targets.isEmpty()) {
            return List.of();
        }
        List<VideoResultTarget> sorted = targets.stream()
                .sorted(Comparator
                        .comparingDouble(this::cardVisualTop)
                        .thenComparingDouble(this::cardVisualLeft)
                        .thenComparing(Comparator.comparingInt(VideoResultTarget::score).reversed()))
                .toList();
        List<List<VideoResultTarget>> rows = new ArrayList<>();
        for (VideoResultTarget target : sorted) {
            List<VideoResultTarget> row = rows.isEmpty() ? null : rows.getLast();
            double threshold = rowClusterThreshold(target, row);
            if (row == null || Math.abs(cardVisualTop(target) - medianRowTop(row)) > threshold) {
                List<VideoResultTarget> next = new ArrayList<>();
                next.add(target);
                rows.add(next);
            } else {
                row.add(target);
            }
        }
        List<VideoResultTarget> ordered = new ArrayList<>();
        for (List<VideoResultTarget> row : rows) {
            row.sort(Comparator
                    .comparingDouble(this::cardVisualLeft)
                    .thenComparingDouble(this::cardVisualTop)
                    .thenComparing(Comparator.comparingInt(VideoResultTarget::score).reversed()));
            ordered.addAll(row);
        }
        return ordered;
    }

    private double rowClusterThreshold(VideoResultTarget target, @Nullable List<VideoResultTarget> row) {
        List<Double> heights = new ArrayList<>();
        heights.add(Math.max(target.clickLine().h(), target.evidenceLine().h()));
        if (row != null) {
            for (VideoResultTarget item : row) {
                heights.add(Math.max(item.clickLine().h(), item.evidenceLine().h()));
            }
        }
        heights = heights.stream()
                .filter(value -> Double.isFinite(value) && value > 0.0d)
                .sorted()
                .toList();
        double medianHeight = heights.isEmpty() ? 180.0d : heights.get(heights.size() / 2);
        return Math.max(28.0d, Math.min(110.0d, medianHeight * 0.45d));
    }

    private double medianRowTop(List<VideoResultTarget> row) {
        if (row == null || row.isEmpty()) {
            return 0.0d;
        }
        List<Double> tops = row.stream()
                .map(this::cardVisualTop)
                .sorted()
                .toList();
        return tops.get(tops.size() / 2);
    }

    private List<VideoResultTarget> videoTargets(String tree, int viewportWidth, int viewportHeight) {
        List<TreeLine> lines = parseTreeLines(tree);
        List<VideoResultTarget> sorted = lines.stream()
                .filter(line -> isVideoResultCandidate(line, lines, viewportWidth, viewportHeight))
                .map(line -> new VideoResultTarget(
                        bestClickableLineForVideoResult(line, lines),
                        line,
                        titleForVideoResult(line, lines),
                        nearbyLikeCount(line, lines),
                        scoreVideoResult(line, lines, viewportWidth, viewportHeight),
                        null,
                        "a11y_fallback"))
                .filter(target -> target.score() >= 60)
                .sorted(Comparator
                        .comparingInt(VideoResultTarget::score).reversed()
                        .thenComparingDouble(target -> target.evidenceLine().y())
                        .thenComparingDouble(target -> target.evidenceLine().x()))
                .toList();
        List<VideoResultTarget> unique = new ArrayList<>();
        for (VideoResultTarget target : sorted) {
            boolean duplicate = unique.stream()
                    .anyMatch(existing -> sameVideoResultCard(existing.evidenceLine(), target.evidenceLine())
                            || sameVideoResultCard(existing.clickLine(), target.clickLine()));
            if (!duplicate) {
                unique.add(target);
            }
        }
        return unique;
    }

    private boolean isVideoResultCandidate(TreeLine line,
                                           List<TreeLine> lines,
                                           int viewportWidth,
                                           int viewportHeight) {
        String lower = line.name().toLowerCase(Locale.ROOT);
        if (line.x() >= Math.max(260.0d, viewportWidth - 8.0d)) {
            return false;
        }
        if (line.y() < 110.0d) {
            return false;
        }
        if (line.w() > Math.max(520.0d, viewportWidth * 0.64d)
                || line.h() > Math.max(560.0d, viewportHeight * 0.78d)) {
            return false;
        }
        if (line.name().contains("搜索") || line.name().contains("筛选") || looksLikeAiSearchLine(line.name())) {
            return false;
        }
        if (isProbablyRelatedSearchSuggestion(line)) {
            return false;
        }
        String role = line.role().toLowerCase(Locale.ROOT);
        boolean supportedRole = role.equals("article")
                || role.equals("link")
                || role.equals("image")
                || role.equals("img")
                || role.equals("generic")
                || role.equals("text")
                || role.equals("statictext");
        if (!supportedRole) {
            return false;
        }
        if (line.y() > Math.max(viewportHeight + 2_400.0d, viewportHeight * 5.0d)) {
            return false;
        }
        if (role.equals("image") || role.equals("img")) {
            return line.w() >= 120.0d
                    && line.h() >= 120.0d
                    && (nearbyLikeCount(line, lines) > 0 || !titleForVideoResult(line, lines).isBlank());
        }
        if (line.name().isBlank()
                || line.name().length() > 320
                || isVideoControlText(line.name())
                || isNavOnlyText(line.name())) {
            return false;
        }
        return (line.name().length() >= 10 || looksLikePostTitle(line.name()))
                && line.w() >= 40.0d
                && (lower.contains("openclaw")
                || line.name().contains("#")
                || line.name().contains("龙虾")
                || nearbyLikeCount(line, lines) > 0);
    }

    private boolean isProbablyRelatedSearchSuggestion(TreeLine line) {
        return line.w() <= 180.0d
                && line.h() <= 40.0d
                && (line.name().toLowerCase(Locale.ROOT).contains("openclaw")
                || line.name().contains("龙虾"));
    }

    private double resultLeftX(int viewportWidth) {
        // Douyin's left navigation is fixed-width, while the search-result
        // grid can still start around x=190 on wide viewports. A viewport-
        // proportional cutoff (for example 1920 * 0.12 = 230) incorrectly
        // drops the visual first card and makes the second card look first.
        return Math.max(150.0d, Math.min(180.0d, Math.max(1, viewportWidth) * 0.08d));
    }

    private int scoreVideoResult(TreeLine line, List<TreeLine> lines, int viewportWidth, int viewportHeight) {
        String role = line.role().toLowerCase(Locale.ROOT);
        String name = line.name();
        int score = 0;
        if ("article".equals(role)) score += 95;
        else if ("link".equals(role)) score += 65;
        else if ("image".equals(role) || "img".equals(role)) score += 75;
        else score += 35;
        if (line.x() >= resultLeftX(viewportWidth)) score += 20;
        else score -= 40;
        if (line.y() >= 140.0d && line.y() <= Math.max(520.0d, viewportHeight + 80.0d)) score += 35;
        else if (line.y() > viewportHeight + 1_200.0d) score -= 20;
        if (line.w() >= 160.0d || line.h() >= 120.0d) score += 30;
        if (looksLikePostTitle(name)) score += 25;
        if (titleForVideoResult(line, lines).toLowerCase(Locale.ROOT).contains("openclaw")) score += 25;
        double likeCount = nearbyLikeCount(line, lines);
        if (likeCount > 0) {
            score += 45;
            if (likeCount >= 100_000.0d) score += 25;
            if (likeCount >= 500_000.0d) score += 25;
        }
        if (isLikelyAuthorOrProfileClickSurface(line, line)) score -= 40;
        return score;
    }

    private TreeLine bestClickableLineForVideoResult(TreeLine evidence, List<TreeLine> lines) {
        Optional<TreeLine> cover = bestCoverLineForVideoResult(evidence, lines);
        if (cover.isPresent()) {
            return cover.get();
        }
        return lines.stream()
                .filter(line -> sameVideoResultCard(evidence, line))
                .filter(line -> isVideoCardClickableSurface(line, evidence))
                .max(Comparator.comparingInt(line -> scoreVideoClickSurface(line, evidence)))
                .orElse(evidence);
    }

    private Optional<TreeLine> bestCoverLineForVideoResult(TreeLine evidence, List<TreeLine> lines) {
        double evidenceCenterX = evidence.x() + evidence.w() / 2.0d;
        return lines.stream()
                .filter(line -> line.role().equalsIgnoreCase("image") || line.role().equalsIgnoreCase("img"))
                .filter(line -> line.w() >= 100.0d && line.h() >= 100.0d)
                .filter(line -> isLikelySameVideoColumn(evidenceCenterX, evidence, line))
                .filter(line -> line == evidence || line.y() <= evidence.y() + 48.0d)
                .filter(line -> line.y() + line.h() >= evidence.y() - 380.0d)
                .max(Comparator.<TreeLine>comparingInt(line -> scoreVideoClickSurface(line, evidence))
                        .thenComparingDouble(line -> line.w() * line.h()));
    }

    private boolean isLikelySameVideoColumn(double evidenceCenterX, TreeLine evidence, TreeLine line) {
        double lineCenterX = line.x() + line.w() / 2.0d;
        double maxCenterDistance = Math.max(90.0d, Math.min(190.0d, Math.max(evidence.w(), line.w()) * 0.48d));
        if (Math.abs(lineCenterX - evidenceCenterX) <= maxCenterDistance) {
            return true;
        }
        double overlap = Math.min(evidence.x() + evidence.w(), line.x() + line.w())
                - Math.max(evidence.x(), line.x());
        return overlap > 0.0d && overlap >= Math.min(evidence.w(), line.w()) * 0.30d;
    }

    private boolean isVideoCardClickableSurface(TreeLine line, TreeLine evidence) {
        String role = line.role().toLowerCase(Locale.ROOT);
        if (!(role.equals("article") || role.equals("link") || role.equals("image")
                || role.equals("img") || role.equals("generic") || role.equals("text")
                || role.equals("statictext"))) {
            return false;
        }
        if (line.w() < 40.0d || line.h() < 16.0d) {
            return false;
        }
        String name = line.name();
        boolean imageSurface = role.equals("image") || role.equals("img");
        if ((!imageSurface && isVideoControlText(name)) || isNavOnlyText(name) || isProbablyRelatedSearchSuggestion(line)) {
            return false;
        }
        boolean coverAboveEvidence = imageSurface
                && line.y() + line.h() <= evidence.y() + 24.0d
                && evidence.y() - (line.y() + line.h()) <= 220.0d
                && line.h() >= 120.0d;
        return coverAboveEvidence
                || line.y() >= Math.max(100.0d, evidence.y() - 145.0d)
                && line.y() <= evidence.y() + evidence.h() + 220.0d;
    }

    private int scoreVideoClickSurface(TreeLine line, TreeLine evidence) {
        String role = line.role().toLowerCase(Locale.ROOT);
        int score = 0;
        if ("image".equals(role) || "img".equals(role)) score += 120;
        else if ("article".equals(role) || "link".equals(role)) score += 85;
        else score += 35;
        if (line.w() >= 120.0d && line.h() >= 120.0d) score += 50;
        String name = line.name();
        if (name.contains("点赞") || name.contains("评论") || name.contains("分享")) score -= 55;
        if (isLikelyAuthorOrProfileClickSurface(line, evidence)) score -= 140;
        score += Math.max(0, 50 - (int) Math.round(Math.abs(line.y() - evidence.y()) / 3.0d));
        score += Math.max(0, 30 - (int) Math.round(Math.abs(line.x() - evidence.x()) / 8.0d));
        return score;
    }

    private boolean isLikelyAuthorOrProfileClickSurface(TreeLine line, TreeLine evidence) {
        if (line == evidence) {
            return false;
        }
        String role = line.role().toLowerCase(Locale.ROOT);
        if (!(role.equals("link") || role.equals("button") || role.equals("text") || role.equals("statictext"))) {
            return false;
        }
        String name = line.name().trim();
        if (name.isBlank()) {
            return true;
        }
        if (name.contains("作者") || name.contains("主页") || name.contains("粉丝") || name.contains("获赞")) {
            return true;
        }
        return line.w() <= 180.0d
                && line.h() <= 44.0d
                && name.length() <= 42
                && !looksLikePostTitle(name)
                && parseLikeCount(name) == 0.0d;
    }

    private double nearbyLikeCount(TreeLine line, List<TreeLine> lines) {
        double best = parseLikeCount(line.name());
        for (TreeLine other : lines) {
            if (sameVideoResultCard(line, other)) {
                best = Math.max(best, parseLikeCount(other.name()));
            }
        }
        return best;
    }

    private String titleForVideoResult(TreeLine line, List<TreeLine> lines) {
        return lines.stream()
                .filter(other -> sameVideoResultCard(line, other))
                .map(TreeLine::name)
                .filter(name -> name.length() >= 8)
                .filter(name -> !isVideoControlText(name))
                .filter(name -> !isNavOnlyText(name))
                .filter(name -> !isProbablyCountOnly(name))
                .max(Comparator.comparingInt(name -> {
                    int score = 0;
                    if (looksLikePostTitle(name)) score += 50;
                    if (name.toLowerCase(Locale.ROOT).contains("openclaw")) score += 30;
                    if (name.contains("#") || name.contains("龙虾")) score += 20;
                    score += Math.min(60, name.length());
                    return score;
                }))
                .orElse(line.name());
    }

    private boolean sameVideoResultCard(TreeLine anchor, TreeLine other) {
        return sameVideoCardColumn(anchor, other) && sameVideoCardBand(anchor, other);
    }

    private boolean sameVideoCardColumn(TreeLine anchor, TreeLine other) {
        double overlap = Math.min(anchor.x() + anchor.w(), other.x() + other.w())
                - Math.max(anchor.x(), other.x());
        if (overlap > 0.0d && overlap >= Math.min(anchor.w(), other.w()) * 0.45d) {
            return true;
        }
        double anchorCenter = anchor.x() + anchor.w() / 2.0d;
        double otherCenter = other.x() + other.w() / 2.0d;
        double maxCenterDistance = Math.max(80.0d, Math.min(220.0d, Math.max(anchor.w(), other.w()) * 0.45d));
        if (Math.abs(anchorCenter - otherCenter) <= maxCenterDistance) {
            return true;
        }
        return other.x() >= anchor.x() - 35.0d
                && other.x() <= anchor.x() + Math.min(anchor.w(), 180.0d) + 35.0d;
    }

    private boolean sameVideoCardBand(TreeLine anchor, TreeLine other) {
        double anchorCenter = anchor.y() + anchor.h() / 2.0d;
        double otherCenter = other.y() + other.h() / 2.0d;
        if (Math.abs(anchorCenter - otherCenter) > 260.0d) {
            return false;
        }
        boolean otherCoverImmediatelyAboveAnchor = other.y() + other.h() <= anchor.y() + 24.0d
                && anchor.y() - (other.y() + other.h()) <= 220.0d
                && other.h() >= 120.0d;
        if (otherCoverImmediatelyAboveAnchor) {
            return true;
        }
        return other.y() >= anchor.y() - 160.0d
                && other.y() <= anchor.y() + anchor.h() + 170.0d;
    }

    private double parseLikeCount(String text) {
        if (text == null || text.isBlank()) {
            return 0.0d;
        }
        double best = 0.0d;
        String normalized = text.replaceAll("\\s+", "");
        Matcher prefixLabel = Pattern.compile("(?i)(?:获赞|点赞|赞)[:：]?(\\d+(?:\\.\\d+)?)([万wW])?")
                .matcher(normalized);
        while (prefixLabel.find()) {
            best = Math.max(best, parseLikeValue(prefixLabel.group(1), prefixLabel.group(2)));
        }
        Matcher suffixLabel = Pattern.compile("(?i)(\\d+(?:\\.\\d+)?)([万wW])?(?:获赞|点赞|赞)")
                .matcher(normalized);
        while (suffixLabel.find()) {
            best = Math.max(best, parseLikeValue(suffixLabel.group(1), suffixLabel.group(2)));
        }
        Matcher compactCount = Pattern.compile("(?i)^\\D{0,3}(\\d+(?:\\.\\d+)?)([万wW])\\D{0,3}$")
                .matcher(normalized);
        if (best == 0.0d
                && normalized.length() <= 12
                && !normalized.matches(".*[个条次天年月小时分钟秒].*")
                && compactCount.find()) {
            best = Math.max(best, parseLikeValue(compactCount.group(1), compactCount.group(2)));
        }
        return best;
    }

    private double parseLikeValue(String rawNumber, @Nullable String unit) {
        return parseCountValue(rawNumber, unit);
    }

    private double parseCountValue(String rawNumber, @Nullable String unit) {
        try {
            double value = Double.parseDouble(rawNumber);
            if (unit != null && (unit.equalsIgnoreCase("w") || unit.equals("万"))) {
                value *= 10_000.0d;
            } else if (unit != null && (unit.equalsIgnoreCase("k") || unit.equals("千"))) {
                value *= 1_000.0d;
            }
            return value;
        } catch (NumberFormatException ignored) {
            return 0.0d;
        }
    }

    @Nullable
    Bounds boundsFromExtractedItem(JsonNode bbox) {
        if (bbox == null || bbox.isMissingNode() || bbox.isNull()) {
            return null;
        }
        double x = bbox.path("x").asDouble(Double.NaN);
        double y = bbox.path("y").asDouble(Double.NaN);
        double width = bbox.path("width").asDouble(Double.NaN);
        double height = bbox.path("height").asDouble(Double.NaN);
        if (!Double.isFinite(x) || !Double.isFinite(y)
                || !Double.isFinite(width) || !Double.isFinite(height)
                || width <= 0.0d || height <= 0.0d) {
            return null;
        }
        return new Bounds(x, y, width, height);
    }

    private String cleanExtractedVideoTitle(String text) {
        if (text == null) {
            return "";
        }
        return text.replaceAll("\\s+", " ").trim();
    }

    @Nullable
    String bestVideoHref(JsonNode item) {
        if (item == null || item.isMissingNode() || item.isNull()) {
            return null;
        }
        String href = item.path("href").asText("");
        if (looksLikeDouyinVideoHref(href)) {
            return href;
        }
        JsonNode hrefs = item.path("hrefs");
        if (hrefs.isArray()) {
            for (JsonNode node : hrefs) {
                String candidate = node.asText("");
                if (looksLikeDouyinVideoHref(candidate)) {
                    return candidate;
                }
            }
        }
        return null;
    }

    private boolean looksLikeDouyinVideoHref(@Nullable String href) {
        if (href == null || href.isBlank()) {
            return false;
        }
        String lower = href.toLowerCase(Locale.ROOT);
        return (lower.contains("douyin.com") || lower.startsWith("/"))
                && (lower.contains("/video/") || lower.contains("modal_id=") || lower.contains("aweme_id="));
    }

    private String videoIdentity(String url) {
        if (url == null || url.isBlank()) {
            return "";
        }
        Matcher query = Pattern.compile("(?i)(?:modal_id|aweme_id)=([0-9A-Za-z_-]{6,})").matcher(url);
        if (query.find()) {
            return query.group(1);
        }
        Matcher path = Pattern.compile("(?i)/video/([0-9A-Za-z_-]{6,})").matcher(url);
        if (path.find()) {
            return path.group(1);
        }
        return "";
    }

    private boolean looksLikePostTitle(String name) {
        return name != null && (name.length() >= 12
                || name.contains("#")
                || name.contains("？")
                || name.contains("?")
                || name.contains("！")
                || name.contains("!")
                || name.contains("...")
                || name.contains("…"));
    }

    private boolean isProbablyCountOnly(String name) {
        return name != null && name.replaceAll("\\s+", "").matches("^\\d+(?:\\.\\d+)?([万wW])?$");
    }

    private boolean isVideoControlText(String name) {
        String trimmed = name == null ? "" : name.replaceAll("\\s+", "");
        return trimmed.isBlank()
                || trimmed.matches("^(搜索|筛选|排序|综合|视频|用户|直播|商品|音乐|话题|地点|更多|展开|收起|回复|点赞|分享|收藏|评论|关注|私信|图文|添加至稍后再看|没有更多)$")
                || trimmed.matches("^\\d+(?:\\.\\d+)?([万wW])?(赞|评论|回复|分享|收藏)$");
    }

    private boolean isNavOnlyText(String name) {
        String trimmed = name == null ? "" : name.replaceAll("\\s+", "");
        return trimmed.matches("^(首页|推荐|精选|朋友|关注|商城|消息|我的|我|发布视频|创作者服务中心|综合排序|最多点赞|最新发布|发布时间|全部时间|一周内|半年内|多列|单列|问问AI|问AI)$");
    }

    private BrowserObservation clickAndVerifyVideoTarget(VideoResultTarget target, BrowserObservation before) {
        BrowserObservation current = before;
        List<ClickPoint> points = videoOpenClickPoints(target, before);
        boolean clicked = false;
        for (int i = 0; i < points.size(); i++) {
            ClickPoint point = points.get(i);
            if (!tryOk(browser.service_click_main(point.x(), point.y()))) {
                continue;
            }
            clicked = true;
            waitMs(i == 0 ? 1_800L : 900L);
            current = observeMain("all");
            if (looksLikeVideoOpenHard(current) || looksLikeLoginWall(current)) {
                return current;
            }
        }
        if (!clicked) {
            throw new DouyinBrowserException("VIDEO_OPEN_CLICK_FAILED",
                    "找到视频候选，但所有点击点都失败。target=" + target.debugSummary());
        }
        return current;
    }

    private boolean looksLikeLoginWall(BrowserObservation obs) {
        String hay = ((obs.title() == null ? "" : obs.title()) + "\n"
                + (obs.tree() == null ? "" : obs.tree())).toLowerCase(Locale.ROOT);
        return hay.contains("登录后即可")
                || hay.contains("扫码登录")
                || hay.contains("验证码登录")
                || hay.contains("手机号登录")
                || hay.contains("登录抖音")
                || hay.contains("scan to log in")
                || hay.contains("sign in to continue");
    }

    private List<ClickPoint> videoOpenClickPoints(VideoResultTarget target, BrowserObservation obs) {
        List<ClickPoint> points = new ArrayList<>();
        List<TreeLine> lines = parseTreeLines(obs.tree());
        List<TreeLine> surfaces = lines.stream()
                .filter(line -> sameVideoResultCard(target.evidenceLine(), line)
                        || sameVideoResultCard(target.clickLine(), line))
                .filter(line -> isVideoCardClickableSurface(line, target.evidenceLine()))
                .sorted(Comparator.comparingInt((TreeLine line) -> scoreVideoClickSurface(line, target.evidenceLine()))
                        .reversed())
                .toList();
        bestCoverLineForVideoResult(target.evidenceLine(), lines)
                .ifPresent(surface -> addVideoClickPoint(points, videoSurfacePoint(surface, 0.50d, 0.34d), obs));
        for (ClickPoint point : inferredVideoCoverPoints(target, obs)) {
            addVideoClickPoint(points, point, obs);
        }
        surfaces.stream()
                .filter(surface -> (surface.role().equalsIgnoreCase("image") || surface.role().equalsIgnoreCase("img"))
                        && surface.w() >= 120.0d
                        && surface.h() >= 120.0d)
                .findFirst()
                .ifPresent(surface -> addVideoClickPoint(points, videoSurfacePoint(surface, 0.50d, 0.34d), obs));
        for (TreeLine surface : surfaces) {
            addVideoClickPoint(points, surface.point(), obs);
        }
        addVideoClickPoint(points, videoCardCenterPoint(target.clickLine(), target.evidenceLine()), obs);
        for (TreeLine surface : surfaces) {
            if (surface.w() >= 120.0d && surface.h() >= 90.0d) {
                addVideoClickPoint(points, videoSurfacePoint(surface, 0.50d, 0.30d), obs);
                addVideoClickPoint(points, videoSurfacePoint(surface, 0.50d, 0.62d), obs);
            }
            addVideoClickPoint(points, videoCoverLikePoint(surface), obs);
        }
        return points;
    }

    private List<ClickPoint> inferredVideoCoverPoints(VideoResultTarget target, BrowserObservation obs) {
        if (target == null || obs == null) {
            return List.of();
        }
        if (target.source() == null || !target.source().contains("a11y")) {
            return List.of();
        }
        TreeLine click = target.clickLine();
        TreeLine evidence = target.evidenceLine();
        if (isLargeImageLine(click) || isLargeImageLine(evidence)) {
            return List.of();
        }
        double left = Math.min(click.x(), evidence.x());
        double right = Math.max(click.x() + click.w(), evidence.x() + evidence.w());
        double top = Math.min(click.y(), evidence.y());
        double width = Math.max(180.0d, right - left);
        double textHeight = Math.max(click.h(), evidence.h());
        double verticalOffset = Math.max(205.0d, Math.min(250.0d, textHeight * 2.15d));
        if (top < 180.0d) {
            return List.of();
        }
        List<ClickPoint> points = new ArrayList<>();
        double[] xRatios = {0.50d, 0.68d, 0.36d};
        double[] yOffsets = {verticalOffset, Math.max(150.0d, verticalOffset - 55.0d), Math.min(280.0d, verticalOffset + 45.0d)};
        for (double yOffset : yOffsets) {
            double y = top - yOffset;
            if (y < 108.0d) {
                y = Math.max(108.0d, top - 92.0d);
            }
            if (y >= top - 24.0d) {
                continue;
            }
            for (double xRatio : xRatios) {
                double x = left + width * xRatio;
                points.add(new ClickPoint(x, y));
            }
        }
        return points;
    }

    private boolean isLargeImageLine(TreeLine line) {
        return line != null
                && (line.role().equalsIgnoreCase("image") || line.role().equalsIgnoreCase("img"))
                && line.w() >= 100.0d
                && line.h() >= 100.0d;
    }

    private ClickPoint videoCardCenterPoint(TreeLine clickLine, TreeLine evidence) {
        double left = Math.min(clickLine.x(), evidence.x());
        double top = Math.min(clickLine.y(), evidence.y());
        double right = Math.max(clickLine.x() + clickLine.w(), evidence.x() + evidence.w());
        double bottom = Math.max(clickLine.y() + clickLine.h(), evidence.y() + evidence.h());
        return new ClickPoint(left + Math.max(160.0d, right - left) / 2.0d,
                top + Math.max(160.0d, bottom - top) / 2.0d);
    }

    private ClickPoint videoCoverLikePoint(TreeLine line) {
        double x = line.x() + Math.max(24.0d, Math.min(line.w() * 0.5d, line.w() - 24.0d));
        double y = line.y() + Math.max(36.0d, Math.min(line.h() * 0.45d, line.h() - 36.0d));
        return new ClickPoint(x, y);
    }

    private ClickPoint videoSurfacePoint(TreeLine line, double xRatio, double yRatio) {
        double x = line.x() + Math.max(8.0d, Math.min(line.w() * xRatio, line.w() - 8.0d));
        double y = line.y() + Math.max(8.0d, Math.min(line.h() * yRatio, line.h() - 8.0d));
        return new ClickPoint(x, y);
    }

    private void addVideoClickPoint(List<ClickPoint> points, ClickPoint point, BrowserObservation obs) {
        if (point.x() < 0.0d || point.y() < 0.0d
                || point.x() >= obs.viewportWidth()
                || point.y() >= obs.viewportHeight()) {
            return;
        }
        boolean duplicate = points.stream()
                .anyMatch(existing -> Math.abs(existing.x() - point.x()) < 3.0d
                        && Math.abs(existing.y() - point.y()) < 3.0d);
        if (!duplicate) {
            points.add(point);
        }
    }

    private boolean looksLikeVideoOpen(BrowserObservation obs) {
        String lowerUrl = obs.url() == null ? "" : obs.url().toLowerCase(Locale.ROOT);
        if (looksLikeVideoOpenHard(obs)) {
            return true;
        }
        String tree = obs.tree() == null ? "" : obs.tree();
        boolean hasVideoActions = tree.contains("评论")
                && (tree.contains("分享") || tree.contains("收藏") || tree.contains("点赞"));
        boolean hasVideoSurface = tree.contains("暂停")
                || tree.contains("播放")
                || tree.contains("倍速")
                || tree.contains("全屏")
                || tree.contains("相关视频")
                || tree.contains("作者");
        boolean stillSearch = lowerUrl.contains("/search")
                && (tree.contains("综合") || tree.contains("筛选") || tree.contains("最多点赞"))
                && !lowerUrl.contains("modal_id=");
        return hasVideoActions && hasVideoSurface && !stillSearch;
    }

    private boolean looksLikeVideoOpenHard(BrowserObservation obs) {
        String lowerUrl = obs.url() == null ? "" : obs.url().toLowerCase(Locale.ROOT);
        return lowerUrl.contains("/video/")
                || lowerUrl.contains("modal_id=")
                || lowerUrl.contains("aweme_id=");
    }

    private boolean openedMatchesTarget(BrowserObservation opened, VideoResultTarget target) {
        String targetVideoId = videoIdentity(target.href());
        if (!targetVideoId.isBlank()) {
            String openedVideoId = videoIdentity(opened.url());
            if (!openedVideoId.isBlank()) {
                return openedVideoId.equals(targetVideoId);
            }
        }
        String normalizedTree = normalizeForLooseMatch(opened.tree());
        String normalizedTitle = normalizeForLooseMatch(target.title());
        if (normalizedTitle.length() < 8) {
            return true;
        }
        if (normalizedTree.contains(normalizedTitle)) {
            return true;
        }
        int prefixLength = Math.min(normalizedTitle.length(), normalizedTitle.length() >= 18 ? 14 : 8);
        String prefix = normalizedTitle.substring(0, prefixLength);
        if (normalizedTree.contains(prefix)) {
            return true;
        }
        String evidence = normalizeForLooseMatch(target.evidenceLine().name());
        return evidence.length() >= 8 && normalizedTree.contains(evidence.substring(0, Math.min(8, evidence.length())));
    }

    private String normalizeForLooseMatch(String text) {
        return text == null ? "" : text.toLowerCase(Locale.ROOT)
                .replaceAll("[\\s`'\"“”‘’《》<>\\[\\]()（）【】{}，,。.!！?？:：;；、/\\\\|-]", "");
    }

    private String videoOpenSignals(BrowserObservation obs) {
        String tree = obs.tree() == null ? "" : obs.tree();
        return "videoOpen=" + looksLikeVideoOpen(obs)
                + ", hasModalUrl=" + (obs.url() != null && obs.url().contains("modal_id="))
                + ", hasActions=" + (tree.contains("评论") && (tree.contains("分享") || tree.contains("点赞")))
                + ", treeChars=" + tree.length();
    }

    record ClickPoint(double x, double y) {
        static ClickPoint fromTreeLine(String line) {
            Bounds bounds = Bounds.fromTreeLine(line);
            if (bounds == null) {
                return null;
            }
            return new ClickPoint(bounds.x() + bounds.width() / 2.0d, bounds.y() + bounds.height() / 2.0d);
        }
    }

    record Bounds(double x, double y, double width, double height) {
        static Bounds fromTreeLine(String line) {
            if (line == null) {
                return null;
            }
            java.util.regex.Matcher matcher = java.util.regex.Pattern
                    .compile("@\\{(-?\\d+),(-?\\d+)\\s+(\\d+)x(\\d+)\\}")
                    .matcher(line);
            if (!matcher.find()) {
                return null;
            }
            return new Bounds(
                    Double.parseDouble(matcher.group(1)),
                    Double.parseDouble(matcher.group(2)),
                    Double.parseDouble(matcher.group(3)),
                    Double.parseDouble(matcher.group(4)));
        }
    }

    record ScoredPoint(ClickPoint point, int score) {
    }

    record ScrollRegionEvidence(
            boolean moved,
            String mode,
            String reason,
            double before,
            double after,
            boolean forwardProgress,
            int newVisibleItemCount,
            int retainedVisibleItemCount,
            String beforeFirstItemSignature,
            String beforeLastItemSignature,
            String afterFirstItemSignature,
            String afterLastItemSignature,
            String afterWindowSignature
    ) {
        static ScrollRegionEvidence none() {
            return empty("", "");
        }

        static ScrollRegionEvidence wheelFallback(String reason) {
            return empty("wheel_direct_fallback", reason);
        }

        static ScrollRegionEvidence keyboardFallback(String reason) {
            return empty("keyboard_fallback", reason);
        }

        static ScrollRegionEvidence regionOnlyFallback(String reason) {
            return empty("region_scroll_only", reason);
        }

        static ScrollRegionEvidence networkTrigger(String reason) {
            return new ScrollRegionEvidence(
                    true,
                    "network_direct_wheel",
                    reason == null ? "" : reason,
                    Double.NaN,
                    Double.NaN,
                    true,
                    0,
                    0,
                    "",
                    "",
                    "",
                    "",
                    "");
        }

        static ScrollRegionEvidence empty(String mode, String reason) {
            return new ScrollRegionEvidence(
                    false,
                    mode == null ? "" : mode,
                    reason == null ? "" : reason,
                    Double.NaN,
                    Double.NaN,
                    false,
                    0,
                    0,
                    "",
                    "",
                    "",
                    "",
                    "");
        }

        boolean hasPositionEvidence() {
            return forwardProgress
                    || "comment_window_advanced".equals(reason)
                    || Double.isFinite(before) && Double.isFinite(after);
        }

        boolean repeatedWindow() {
            return "comment_window_unchanged".equals(reason)
                    || "comment_window_changed_without_forward_progress".equals(reason);
        }

        boolean panelLostSignal() {
            return "url_changed_or_panel_lost".equals(reason)
                    || "comment_panel_not_verified_before_wheel".equals(reason);
        }

        static ScrollRegionEvidence from(JsonNode node) {
            JsonNode payload = node.path("results").isArray() && !node.path("results").isEmpty()
                    ? node.path("results").get(0).path("payload")
                    : node.path("payload");
            double before = payload.path("scrollTopBefore").asDouble(Double.NaN);
            double after = payload.path("scrollTopAfter").asDouble(Double.NaN);
            String mode = payload.path("mode").asText("");
            String reason = payload.path("reason").asText("");
            boolean forwardProgress = payload.path("forwardProgress").asBoolean(false)
                    || "comment_window_advanced".equals(reason);
            boolean moved = payload.path("moved").asBoolean(false)
                    || forwardProgress
                    || Double.isFinite(before)
                    && Double.isFinite(after)
                    && Math.abs(after - before) > 0.5d;
            return new ScrollRegionEvidence(
                    moved,
                    mode,
                    reason,
                    before,
                    after,
                    forwardProgress,
                    payload.path("newVisibleItemCount").asInt(0),
                    payload.path("retainedVisibleItemCount").asInt(0),
                    payload.path("beforeFirstItemSignature").asText(""),
                    payload.path("beforeLastItemSignature").asText(""),
                    payload.path("afterFirstItemSignature").asText(""),
                    payload.path("afterLastItemSignature").asText(""),
                    payload.path("afterWindowSignature").asText(""));
        }
    }

    record SearchTarget(@Nullable ClickPoint point, BrowserObservation observation) {
    }

    record VideoCandidates(List<VideoResultTarget> targets, BrowserObservation observation, String debug) {
        boolean isEmpty() {
            return targets == null || targets.isEmpty();
        }
    }

    record SortedVideoSnapshot(String keyword, VideoCandidates candidates, long capturedAtMs) {
        SortedVideoSnapshot {
            keyword = keyword == null ? "" : keyword;
            candidates = candidates == null
                    ? new VideoCandidates(List.of(), BrowserObservation.failed("NO_SNAPSHOT", "no sorted snapshot"), "")
                    : candidates;
        }

        static SortedVideoSnapshot empty() {
            return new SortedVideoSnapshot(
                    "",
                    new VideoCandidates(List.of(), BrowserObservation.failed("NO_SNAPSHOT", "no sorted snapshot"), ""),
                    0L);
        }

        boolean isEmpty() {
            return keyword.isBlank() || candidates.isEmpty() || capturedAtMs <= 0L;
        }
    }

    record DomVideoTargets(List<VideoResultTarget> targets, String debug) {
        DomVideoTargets {
            targets = targets == null ? List.of() : List.copyOf(targets);
            debug = debug == null ? "" : debug;
        }
    }

    record ExtractedComments(
            List<DouyinCommentItem> comments,
            int declaredCommentCount,
            boolean endReached,
            boolean emptyReached,
            int nextDomStartIndex,
            int networkPageCount,
            boolean networkHasMoreFalse,
            List<String> networkPageKeys,
            String networkCursor,
            String networkNextCursor
    ) {
        ExtractedComments(List<DouyinCommentItem> comments, int declaredCommentCount) {
            this(comments, declaredCommentCount, false, false, -1, 0, false, List.of(), "", "");
        }

        ExtractedComments(List<DouyinCommentItem> comments, int declaredCommentCount, boolean endReached) {
            this(comments, declaredCommentCount, endReached, false, -1, 0, false, List.of(), "", "");
        }

        ExtractedComments(List<DouyinCommentItem> comments,
                          int declaredCommentCount,
                          boolean endReached,
                          boolean emptyReached) {
            this(comments, declaredCommentCount, endReached, emptyReached, -1, 0, false, List.of(), "", "");
        }

        ExtractedComments(List<DouyinCommentItem> comments,
                          int declaredCommentCount,
                          boolean endReached,
                          boolean emptyReached,
                          int nextDomStartIndex) {
            this(comments, declaredCommentCount, endReached, emptyReached, nextDomStartIndex, 0, false, List.of(), "", "");
        }

        static ExtractedComments empty() {
            return new ExtractedComments(List.of(), 0, false, false, -1, 0, false, List.of(), "", "");
        }

        static ExtractedComments network(List<DouyinCommentItem> comments,
                                         int declaredCommentCount,
                                         int pageCount,
                                         boolean hasMoreFalse,
                                         List<String> pageKeys,
                                         String cursor,
                                         String nextCursor) {
            return new ExtractedComments(
                    comments,
                    declaredCommentCount,
                    false,
                    false,
                    -1,
                    pageCount,
                    hasMoreFalse,
                    pageKeys,
                    cursor,
                    nextCursor);
        }

        ExtractedComments {
            comments = comments == null ? List.of() : List.copyOf(comments);
            declaredCommentCount = Math.max(0, declaredCommentCount);
            nextDomStartIndex = Math.max(-1, nextDomStartIndex);
            networkPageCount = Math.max(0, networkPageCount);
            networkPageKeys = networkPageKeys == null ? List.of() : List.copyOf(networkPageKeys);
            networkCursor = networkCursor == null ? "" : networkCursor;
            networkNextCursor = networkNextCursor == null ? "" : networkNextCursor;
        }
    }

    record CommentWindowHarvest(
            int beforeCount,
            int afterCount,
            int newItems,
            int extractedCount,
            int visibleCount,
            int declaredCommentCount,
            int nextDomStartIndex,
            boolean endReached,
            boolean emptyReached,
            int networkPageCount,
            boolean networkHasMoreFalse
    ) {
    }

    private static final class NetworkCollectionState {
        private int pageCount;
        private int terminalPageCount;
        private final Set<String> pageKeys = new HashSet<>();
        private String lastCursor = "";
        private String lastNextCursor = "";
        // 最近一次 drain 时扩展侧报告的"在途未回"续拉数(pendingResponses + inflight)。
        private int lastInflight;

        void recordInflight(int inflight) {
            this.lastInflight = Math.max(0, inflight);
        }

        boolean hasPendingPulls() {
            return lastInflight > 0;
        }

        void record(ExtractedComments comments) {
            if (comments == null || comments.networkPageCount() <= 0) {
                return;
            }
            int newPages = 0;
            if (comments.networkPageKeys().isEmpty()) {
                newPages = comments.networkPageCount();
            } else {
                for (String key : comments.networkPageKeys()) {
                    if (key == null || key.isBlank() || pageKeys.add(key)) {
                        newPages++;
                    }
                }
            }
            if (newPages <= 0) {
                return;
            }
            pageCount += newPages;
            if (comments.networkHasMoreFalse()) {
                terminalPageCount++;
            }
            lastCursor = firstNonBlankStatic(comments.networkCursor(), lastCursor);
            lastNextCursor = firstNonBlankStatic(comments.networkNextCursor(), lastNextCursor);
        }

        boolean primaryActive() {
            return pageCount > 0;
        }

        boolean hasTerminalPage() {
            return terminalPageCount > 0;
        }

        int pageCount() {
            return pageCount;
        }

        int terminalPageCount() {
            return terminalPageCount;
        }

        String lastCursor() {
            return lastCursor;
        }

        String lastNextCursor() {
            return lastNextCursor;
        }

        private static String firstNonBlankStatic(String first, String second) {
            return first != null && !first.isBlank() ? first : second == null ? "" : second;
        }
    }

    record VideoResultTarget(TreeLine clickLine,
                             TreeLine evidenceLine,
                             String title,
                             double likeCount,
                             int score,
                             @Nullable String href,
                             String source) {
        String debugSummary() {
            return "{title=\"" + (title == null ? "" : title.replace("\"", "'"))
                    + "\", like=" + likeCount
                    + ", score=" + score
                    + ", source=" + (source == null ? "" : source)
                    + ", href=\"" + (href == null ? "" : href.replace("\"", "'"))
                    + ", click=(" + clickLine.point().x() + "," + clickLine.point().y() + ")"
                    + ", evidence=\"" + evidenceLine.name().replace("\"", "'") + "\"}";
        }
    }

    record SearchGrounding(String hintText, String role) {
    }

    record TreeLine(String role, String name, Bounds bounds) {
        double x() {
            return bounds.x();
        }

        double y() {
            return bounds.y();
        }

        double w() {
            return bounds.width();
        }

        double h() {
            return bounds.height();
        }

        ClickPoint point() {
            return new ClickPoint(x() + w() / 2.0d, y() + h() / 2.0d);
        }
    }

    record DouyinSortSpec(String code, String displayName, List<String> labels) {
        DouyinSortSpec {
            code = code == null || code.isBlank() ? DouyinLeadAcquisitionInput.DEFAULT_SORT : code.trim();
            displayName = displayName == null || displayName.isBlank() ? code : displayName.trim();
            labels = labels == null || labels.isEmpty() ? DOUYIN_COMPREHENSIVE_SORT_LABELS : List.copyOf(labels);
        }

        static DouyinSortSpec from(String sort) {
            String normalized = sort == null ? "" : sort.trim().toLowerCase(Locale.ROOT);
            return switch (normalized) {
                case "latest", "latest_published", "newest", "publish_time", "time" -> latest();
                case "most_liked", "like", "liked", "digg" -> mostLiked();
                case "comprehensive", "comprehensive_sort", "general", "default", "" -> comprehensive();
                default -> comprehensive();
            };
        }

        static DouyinSortSpec comprehensive() {
            return new DouyinSortSpec("comprehensive", "综合排序", DOUYIN_COMPREHENSIVE_SORT_LABELS);
        }

        static DouyinSortSpec mostLiked() {
            return new DouyinSortSpec("most_liked", "最多点赞", DOUYIN_LIKE_SORT_LABELS);
        }

        static DouyinSortSpec latest() {
            return new DouyinSortSpec("latest", "最新发布", DOUYIN_LATEST_SORT_LABELS);
        }

        String primaryLabel() {
            return labels.getFirst();
        }

        boolean isMostLiked() {
            return "most_liked".equals(code);
        }

        boolean isComprehensive() {
            return "comprehensive".equals(code);
        }
    }

    record SortOption(TreeLine line, String label, int score) {
    }

    record CommentTriggerCandidate(TreeLine line, int score) {
    }
}
