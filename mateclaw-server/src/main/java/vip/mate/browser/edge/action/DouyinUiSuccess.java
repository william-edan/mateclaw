package vip.mate.browser.edge.action;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * Success payload for {@code douyin_ui}. {@code op} echoes the requested op and
 * {@code detail} is a short status (e.g. opened / already_open / sorted:最多点赞 /
 * paused N). Extra fields tolerated.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NONE)
@JsonIgnoreProperties(ignoreUnknown = true)
public record DouyinUiSuccess(
        String op,
        String detail
) implements ActionSuccessPayload {
}
