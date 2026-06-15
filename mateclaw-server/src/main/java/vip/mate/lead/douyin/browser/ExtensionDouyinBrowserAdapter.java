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
    private static final int COMMENT_NETWORK_FALLBACK_GRACE_SCROLLS = 6;
    private static final int COMMENT_NETWORK_ONLY_NO_PAGE_SCROLL_LIMIT = 35;
    private static final int COMMENT_NETWORK_ONLY_STALE_WINDOW_LIMIT = 60;
    private static final int AUTHOR_PROFILE_OPEN_MAX_ATTEMPTS = 2;
    private static final int AUTHOR_PROFILE_CONFIRM_ATTEMPTS = 16;
    private static final int AUTHOR_PROFILE_RETRY_CONFIRM_ATTEMPTS = 24;
    private static final long AUTHOR_PROFILE_CONFIRM_WAIT_MS = 750L;
    private static final long AUTHOR_PROFILE_RETRY_CONFIRM_WAIT_MS = 1_000L;
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

    @Override
    public BrowserObservation openDouyinAndSearch(DouyinLeadAcquisitionInput input) {
        clearSortedVideoSnapshot();
        DouyinBrowserException last = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                return openDouyinAndSearchOnce(input, attempt);
            } catch (DouyinBrowserException e) {
                last = e;
                if (!"SESSION_DETACHED".equals(e.code()) || attempt >= 2) {
                    throw e;
                }
                sleepLocal(1200L + attempt * 900L);
            }
        }
        throw last == null ? new DouyinBrowserException("SEARCH_FAILED", "抖音搜索失败") : last;
    }

    private BrowserObservation openDouyinAndSearchOnce(DouyinLeadAcquisitionInput input, int attempt) {
        BrowserObservation current = observeMain("all");
        if (canReuseSearchResultsForSorting(current, input.keyword())) {
            return current;
        }
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

    @Override
    public BrowserObservation applySort(DouyinLeadAcquisitionInput input) {
        DouyinSortSpec sort = DouyinSortSpec.from(input == null ? "" : input.sort());
        String keyword = input == null ? DouyinLeadAcquisitionInput.DEFAULT_KEYWORD : input.keyword();
        DouyinBrowserException lastDetached = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
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
                BrowserObservation panel = openFilterPanel(observed, keyword, sort);
                return selectSortOption(panel, keyword, sort);
            } catch (DouyinBrowserException e) {
                if (!"SESSION_DETACHED".equals(e.code()) || attempt >= 2) {
                    throw e;
                }
                lastDetached = e;
                sleepLocal(1_200L);
                BrowserObservation recovered = waitForSearchVerified(keyword, 4, 700L);
                if (sortVerified(recovered, keyword, sort)) {
                    rememberSortedVideoSnapshot(keyword, recovered);
                    return recovered;
                }
            }
        }
        throw lastDetached == null
                ? new DouyinBrowserException("SORT_FAILED", "抖音排序失败")
                : lastDetached;
    }

    @Override
    public BrowserObservation openVideo(int zeroBasedIndex) {
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
        VideoCandidates candidates = sortedVideoSnapshotCandidates(current);
        if (candidates.isEmpty()) {
            candidates = videoCandidatesFromObservation(current);
        }
        if (candidates.isEmpty()) {
            candidates = waitForVideoTargets(8, 700L);
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

    private BrowserObservation switchToNextVideoByKeyboard(BrowserObservation current, int zeroBasedIndex) {
        BrowserObservation before = closeCommentPanelBeforeVideoSwitch(current);
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
        throw new DouyinBrowserException("VIDEO_KEYBOARD_SWITCH_NOT_CONFIRMED",
                "已尝试用下方向键打开第 " + (zeroBasedIndex + 1)
                        + " 个视频，但没有确认视频切换。url=" + before.url()
                        + ", title=" + before.title()
                        + ", tree=" + treeExcerpt(before.tree()));
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
        startCommentNetworkCapture();
        BrowserObservation observed = observeMain("all");
        if (commentsPanelReady(observed)) {
            return commentsOpenedObservation(observed, "already_open");
        }

        observed = pressDouyinCommentShortcutX(observed);
        if (commentsPanelReady(observed)) {
            return commentsOpenedObservation(observed, "shortcut_x");
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
        NetworkCollectionState network = new NetworkCollectionState();
        if (COMMENT_NETWORK_ONLY_COLLECTION && !commentNetworkCaptureActive) {
            startCommentNetworkCapture();
        }
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
        ExtractedComments networkResult = drainNetworkComments(current == null ? "" : current.url());
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
        return openAuthorProfileForEngagement(comment).observation();
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
            profile = waitForFollowConfirmation(engagementTabId, profile, 6, 700L);
        }
        boolean followConfirmed = alreadyFollowed || profileFollowConfirmed(profile);

        List<String> dmLabels = List.of("私信", "发私信", "Message", "发消息");
        if (!clickProfileAction(profile, "dm", dmLabels, engagementTabId)) {
            return new EngagementResult(comment, comment.authorName(), resultProfileUrl, true,
                    followConfirmed, false, false, false, "failed", "DM_BUTTON_NOT_FOUND",
                    "未找到私信入口：A11y 与 DOM 均未命中");
        }
        BrowserObservation dmPage = waitForDmPage(engagementTabId, 8, 650L);
        if (!looksLikeDouyinDmPage(dmPage)) {
            dmPage = retryDmDomActionAfterUnconfirmedPage(comment, profile, dmPage, dmLabels, engagementTabId);
        }
        if (!isDouyinPage(dmPage.url())) {
            return EngagementResult.failed(comment, "DM_TAB_NOT_CONTROLLED", "私信页不是受控抖音标签页");
        }
        if (!looksLikeDouyinDmPage(dmPage)) {
            log.warn("[douyin.lead] dm page not confirmed: url={}, title={}, signals={}, tree={}",
                    dmPage.url(), dmPage.title(), dmPageSignals(dmPage), treeExcerpt(dmPage.tree()));
            return new EngagementResult(comment, comment.authorName(), resultProfileUrl, true,
                    followConfirmed, false, false, false, "failed", "DM_PAGE_NOT_CONFIRMED",
                    "未确认进入目标用户私信页，拒绝输入草稿。signals=" + dmPageSignals(dmPage));
        }
        JsonNode dmDraftAction = errorNode("NOT_RUN", "type_dm_draft not run");
        boolean typedByDmPrimitive = false;
        boolean typedByGenericFallback = false;
        boolean sentByDmPrimitive = false;
        try {
            dmDraftAction = parse(engagementTabId == null
                    ? browser.service_type_dm_draft_active(dmDraft, sendDm)
                    : browser.service_type_dm_draft_tab(engagementTabId, dmDraft, sendDm));
            typedByDmPrimitive = ok(dmDraftAction);
            sentByDmPrimitive = actionPayloadBoolean(dmDraftAction, "sent");
        } catch (RuntimeException e) {
            log.warn("[douyin.lead] type_dm_draft primitive failed; checking whether draft is already visible: {}",
                    e.getMessage());
            typedByDmPrimitive = false;
        }
        if (!typedByDmPrimitive) {
            BrowserObservation afterDraftAttempt = observeEngagementTab(engagementTabId, "all");
            if (looksLikeDouyinDmPage(afterDraftAttempt)
                    && (dmDraftVisibleInDmInputArea(afterDraftAttempt, dmDraft) || dmDraftVisibleInDmDom(afterDraftAttempt, dmDraft))) {
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
        if (sendDm && !sentByDmPrimitive) {
            BrowserObservation beforeSend = observeEngagementTab(engagementTabId, "all");
            if (looksLikeDouyinDmPage(beforeSend)
                    && (dmDraftVisibleInDmInputArea(beforeSend, dmDraft) || dmDraftVisibleInDmDom(beforeSend, dmDraft))) {
                try {
                    JsonNode sendAction = parse(engagementTabId == null
                            ? browser.service_send_dm_active(dmDraft)
                            : browser.service_send_dm_tab(engagementTabId, dmDraft));
                    sentByDmPrimitive = ok(sendAction) && actionPayloadBoolean(sendAction, "sent");
                    if (sentByDmPrimitive) {
                        log.info("[douyin.lead] sent existing dm draft by send-only primitive");
                    }
                } catch (RuntimeException e) {
                    log.warn("[douyin.lead] send-only dm primitive failed: {}", e.getMessage());
                }
            }
        }
        waitMs(500);
        BrowserObservation verify = observeEngagementTab(engagementTabId, "all");
        boolean draftTyped = sentByDmPrimitive
                || typedByDmPrimitive
                || typedByGenericFallback
                || (looksLikeDouyinDmPage(verify)
                && (dmDraftVisibleInDmInputArea(verify, dmDraft) || dmDraftVisibleInDmDom(verify, dmDraft)));
        boolean sent = sendDm && sentByDmPrimitive;
        boolean succeeded = draftTyped && (followConfirmed || dmPage.tree().contains("私信")) && (!sendDm || sent);
        String failureCode = succeeded ? null
                : (!draftTyped ? "DRAFT_NOT_OBSERVED"
                : (!followConfirmed && !dmPage.tree().contains("私信") ? "FOLLOW_NOT_CONFIRMED"
                : "DM_SEND_NOT_CONFIRMED"));
        String failureMessage = succeeded ? null
                : (!draftTyped ? "未能确认私信草稿已输入"
                : (!followConfirmed && !dmPage.tree().contains("私信") ? "未能确认已关注目标作者"
                : "未能确认私信已发送"));
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
        return waitForDmPage(engagementTabId, 8, 650L);
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
        ClickPoint a11y = findProfileActionPoint(profile, labels.toArray(String[]::new));
        if (a11y != null && clickEngagementPoint(engagementTabId, a11y)) {
            log.info("[douyin.lead] clicked profile action by a11y: action={}, labels={}, x={}, y={}",
                    actionName, labels, a11y.x(), a11y.y());
            return true;
        }
        if (clickProfileActionByDom(engagementTabId, labels)) {
            log.info("[douyin.lead] clicked profile action by dom: action={}, labels={}",
                    actionName, labels);
            return true;
        }
        if ("dm".equals(actionName) && openProfileMoreMenu(profile, engagementTabId)) {
            waitMs(350L);
            BrowserObservation afterMore = observeEngagementTab(engagementTabId, "all");
            ClickPoint menuPoint = findProfileActionPoint(afterMore, labels.toArray(String[]::new));
            if (menuPoint != null && clickEngagementPoint(engagementTabId, menuPoint)) {
                log.info("[douyin.lead] clicked profile action from more menu by a11y: action={}, labels={}, x={}, y={}",
                        actionName, labels, menuPoint.x(), menuPoint.y());
                return true;
            }
            if (clickProfileActionByDom(engagementTabId, labels)) {
                log.info("[douyin.lead] clicked profile action from more menu by dom: action={}, labels={}",
                        actionName, labels);
                return true;
            }
        }
        log.warn("[douyin.lead] profile action not found: action={}, labels={}, url={}, tree={}",
                actionName, labels, profile == null ? "" : profile.url(),
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
        return tryOk(engagementTabId == null
                ? browser.service_click_profile_action_active(labels)
                : browser.service_click_profile_action_tab(engagementTabId, labels));
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
        if (obs == null || dmDraft == null || dmDraft.isBlank() || !isDouyinPage(obs.url())) {
            return false;
        }
        RegionInfo dmRegion = dmRegion(obs);
        if (!tryOk(browser.service_register_region_active(
                dmRegion.regionKey(), dmRegion.x(), dmRegion.y(), dmRegion.width(), dmRegion.height(), dmRegion.source()))) {
            return false;
        }
        try {
            JsonNode root = parse(browser.service_extract_region_active(dmRegion.regionKey(), 20));
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

    private ExtractedComments drainNetworkComments(String videoKey) {
        try {
            String result = browser.service_douyin_comment_network_main("drain", null, null, null);
            logCommentNetworkAction("drain", result);
            JsonNode root = parse(result);
            if (!root.path("ok").asBoolean(false)) {
                return ExtractedComments.empty();
            }
            JsonNode pages = root.path("results").path(0).path("payload").path("pages");
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
            return scrollCommentRegionNetworkTrigger(region);
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

    private ScrollRegionEvidence scrollCommentRegionNetworkTrigger(RegionInfo region) {
        ClickPoint point = commentRegionScrollPoint(region, 0);
        try {
            requireOk(browser.service_scroll_main(
                            "down",
                            COMMENT_NETWORK_SCROLL_STEP_PX,
                            point.x(),
                            point.y(),
                            COMMENT_NETWORK_WHEEL_SCROLL_DEADLINE_MS),
                    "scroll_comments_network_trigger");
            return ScrollRegionEvidence.networkTrigger("network_only_direct_wheel");
        } catch (DouyinBrowserException e) {
            log.warn("[douyin.comments.network] direct wheel trigger failed, keep network collector alive: code={}, message={}",
                    e.code(), e.getMessage());
            return ScrollRegionEvidence.wheelFallback("network_direct_wheel_" + e.code().toLowerCase(Locale.ROOT));
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
        requireOk(browser.service_hover_main(point.x(), point.y()),
                step);
    }

    private void focusCommentRegion(ClickPoint point, String step) {
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
                BrowserObservation active = observeActive("all");
                if (looksLikeDouyinUserProfile(active, comment)) {
                    log.info("[douyin.lead] author profile tab {} snapshot was blank; using active controlled tab for engagement",
                            tabId);
                    return new OpenedAuthorProfile(active, null);
                }
                BrowserObservation main = observeMain("all");
                if (looksLikeDouyinUserProfile(main, comment)) {
                    log.info("[douyin.lead] author profile tab {} snapshot was blank; using main controlled tab for engagement",
                            tabId);
                    return new OpenedAuthorProfile(main, null);
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
