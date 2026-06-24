package vip.mate.browser.edge.action;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * Success payload for {@code douyin_open_video}. {@code clicked} reports whether
 * a video cover card was found and clicked; {@code index}/{@code total} the chosen
 * card and how many video cards were available; {@code title} a short label.
 * Extra fields tolerated.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NONE)
@JsonIgnoreProperties(ignoreUnknown = true)
public record DouyinOpenVideoSuccess(
        Boolean clicked,
        Integer index,
        Integer total,
        String title,
        String reason,
        // 扩展据点开视频后评论首屏 comment/list 回包确认视频已打开(接口驱动)。null=旧扩展无此字段。
        Boolean openVideoConfirmed
) implements ActionSuccessPayload {
}
