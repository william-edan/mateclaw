package vip.mate.browser.edge.action;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

@JsonTypeInfo(use = JsonTypeInfo.Id.NONE)
public record OpenAuthorFromCommentSuccess(
        @JsonProperty("href") String href,
        @JsonProperty("author") String author,
        @JsonProperty("tabId") Long tabId
) implements ActionSuccessPayload {
}
