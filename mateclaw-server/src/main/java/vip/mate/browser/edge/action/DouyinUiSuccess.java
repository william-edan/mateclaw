package vip.mate.browser.edge.action;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * Success payload for {@code douyin_ui}. {@code op} echoes the requested op and
 * {@code detail} is a short status (e.g. opened / already_open / sorted:最多点赞 /
 * paused N). Extra fields tolerated.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NONE)
@JsonIgnoreProperties(ignoreUnknown = true)
public record DouyinUiSuccess(
        String op,
        String detail,
        // 排序(op=sort)接口驱动诊断:sortConfirmed=据 general/search 回包确认排序重载;
        // sortNetMs=等回包实际耗时;netDebug=未命中时的观察器状态(排查)。null=旧扩展/非sort。
        Boolean sortConfirmed,
        Long sortNetMs,
        JsonNode netDebug
) implements ActionSuccessPayload {
}
