package vip.mate.browser.edge.action;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * Success payload for the {@code douyin_search} action. {@code typed}/{@code clicked}
 * report whether the keyword went into the search box and the search button was
 * clicked; the rest are diagnostics. Extra fields tolerated (ignoreUnknown).
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NONE)
@JsonIgnoreProperties(ignoreUnknown = true)
public record DouyinSearchSuccess(
        Boolean typed,
        Boolean clicked,
        String value,
        String inputSelector,
        String buttonSelector,
        String reason
) implements ActionSuccessPayload {
}
