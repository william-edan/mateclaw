package vip.mate.lead.douyin.collect;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;
import vip.mate.lead.douyin.browser.DouyinBrowserAdapter;
import vip.mate.lead.douyin.model.DouyinCommentItem;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class DouyinCommentCollector {

    private static final Pattern TREE_LINE = Pattern.compile(
            "^([A-Za-z][\\w-]*)\\s*\\[ref=([\\w-]+)(?:\\s*,\\s*frame=(\\d+))?]\\s*(?::\\s*(.+?))?\\s*"
                    + "@\\{(-?\\d+),(-?\\d+)\\s+(\\d+)x(\\d+)}\\s*$");
    private static final String COUNT_NUMBER = "(?:\\d{1,3}(?:,\\d{3})+|\\d+(?:\\.\\d+)?)";
    private static final Pattern COUNT_AFTER_LABEL = Pattern.compile("评论\\s*[（(]?\\s*(" + COUNT_NUMBER + ")(万|w|k|千)?\\s*[）)]?\\s*(?:条)?", Pattern.CASE_INSENSITIVE);
    private static final Pattern COUNT_BEFORE_LABEL = Pattern.compile("(" + COUNT_NUMBER + ")(万|w|k|千)?\\s*(?:条)?\\s*评论", Pattern.CASE_INSENSITIVE);
    private static final Pattern COMMENT_TIME_LOCATION = Pattern.compile(
            "^(?:刚刚|昨天|前天|\\d{1,3}\\s*(?:秒|分钟|小时|天|周|个?月|年)前)(?:\\s*[·・•]\\s*[^\\s]{1,16})?$");
    private static final Pattern GENERIC_USER_ID = Pattern.compile("^用户\\d{5,}$");
    private static final Pattern BRACKET_EMOJI_ONLY = Pattern.compile("^(?:\\[[^\\]\\s]{1,12}])+$");

    public Optional<DouyinBrowserAdapter.RegionInfo> detectCommentRegion(DouyinBrowserAdapter.BrowserObservation obs) {
        List<TreeLine> lines = parseLines(obs.tree());
        if (lines.isEmpty()) {
            return Optional.empty();
        }
        Optional<DouyinBrowserAdapter.RegionInfo> fromItems = detectCommentRegionFromCommentItems(lines, obs);
        if (fromItems.isPresent()) {
            return fromItems;
        }
        double panelMinX = Math.max(160d, obs.viewportWidth() >= 1500
                ? obs.viewportWidth() * 0.58d
                : obs.viewportWidth() * 0.45d);
        List<TreeLine> anchors = lines.stream()
                .filter(line -> line.centerX() >= panelMinX)
                .filter(line -> line.name().contains("评论")
                        || line.name().contains("TA的作品")
                        || line.name().contains("问AI")
                        || line.name().contains("展开")
                        || line.name().contains("回复"))
                .toList();
        if (anchors.isEmpty()) {
            return Optional.empty();
        }
        double minX = anchors.stream().mapToDouble(line -> line.x()).min().orElse(obs.viewportWidth() * 0.52d);
        double minY = anchors.stream().mapToDouble(line -> line.y()).min().orElse(0d);
        double maxX = anchors.stream().mapToDouble(line -> line.x() + line.w()).max().orElse(obs.viewportWidth());
        double maxY = anchors.stream().mapToDouble(line -> line.y() + line.h()).max().orElse(obs.viewportHeight());
        double x = Math.max(panelMinX, Math.min(minX - 40d, obs.viewportWidth() - 320d));
        double y = Math.max(0, Math.min(minY - 40d, obs.viewportHeight() - 200d));
        double width = Math.max(320d, Math.min(obs.viewportWidth() - x, Math.max(maxX - x + 80d, obs.viewportWidth() * 0.35d)));
        double height = Math.max(300d, Math.min(obs.viewportHeight() - y, Math.max(maxY - y + 120d, obs.viewportHeight() - y)));
        return Optional.of(DouyinBrowserAdapter.RegionInfo.comments(x, y, width, height, "a11y-comment-anchors"));
    }

    private Optional<DouyinBrowserAdapter.RegionInfo> detectCommentRegionFromCommentItems(
            List<TreeLine> lines,
            DouyinBrowserAdapter.BrowserObservation obs) {
        List<TreeLine> itemLines = new ArrayList<>();
        Optional<Double> tabLeft = commentPanelTabLeft(lines);
        double minPanelX = tabLeft
                .map(value -> Math.max(160d, value - 96d))
                .orElseGet(() -> Math.max(160d, obs.viewportWidth() >= 1500
                        ? obs.viewportWidth() * 0.56d
                        : obs.viewportWidth() * 0.54d));
        for (int i = 0; i < lines.size(); i++) {
            TreeLine line = lines.get(i);
            if (line.centerX() < minPanelX) {
                continue;
            }
            if (isLikelyCommentText(line.name()) && !isControlText(line.name())) {
                itemLines.add(line);
                nearestAuthor(lines, i).ifPresent(itemLines::add);
            }
        }
        List<TreeLine> inputAndReplies = lines.stream()
                .filter(line -> line.centerX() >= minPanelX)
                .filter(line -> line.name().contains("说点什么")
                        || line.name().contains("发表评论")
                        || line.name().contains("写评论")
                        || line.name().contains("回复")
                        || line.name().contains("展开"))
                .toList();
        itemLines.addAll(inputAndReplies);
        if (itemLines.size() < 3) {
            return Optional.empty();
        }
        double minX = itemLines.stream().mapToDouble(TreeLine::x).min().orElse(minPanelX);
        double maxX = itemLines.stream().mapToDouble(line -> line.x() + line.w()).max().orElse(obs.viewportWidth());
        double minY = itemLines.stream().mapToDouble(TreeLine::y).min().orElse(0d);
        double x = Math.max(minPanelX, Math.min(minX - 72d, obs.viewportWidth() - 320d));
        double y = Math.max(0d, Math.min(minY - 96d, obs.viewportHeight() - 260d));
        double right = Math.min(obs.viewportWidth(), Math.max(maxX + 96d, x + Math.max(360d, obs.viewportWidth() * 0.32d)));
        double width = Math.max(320d, right - x);
        double height = Math.max(300d, obs.viewportHeight() - y);
        return Optional.of(DouyinBrowserAdapter.RegionInfo.comments(
                x,
                y,
                width,
                height,
                "a11y-comment-items"));
    }

    private Optional<Double> commentPanelTabLeft(List<TreeLine> lines) {
        List<TreeLine> tabs = lines.stream()
                .filter(line -> {
                    String role = line.role().toLowerCase(Locale.ROOT);
                    String name = clean(line.name()).replaceAll("\\s+", "");
                    return ("tab".equals(role) || "button".equals(role) || "text".equals(role) || "statictext".equals(role))
                            && ("详情".equals(name) || "评论".equals(name))
                            && line.y() >= 48
                            && line.y() <= 180
                            && line.w() <= 180
                            && line.h() <= 80;
                })
                .toList();
        boolean hasDetails = tabs.stream().anyMatch(line -> clean(line.name()).replaceAll("\\s+", "").equals("详情"));
        boolean hasComments = tabs.stream().anyMatch(line -> clean(line.name()).replaceAll("\\s+", "").equals("评论"));
        if (!hasDetails || !hasComments) {
            return Optional.empty();
        }
        return tabs.stream().mapToDouble(TreeLine::x).min().stream().boxed().findFirst();
    }

    public List<DouyinCommentItem> visibleComments(DouyinBrowserAdapter.BrowserObservation obs,
                                                   DouyinBrowserAdapter.RegionInfo region) {
        LinkedHashMap<String, DouyinCommentItem> out = new LinkedHashMap<>();
        for (DouyinCommentItem item : commentsFromTree(obs.tree(), region, obs.url())) {
            out.putIfAbsent(item.commentKey(), item);
        }
        return new ArrayList<>(out.values());
    }

    public List<DouyinCommentItem> commentsFromExtractedRegion(JsonNode extractRoot, String videoKey) {
        JsonNode results = extractRoot.path("results");
        if (!results.isArray() || results.isEmpty()) {
            return List.of();
        }
        JsonNode items = results.get(0).path("payload").path("items");
        if (!items.isArray()) {
            return List.of();
        }
        List<DouyinCommentItem> out = new ArrayList<>();
        for (JsonNode item : items) {
            if (!"douyin_comment".equals(item.path("itemType").asText(""))) {
                continue;
            }
            String text = clean(item.path("text").asText(""));
            String author = clean(item.path("author").asText(""));
            if (!isStructuredExtractedCommentText(text, author)) {
                continue;
            }
            String href = bestProfileHref(item);
            DouyinCommentItem.ClickTarget target = clickTarget(item.path("bbox"), item.path("href").asText(null));
            String key = stableCommentKey(videoKey, author, href, text);
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("source", "extract_region");
            metadata.put("rawText", text);
            if (item.has("visibleInRegion")) {
                metadata.put("visibleInRegion", item.path("visibleInRegion").asBoolean(false));
            }
            out.add(new DouyinCommentItem(
                    videoKey,
                    key,
                    null,
                    author,
                    href,
                    null,
                    stripAuthorPrefix(text, author),
                    null,
                    null,
                    target,
                    metadata));
        }
        return out;
    }

    public int declaredCommentCountFromExtractedRegion(JsonNode extractRoot) {
        JsonNode results = extractRoot.path("results");
        if (!results.isArray() || results.isEmpty()) {
            return 0;
        }
        JsonNode items = results.get(0).path("payload").path("items");
        if (!items.isArray()) {
            return 0;
        }
        int best = 0;
        for (JsonNode item : items) {
            if (!"comment_count".equals(item.path("itemType").asText(""))) {
                continue;
            }
            best = Math.max(best, item.path("text").asInt(0));
        }
        return best;
    }

    public boolean commentsReachedEndFromExtractedRegion(JsonNode extractRoot) {
        JsonNode results = extractRoot.path("results");
        if (!results.isArray() || results.isEmpty()) {
            return false;
        }
        JsonNode items = results.get(0).path("payload").path("items");
        if (!items.isArray()) {
            return false;
        }
        for (JsonNode item : items) {
            String itemType = item.path("itemType").asText("");
            String text = item.path("text").asText("");
            if ("comment_end".equals(itemType) || commentsReachedEnd(text)) {
                return true;
            }
        }
        return false;
    }

    public boolean commentsEmptyFromExtractedRegion(JsonNode extractRoot) {
        JsonNode results = extractRoot.path("results");
        if (!results.isArray() || results.isEmpty()) {
            return false;
        }
        JsonNode items = results.get(0).path("payload").path("items");
        if (!items.isArray()) {
            return false;
        }
        for (JsonNode item : items) {
            if (commentsEmpty(item.path("text").asText(""))) {
                return true;
            }
        }
        return false;
    }

    public NetworkCommentPage commentsFromNetworkPage(JsonNode page, String fallbackVideoKey) {
        JsonNode body = networkResponseBody(page);
        if (body == null || body.isMissingNode() || body.isNull()) {
            return NetworkCommentPage.empty();
        }
        String videoKey = firstTextValueDeep(body, "aweme_id", "awemeId", "group_id", "groupId", "item_id", "itemId");
        if (videoKey.isBlank()) {
            videoKey = fallbackVideoKey;
        }
        String cursor = firstTextValueDeep(body, "cursor", "current_cursor", "currentCursor", "offset");
        String nextCursor = firstTextValueDeep(body, "next_cursor", "nextCursor", "cursor", "offset");
        boolean hasMoreKnown = firstFieldExistsDeep(body, "has_more", "hasMore", "has_next", "hasNext", "more");
        boolean hasMore = hasMoreKnown
                && firstBooleanValueDeep(body, "has_more", "hasMore", "has_next", "hasNext", "more");
        int declared = firstIntValueDeep(body, "total", "total_count", "totalCount", "comment_total", "commentTotal");

        LinkedHashMap<String, DouyinCommentItem> out = new LinkedHashMap<>();
        Set<String> visited = new HashSet<>();
        collectNetworkCommentObjects(body, videoKey, out, visited);
        return new NetworkCommentPage(
                new ArrayList<>(out.values()),
                declared,
                cursor,
                nextCursor,
                hasMore,
                hasMoreKnown,
                page == null ? "" : page.path("url").asText(""));
    }

    private JsonNode networkResponseBody(JsonNode page) {
        if (page == null || page.isMissingNode() || page.isNull()) {
            return null;
        }
        String body = page.path("body").asText("");
        if (body.isBlank()) {
            body = page.path("responseBody").asText("");
        }
        if (body.isBlank()) {
            return null;
        }
        if (page.path("base64Encoded").asBoolean(false)) {
            try {
                body = new String(Base64.getDecoder().decode(body), StandardCharsets.UTF_8);
            } catch (Exception ignored) {
                return null;
            }
        }
        try {
            return com.fasterxml.jackson.databind.json.JsonMapper.builder().build().readTree(body);
        } catch (Exception ignored) {
            return null;
        }
    }

    private void collectNetworkCommentObjects(
            JsonNode node,
            String videoKey,
            LinkedHashMap<String, DouyinCommentItem> out,
            Set<String> visited) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return;
        }
        if (node.isObject()) {
            String nodeId = objectIdentity(node);
            if (!nodeId.isBlank() && !visited.add(nodeId)) {
                return;
            }
            networkCommentFromObject(node, videoKey).ifPresent(item -> out.putIfAbsent(item.commentKey(), item));
            node.fields().forEachRemaining(entry -> collectNetworkCommentObjects(entry.getValue(), videoKey, out, visited));
            return;
        }
        if (node.isArray()) {
            for (JsonNode child : node) {
                collectNetworkCommentObjects(child, videoKey, out, visited);
            }
        }
    }

    private Optional<DouyinCommentItem> networkCommentFromObject(JsonNode node, String videoKey) {
        String text = clean(firstTextValue(node,
                "text",
                "content",
                "comment_text",
                "commentText",
                "reply_text",
                "replyText"));
        if (!isLikelyNetworkCommentText(text)) {
            return Optional.empty();
        }
        String commentId = firstTextValue(node,
                "cid",
                "comment_id",
                "commentId",
                "reply_id",
                "replyId",
                "id");
        JsonNode user = firstObjectValue(node, "user", "user_info", "userInfo", "author", "author_info", "authorInfo");
        String author = clean(firstTextValue(user,
                "nickname",
                "nick_name",
                "nickName",
                "unique_id",
                "uniqueId",
                "short_id",
                "shortId",
                "name"));
        if (commentId.isBlank() && author.isBlank()) {
            return Optional.empty();
        }
        String secUid = firstTextValue(user, "sec_uid", "secUid");
        String uid = firstTextValue(user, "uid", "user_id", "userId");
        String profileHref = "";
        if (!secUid.isBlank()) {
            profileHref = "https://www.douyin.com/user/" + secUid;
        } else if (!uid.isBlank()) {
            profileHref = "https://www.douyin.com/user/" + uid;
        }
        String avatar = firstUrlFromNode(firstObjectValue(user, "avatar_thumb", "avatarThumb", "avatar_medium", "avatarMedium"));
        Integer likeCount = boxedPositiveInt(firstIntValue(node, "digg_count", "diggCount", "like_count", "likeCount"));
        Integer replyCount = boxedPositiveInt(firstIntValue(node, "reply_comment_total", "replyCommentTotal", "reply_count", "replyCount"));
        String parentId = firstTextValue(node, "reply_to_reply_id", "replyToReplyId", "parent_comment_id", "parentCommentId");
        String key = !commentId.isBlank()
                ? stableNetworkCommentKey(videoKey, commentId)
                : stableCommentKey(videoKey, author, profileHref, text);
        Map<String, Object> metadata = Map.of(
                "source", "network_observed",
                "rawCommentId", commentId,
                "rawUserId", uid,
                "rawSecUid", secUid);
        return Optional.of(new DouyinCommentItem(
                videoKey,
                key,
                parentId.isBlank() ? null : stableNetworkCommentKey(videoKey, parentId),
                author,
                profileHref.isBlank() ? null : profileHref,
                avatar.isBlank() ? null : avatar,
                text,
                likeCount,
                replyCount,
                null,
                metadata));
    }

    private boolean isLikelyNetworkCommentText(String text) {
        String value = clean(text);
        return !value.isBlank()
                && value.length() <= 600
                && !isControlText(value)
                && !value.endsWith("头像")
                && !value.contains("验证码")
                && !value.contains("登录后");
    }

    private String objectIdentity(JsonNode node) {
        String id = firstTextValue(node, "cid", "comment_id", "commentId", "reply_id", "replyId", "id");
        if (!id.isBlank()) {
            return id;
        }
        return "";
    }

    private JsonNode firstObjectValue(JsonNode node, String... names) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        for (String name : names) {
            JsonNode value = node.path(name);
            if (value.isObject()) {
                return value;
            }
        }
        return null;
    }

    private String firstTextValue(JsonNode node, String... names) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return "";
        }
        for (String name : names) {
            JsonNode value = node.path(name);
            if (value.isValueNode() && !value.asText("").isBlank()) {
                return clean(value.asText(""));
            }
        }
        return "";
    }

    private String firstTextValueDeep(JsonNode node, String... names) {
        JsonNode value = firstValueDeep(node, List.of(names), 0);
        if (value != null && value.isValueNode() && !value.asText("").isBlank()) {
            return clean(value.asText(""));
        }
        return "";
    }

    private boolean firstBooleanValue(JsonNode node, String... names) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return false;
        }
        for (String name : names) {
            JsonNode value = node.path(name);
            if (value.isBoolean()) {
                return value.asBoolean(false);
            }
            if (value.isNumber()) {
                return value.asInt(0) > 0;
            }
            if (value.isTextual() && !value.asText("").isBlank()) {
                return "true".equalsIgnoreCase(value.asText("")) || "1".equals(value.asText(""));
            }
        }
        return false;
    }

    private boolean firstFieldExists(JsonNode node, String... names) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return false;
        }
        for (String name : names) {
            if (node.has(name) && !node.path(name).isMissingNode() && !node.path(name).isNull()) {
                return true;
            }
        }
        return false;
    }

    private boolean firstFieldExistsDeep(JsonNode node, String... names) {
        return firstValueDeep(node, List.of(names), 0) != null;
    }

    private boolean firstBooleanValueDeep(JsonNode node, String... names) {
        JsonNode value = firstValueDeep(node, List.of(names), 0);
        if (value == null || value.isMissingNode() || value.isNull()) {
            return false;
        }
        if (value.isBoolean()) {
            return value.asBoolean(false);
        }
        if (value.isNumber()) {
            return value.asInt(0) > 0;
        }
        if (value.isTextual() && !value.asText("").isBlank()) {
            return "true".equalsIgnoreCase(value.asText("")) || "1".equals(value.asText(""));
        }
        return false;
    }

    private int firstIntValue(JsonNode node, String... names) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return 0;
        }
        for (String name : names) {
            JsonNode value = node.path(name);
            if (value.isNumber()) {
                return value.asInt(0);
            }
            if (value.isTextual() && value.asText("").matches("\\d+")) {
                return Integer.parseInt(value.asText(""));
            }
        }
        return 0;
    }

    private int firstIntValueDeep(JsonNode node, String... names) {
        JsonNode value = firstValueDeep(node, List.of(names), 0);
        if (value == null || value.isMissingNode() || value.isNull()) {
            return 0;
        }
        if (value.isNumber()) {
            return value.asInt(0);
        }
        if (value.isTextual() && value.asText("").matches("\\d+")) {
            return Integer.parseInt(value.asText(""));
        }
        return 0;
    }

    private JsonNode firstValueDeep(JsonNode node, List<String> names, int depth) {
        if (node == null || node.isMissingNode() || node.isNull() || depth > 5) {
            return null;
        }
        if (node.isObject()) {
            for (String name : names) {
                JsonNode direct = node.path(name);
                if (!direct.isMissingNode() && !direct.isNull()) {
                    return direct;
                }
            }
            var fields = node.fields();
            while (fields.hasNext()) {
                JsonNode found = firstValueDeep(fields.next().getValue(), names, depth + 1);
                if (found != null) {
                    return found;
                }
            }
            return null;
        }
        if (node.isArray()) {
            for (JsonNode child : node) {
                JsonNode found = firstValueDeep(child, names, depth + 1);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private String firstUrlFromNode(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return "";
        }
        String direct = firstTextValue(node, "url", "uri");
        if (!direct.isBlank() && direct.startsWith("http")) {
            return direct;
        }
        JsonNode urls = node.path("url_list");
        if (!urls.isArray()) {
            urls = node.path("urlList");
        }
        if (urls.isArray()) {
            for (JsonNode url : urls) {
                String value = url.asText("");
                if (value.startsWith("http")) {
                    return value;
                }
            }
        }
        return "";
    }

    private Integer boxedPositiveInt(int value) {
        return value > 0 ? value : null;
    }

    public int declaredCommentCount(String tree) {
        return declaredCommentCount(tree, null);
    }

    public int declaredCommentCount(String tree, DouyinBrowserAdapter.RegionInfo region) {
        int best = 0;
        if (tree == null || tree.isBlank()) {
            return best;
        }
        Matcher matcher = COUNT_AFTER_LABEL.matcher(tree);
        while (matcher.find()) {
            best = Math.max(best, parseCount(matcher.group(1), matcher.group(2)));
        }
        matcher = COUNT_BEFORE_LABEL.matcher(tree);
        while (matcher.find()) {
            best = Math.max(best, parseCount(matcher.group(1), matcher.group(2)));
        }
        return best;
    }

    public boolean commentsReachedEnd(String tree) {
        if (tree == null) {
            return false;
        }
        String normalized = tree.replaceAll("\\s+", "");
        return normalized.contains("暂时没有更多评论")
                || normalized.contains("没有更多评论")
                || normalized.contains("到底了")
                || normalized.contains("已展示全部评论");
    }

    public boolean commentsReachedEnd(String tree, DouyinBrowserAdapter.RegionInfo region) {
        if (tree == null || region == null) {
            return commentsReachedEnd(tree);
        }
        return parseLines(tree).stream()
                .filter(line -> overlapsRegion(line, region))
                .map(TreeLine::name)
                .map(value -> value == null ? "" : value.replaceAll("\\s+", ""))
                .anyMatch(value -> value.contains("暂时没有更多评论")
                        || value.contains("没有更多评论")
                        || value.contains("到底了")
                        || value.contains("已展示全部评论"));
    }

    public boolean commentsEmpty(String tree) {
        if (tree == null) {
            return false;
        }
        String normalized = tree.replaceAll("\\s+", "");
        return normalized.contains("暂时没有评论")
                || normalized.contains("还没有评论")
                || normalized.contains("暂无评论")
                || normalized.contains("暂无相关评论");
    }

    public boolean commentsEmpty(String tree, DouyinBrowserAdapter.RegionInfo region) {
        if (tree == null || region == null) {
            return commentsEmpty(tree);
        }
        return parseLines(tree).stream()
                .filter(line -> overlapsRegion(line, region))
                .map(TreeLine::name)
                .map(value -> value == null ? "" : value.replaceAll("\\s+", ""))
                .anyMatch(value -> value.contains("暂时没有评论")
                        || value.contains("还没有评论")
                        || value.contains("暂无评论")
                        || value.contains("暂无相关评论"));
    }

    public Optional<ExtensionPoint> inferDmInputPoint(DouyinBrowserAdapter.BrowserObservation obs) {
        return parseLines(obs.tree()).stream()
                .filter(line -> !line.role().equalsIgnoreCase("searchbox"))
                .filter(line -> line.y() > Math.max(140, obs.viewportHeight() * 0.35))
                .filter(line -> line.role().equalsIgnoreCase("textbox")
                        || line.name().contains("输入消息")
                        || line.name().contains("发送消息"))
                .map(line -> new ExtensionPoint(line.centerX(), line.centerY()))
                .findFirst();
    }

    private List<DouyinCommentItem> commentsFromTree(String tree,
                                                     DouyinBrowserAdapter.RegionInfo region,
                                                     String videoKey) {
        List<TreeLine> allLines = parseLines(tree);
        List<TreeLine> lines = parseLines(tree).stream()
                .filter(line -> overlapsRegion(line, region))
                .toList();
        LinkedHashMap<String, DouyinCommentItem> out = new LinkedHashMap<>();
        for (int i = 0; i < lines.size(); i++) {
            TreeLine line = lines.get(i);
            if (isLikelyAuthorLine(line)) {
                continue;
            }
            if (!isPossibleA11yCommentText(line.name())) {
                continue;
            }
            TreeLine author = nearestAuthor(lines, i).orElse(null);
            if (author == null) {
                continue;
            }
            String authorName = author.name();
            if (!authorName.isBlank() && clean(authorName).equals(clean(line.name()))) {
                continue;
            }
            String commentText = stripAuthorPrefix(line.name(), authorName);
            if (!isPossibleA11yCommentText(commentText)) {
                continue;
            }
            DouyinCommentItem.ClickTarget authorTarget = !isHighConfidenceAuthorTarget(author)
                    || !isVisibleViewportTarget(author)
                    ? null
                    : new DouyinCommentItem.ClickTarget(
                    (double) author.centerX(),
                    (double) author.centerY(),
                    author.ref(),
                    Map.of("x", author.x(), "y", author.y(), "width", author.w(), "height", author.h()));
            String key = stableCommentKey(videoKey, authorName, null, commentText);
            out.putIfAbsent(key, new DouyinCommentItem(
                    videoKey,
                    key,
                    null,
                    authorName,
                    null,
                    null,
                    commentText,
                    null,
                    null,
                    authorTarget,
                    Map.of("source", "a11y_tree", "commentRef", line.ref())));
        }
        return new ArrayList<>(out.values());
    }

    private boolean isPossibleA11yCommentText(String text) {
        String value = clean(text);
        if (value.length() < 4 || value.length() > 600) {
            return false;
        }
        if (value.matches("^转发\\s*[·・•]?$")) {
            return false;
        }
        String normalized = value.toLowerCase(Locale.ROOT);
        if (isControlText(value)
                || normalized.contains("搜索")
                || normalized.contains("筛选")
                || value.endsWith("头像")
                || value.contains("头像")) {
            return false;
        }
        return value.matches(".*[\\p{L}\\p{N}].*");
    }

    private Optional<TreeLine> nearestAuthor(List<TreeLine> lines, int commentIndex) {
        TreeLine comment = lines.get(commentIndex);
        for (int i = commentIndex - 1; i >= 0 && i >= commentIndex - 6; i--) {
            TreeLine candidate = lines.get(i);
            if (candidate.centerY() > comment.centerY() + 4) {
                continue;
            }
            if (isLikelyAuthorLine(candidate) && candidate.centerX() <= comment.centerX() + 80) {
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }

    private boolean overlapsRegion(TreeLine line, DouyinBrowserAdapter.RegionInfo region) {
        double centerX = line.centerX();
        double centerY = line.centerY();
        boolean centerInside = centerX >= region.x()
                && centerX <= region.x() + region.width()
                && centerY >= region.y()
                && centerY <= region.y() + region.height();
        if (centerInside) {
            return true;
        }
        double left = Math.max(line.x(), region.x());
        double right = Math.min(line.x() + line.w(), region.x() + region.width());
        double top = Math.max(line.y(), region.y());
        double bottom = Math.min(line.y() + line.h(), region.y() + region.height());
        double overlap = Math.max(0, right - left) * Math.max(0, bottom - top);
        double area = Math.max(1, line.w() * line.h());
        return overlap / area >= 0.45d;
    }

    private List<TreeLine> parseLines(String tree) {
        if (tree == null || tree.isBlank()) {
            return List.of();
        }
        List<TreeLine> out = new ArrayList<>();
        for (String raw : tree.split("\\R")) {
            Matcher matcher = TREE_LINE.matcher(raw.trim());
            if (matcher.matches()) {
                out.add(new TreeLine(
                        matcher.group(1),
                        matcher.group(2),
                        clean(matcher.group(4)),
                        Integer.parseInt(matcher.group(5)),
                        Integer.parseInt(matcher.group(6)),
                        Integer.parseInt(matcher.group(7)),
                        Integer.parseInt(matcher.group(8))));
            }
        }
        return out;
    }

    private boolean isLikelyCommentText(String text) {
        String value = clean(text);
        if (value.length() < 2 || value.length() > 600) {
            return false;
        }
        String normalized = value.toLowerCase(Locale.ROOT);
        if (isControlText(value)
                || normalized.contains("搜索")
                || normalized.contains("筛选")
                || value.endsWith("头像")
                || value.contains("头像")) {
            return false;
        }
        return value.contains("？")
                || value.contains("?")
                || value.contains("。")
                || value.contains("！")
                || value.contains("!")
                || value.contains("，")
                || value.contains(",")
                || (value.length() >= 14 && !isLikelyCompactUserName(value));
    }

    private boolean isStructuredExtractedCommentText(String text, String author) {
        String value = clean(text);
        if (value.isBlank() || value.length() > 600) {
            return false;
        }
        String cleanAuthor = clean(author);
        if (cleanAuthor.isBlank() && value.contains("#")) {
            return false;
        }
        if (!cleanAuthor.isBlank() && clean(value).equals(cleanAuthor)) {
            return false;
        }
        if (value.endsWith("头像")
                || value.contains("头像")
                || COMMENT_TIME_LOCATION.matcher(value).matches()
                || GENERIC_USER_ID.matcher(value).matches()
                || value.equals("评论")
                || value.equals("详情")
                || value.equals("TA的作品")
                || value.equals("问AI")
                || value.equals("回复")
                || value.equals("关注")
                || value.equals("已关注")
                || value.equals("互相关注")
                || value.equals("回关")
                || value.equals("私信")
                || value.equals("发私信")
                || value.equals("条回复")
                || value.startsWith("展开")
                || value.equals("点赞")
                || value.equals("分享")
                || value.equals("收藏")
                || value.equals("留下你的精彩评论吧")
                || value.equals("说点什么")
                || value.equals("发表评论")
                || value.equals("没有更多评论")
                || value.equals("暂时没有更多评论")
                || value.equals("已展示全部评论")
                || value.equals("到底了")
                || value.equals("大家都在搜：")
                || value.equals("Stop Agent")
                || value.matches("^\\d+(?:\\.\\d+)?([万wWkK千])?$")
                || value.matches("^\\d+条?回复$")) {
            return false;
        }
        return true;
    }

    private boolean isLikelyAuthorLine(TreeLine line) {
        String role = line.role().toLowerCase(Locale.ROOT);
        if (!role.equals("link")) {
            return false;
        }
        return isLikelyAuthor(line.name());
    }

    private boolean isHighConfidenceAuthorTarget(TreeLine line) {
        return line != null
                && line.role().equalsIgnoreCase("link")
                && isLikelyAuthor(line.name());
    }

    private boolean isVisibleViewportTarget(TreeLine line) {
        return line != null
                && line.x() >= 0
                && line.y() >= 0
                && line.y() <= 1_200
                && line.w() > 0
                && line.h() > 0;
    }

    private boolean isStrongStandaloneCommentText(String text) {
        String value = clean(text);
        if (value.length() < 12 || isLikelyCompactUserName(value)) {
            return false;
        }
        return value.contains("？")
                || value.contains("?")
                || value.contains("。")
                || value.contains("！")
                || value.contains("!")
                || value.contains("，")
                || value.contains(",");
    }

    private boolean isLikelyAuthor(String text) {
        String value = clean(text);
        if (value.isBlank() || value.length() > 60 || isControlText(value)) {
            return false;
        }
        if (value.endsWith("头像")
                || value.matches("^[@·。:：\\-—]+$")
                || value.matches("^\\d{1,2}:\\d{2}$")
                || value.matches("^用户\\d{5,}$")) {
            return false;
        }
        if (isLikelyCommentText(value) && !isLikelyCompactUserName(value)) {
            return false;
        }
        return value.matches(".*[\\p{L}\\p{N}_-].*");
    }

    private boolean isLikelyCompactUserName(String text) {
        String value = clean(text);
        if (value.isBlank()
                || value.length() > 28
                || isControlText(value)
                || value.endsWith("头像")
                || value.matches("^用户\\d{5,}$")) {
            return false;
        }
        if (value.contains("？") || value.contains("?") || value.contains("。")
                || value.contains("！") || value.contains("!") || value.contains("，") || value.contains(",")) {
            return false;
        }
        if (value.contains("token")
                || value.contains("电脑")) {
            return false;
        }
        return value.matches(".*[\\p{L}\\p{N}_-].*");
    }

    private boolean isControlText(String text) {
        String value = clean(text);
        return value.isBlank()
                || COMMENT_TIME_LOCATION.matcher(value).matches()
                || GENERIC_USER_ID.matcher(value).matches()
                || BRACKET_EMOJI_ONLY.matcher(value).matches()
                || value.equals("评论")
                || value.equals("详情")
                || value.equals("TA的作品")
                || value.equals("问AI")
                || value.equals("回复")
                || value.equals("关注")
                || value.equals("已关注")
                || value.equals("互相关注")
                || value.equals("回关")
                || value.equals("私信")
                || value.equals("发私信")
                || value.equals("条回复")
                || value.startsWith("展开")
                || value.equals("点赞")
                || value.equals("分享")
                || value.equals("收藏")
                || value.equals("留下你的精彩评论吧")
                || value.equals("说点什么")
                || value.equals("发表评论")
                || value.equals("没有更多评论")
                || value.equals("暂时没有更多评论")
                || value.equals("已展示全部评论")
                || value.equals("到底了")
                || value.equals("大家都在搜：")
                || value.equals("Stop Agent")
                || value.matches("^\\d+(?:\\.\\d+)?([万wWkK千])?$")
                || value.matches("^\\d+条?回复$");
    }

    private int parseCount(String number, String unit) {
        try {
            double value = Double.parseDouble(number.replace(",", ""));
            String normalizedUnit = unit == null ? "" : unit.toLowerCase(Locale.ROOT);
            if ("万".equals(unit) || "w".equals(normalizedUnit)) {
                value *= 10_000d;
            } else if ("千".equals(unit) || "k".equals(normalizedUnit)) {
                value *= 1_000d;
            }
            return (int) Math.round(value);
        } catch (Exception ignored) {
            return 0;
        }
    }

    private DouyinCommentItem.ClickTarget clickTarget(JsonNode bbox, String href) {
        if (bbox == null || bbox.isMissingNode()) {
            return null;
        }
        double x = bbox.path("x").asDouble(Double.NaN);
        double y = bbox.path("y").asDouble(Double.NaN);
        double w = bbox.path("width").asDouble(Double.NaN);
        double h = bbox.path("height").asDouble(Double.NaN);
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(w) || !Double.isFinite(h)) {
            return null;
        }
        return new DouyinCommentItem.ClickTarget(
                x + w / 2.0d,
                y + h / 2.0d,
                null,
                Map.of("x", x, "y", y, "width", w, "height", h, "href", href == null ? "" : href));
    }

    private String bestProfileHref(JsonNode item) {
        String href = item.path("href").asText("");
        if (looksLikeProfileUrl(href)) {
            return href;
        }
        JsonNode hrefs = item.path("hrefs");
        if (hrefs.isArray()) {
            for (JsonNode node : hrefs) {
                String candidate = node.asText("");
                if (looksLikeProfileUrl(candidate)) {
                    return candidate;
                }
            }
        }
        return null;
    }

    private boolean looksLikeProfileUrl(String href) {
        if (href == null || href.isBlank()) {
            return false;
        }
        try {
            URI uri = URI.create(href);
            String host = uri.getHost() == null ? "" : uri.getHost();
            return host.contains("douyin.com") && uri.getPath() != null && uri.getPath().contains("/user");
        } catch (Exception ignored) {
            return href.contains("douyin.com") && href.contains("/user");
        }
    }

    private String stripAuthorPrefix(String text, String author) {
        String cleanText = clean(text);
        String cleanAuthor = clean(author);
        if (!cleanAuthor.isBlank() && cleanText.startsWith(cleanAuthor)) {
            cleanText = clean(cleanText.substring(cleanAuthor.length()));
        }
        return cleanText
                .replaceFirst("^转发\\s*[·・•]\\s*", "")
                .trim();
    }

    private String stableCommentKey(String videoKey, String author, String profileHref, String text) {
        String identity = clean(author);
        if (identity.isBlank()) {
            identity = clean(profileHref);
        }
        return "douyin-comment-" + Integer.toHexString((normalizeVideoKey(videoKey) + "|" + identity + "|" + clean(text)).hashCode());
    }

    private String stableNetworkCommentKey(String videoKey, String commentId) {
        return "douyin-comment-" + Integer.toHexString((normalizeVideoKey(videoKey) + "|cid:" + clean(commentId)).hashCode());
    }

    private String normalizeVideoKey(String videoKey) {
        String value = clean(videoKey);
        if (value.isBlank()) {
            return "";
        }
        try {
            URI uri = URI.create(value);
            String modalId = queryParam(uri, "modal_id");
            if (!modalId.isBlank()) {
                return "modal_id=" + modalId;
            }
            String awemeId = queryParam(uri, "aweme_id");
            if (!awemeId.isBlank()) {
                return "aweme_id=" + awemeId;
            }
            return uri.getPath() == null ? value : uri.getPath();
        } catch (Exception ignored) {
            return value;
        }
    }

    private String queryParam(URI uri, String name) {
        String query = uri.getRawQuery();
        if (query == null || query.isBlank()) {
            return "";
        }
        for (String part : query.split("&")) {
            int eq = part.indexOf('=');
            String key = eq >= 0 ? part.substring(0, eq) : part;
            if (name.equals(key)) {
                return eq >= 0 ? part.substring(eq + 1) : "";
            }
        }
        return "";
    }

    private String clean(String text) {
        return text == null ? "" : text.replaceAll("\\s+", " ").trim();
    }

    public record ExtensionPoint(double x, double y) {
    }

    public record NetworkCommentPage(
            List<DouyinCommentItem> comments,
            int declaredCommentCount,
            String cursor,
            String nextCursor,
            boolean hasMore,
            boolean hasMoreKnown,
            String sourceUrl) {
        public NetworkCommentPage {
            comments = comments == null ? List.of() : List.copyOf(comments);
            cursor = cursor == null ? "" : cursor;
            nextCursor = nextCursor == null ? "" : nextCursor;
            sourceUrl = sourceUrl == null ? "" : sourceUrl;
        }

        static NetworkCommentPage empty() {
            return new NetworkCommentPage(List.of(), 0, "", "", false, false, "");
        }
    }

    private record TreeLine(String role, String ref, String name, int x, int y, int w, int h) {
        int centerX() {
            return x + w / 2;
        }

        int centerY() {
            return y + h / 2;
        }
    }

}
