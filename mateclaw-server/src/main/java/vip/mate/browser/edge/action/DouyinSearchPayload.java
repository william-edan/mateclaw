package vip.mate.browser.edge.action;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * Payload for the {@code douyin_search} action: drive Douyin's real search flow
 * entirely in-page (DOM) — type {@code keyword} into the React-controlled search
 * input ([data-e2e="searchbar-input"]) and click the search button
 * ([data-e2e="searchbar-button"]). Background-capable (no CDP input / no
 * URL-direct), mirrors a real user typing + clicking search.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NONE)
public record DouyinSearchPayload(
        @JsonProperty("keyword") String keyword
) implements ActionPayload {

    public DouyinSearchPayload {
        keyword = keyword == null ? "" : keyword.trim();
        if (keyword.isEmpty()) {
            throw new IllegalArgumentException("keyword is required");
        }
    }
}
