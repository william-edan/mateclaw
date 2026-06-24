package vip.mate.browser.edge.action;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

@JsonTypeInfo(use = JsonTypeInfo.Id.NONE)
public record ClickProfileActionSuccess(
        @JsonProperty("label") String label,
        // 关注:扩展据 commit/follow 回包 status_code==0 确认关注成功(接口驱动)。null=旧扩展/非关注。
        @JsonProperty("followConfirmed") Boolean followConfirmed
) implements ActionSuccessPayload {
}
