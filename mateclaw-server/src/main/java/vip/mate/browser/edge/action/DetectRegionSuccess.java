package vip.mate.browser.edge.action;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

@JsonTypeInfo(use = JsonTypeInfo.Id.NONE)
public record DetectRegionSuccess(
        @JsonProperty("regionKey") String regionKey,
        RegisterRegionPayload.Rect rect,
        @JsonProperty("safePoint") SafePoint safePoint,
        String source,
        // —— 状态探测家族(扩展 detect_region 的 STATE_REGION_KEYS:douyin.search-results / video-open /
        // dm-page / profile / follow-state)复用 detect_region 通道时携带:页面是否处于某状态(后台/非聚焦
        // tab 的 a11y observe 必空,改用这条纯 page DOM 探测)。这类探测没有"可点区域",故 rect 为 null,
        // 后端只读 ready。普通"区域探测"(返回可点 rect)时这三个字段为 null。新字段不加会被 Jackson
        // 静默丢弃(见 DouyinSearchSuccess 等同款坑),故必须显式声明在 record 上。
        @JsonProperty("ready") Boolean ready,
        @JsonProperty("url") String url,
        @JsonProperty("reason") String reason
) implements ActionSuccessPayload {

    public DetectRegionSuccess {
        if (regionKey == null || regionKey.isBlank()) {
            throw new IllegalArgumentException("regionKey is required");
        }
        // rect 仅在"区域探测"(返回可点 rect 供后续 CDP 点击)时必有;"状态探测"只回 ready、无 rect,
        // 故不再强制 rect 非空——评论区探测路径 detectRuntimeCommentRegion 本就自行校验 rect 有限性,放宽安全。
        if (source == null || source.isBlank()) {
            source = "dom";
        }
    }

    public record SafePoint(double x, double y) {
    }
}
