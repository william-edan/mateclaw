package vip.mate.browser.edge.action;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * Payload for {@code douyin_open_video}: open the {@code index}-th VIDEO result
 * card by clicking its cover element in-page (DOM). The handler locates video
 * cover cards (cursor:pointer cover imgs in the results region that carry a
 * MM:SS duration, i.e. videos — image-text "图文" posts are skipped), orders
 * them top-left, and clicks the index-th. Deterministic (clicks the real card
 * element, never a guessed coordinate that could land on a neighbour) +
 * background-capable. 0-based.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NONE)
public record DouyinOpenVideoPayload(
        @JsonProperty("index") int index
) implements ActionPayload {

    public DouyinOpenVideoPayload {
        if (index < 0) {
            index = 0;
        }
    }
}
