package vip.mate.browser.edge.action;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

@JsonTypeInfo(use = JsonTypeInfo.Id.NONE)
public record ExtractRegionPayload(
        @JsonProperty("regionKey") String regionKey,
        @JsonProperty("maxItems") Integer maxItems,
        @JsonProperty("startIndex") Integer startIndex
) implements ActionPayload {

    public ExtractRegionPayload(String regionKey, Integer maxItems) {
        this(regionKey, maxItems, 0);
    }

    public ExtractRegionPayload {
        if (regionKey == null || regionKey.isBlank()) {
            throw new IllegalArgumentException("regionKey is required");
        }
        if (maxItems == null) {
            maxItems = 80;
        }
        if (maxItems < 1 || maxItems > 500) {
            throw new IllegalArgumentException("maxItems must be between 1 and 500");
        }
        if (startIndex == null) {
            startIndex = 0;
        }
        if (startIndex < 0) {
            throw new IllegalArgumentException("startIndex must be >= 0");
        }
    }
}
