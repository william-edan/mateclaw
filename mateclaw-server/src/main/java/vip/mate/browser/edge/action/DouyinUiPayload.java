package vip.mate.browser.edge.action;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * Payload for {@code douyin_ui}: background-capable in-page DOM driver for the
 * Douyin steps a plain CDP click/key can't do off-screen.
 * {@code op} ∈ {@code sort | open_comments | pause}; {@code label} is the sort
 * option text for {@code op=sort} (e.g. 最多点赞 / 最新发布 / 综合排序).
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NONE)
public record DouyinUiPayload(
        @JsonProperty("op") String op,
        @JsonProperty("label") String label
) implements ActionPayload {

    public DouyinUiPayload {
        op = op == null ? "" : op.trim();
        if (op.isEmpty()) {
            throw new IllegalArgumentException("op is required");
        }
        label = label == null ? "" : label.trim();
    }
}
