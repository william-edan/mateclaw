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
        String reason
) implements ActionSuccessPayload {
}
