package vip.mate.browser.edge.action;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import java.util.List;
import java.util.Map;

@JsonTypeInfo(use = JsonTypeInfo.Id.NONE)
@JsonIgnoreProperties(ignoreUnknown = true)
public record DouyinCommentNetworkSuccess(
        String op,
        List<Page> pages,
        Integer drainedCount,
        Integer capturedCount,
        Integer droppedCount,
        Boolean capturing,
        Long expiresAtMs,
        Integer eventsSeen,
        Integer responseEventsSeen,
        Integer loadingFinishedSeen,
        Integer loadingFailedSeen,
        Integer pendingResponses,
        Integer inflight,
        Map<String, Integer> eventMethods,
        List<Map<String, Object>> rawSamples,
        List<Map<String, Object>> childSessions,
        Boolean pageHookInstalled,
        String pageHookMessage,
        Integer pageHookDrainCount,
        Integer pageHookErrorCount
) implements ActionSuccessPayload {

    public DouyinCommentNetworkSuccess(String op, List<?> pages, Integer capturedCount, Integer droppedCount) {
        this(op,
                pages == null ? List.of() : pages.stream()
                        .filter(Page.class::isInstance)
                        .map(Page.class::cast)
                        .toList(),
                null,
                capturedCount,
                droppedCount,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null);
    }

    public DouyinCommentNetworkSuccess {
        if (op == null || op.isBlank()) {
            op = "";
        }
        pages = pages == null ? List.of() : List.copyOf(pages);
        if (drainedCount == null) {
            drainedCount = pages.size();
        }
        if (capturedCount == null) {
            capturedCount = drainedCount;
        }
        if (droppedCount == null) {
            droppedCount = 0;
        }
        if (capturing == null) {
            capturing = false;
        }
        if (expiresAtMs == null) {
            expiresAtMs = 0L;
        }
        if (eventsSeen == null) {
            eventsSeen = 0;
        }
        if (responseEventsSeen == null) {
            responseEventsSeen = 0;
        }
        if (loadingFinishedSeen == null) {
            loadingFinishedSeen = 0;
        }
        if (loadingFailedSeen == null) {
            loadingFailedSeen = 0;
        }
        if (pendingResponses == null) {
            pendingResponses = 0;
        }
        if (inflight == null) {
            inflight = 0;
        }
        eventMethods = eventMethods == null ? Map.of() : Map.copyOf(eventMethods);
        rawSamples = rawSamples == null ? List.of() : List.copyOf(rawSamples);
        childSessions = childSessions == null ? List.of() : List.copyOf(childSessions);
        if (pageHookInstalled == null) {
            pageHookInstalled = false;
        }
        if (pageHookMessage == null) {
            pageHookMessage = "";
        }
        if (pageHookDrainCount == null) {
            pageHookDrainCount = 0;
        }
        if (pageHookErrorCount == null) {
            pageHookErrorCount = 0;
        }
    }

    public record Page(
            String url,
            String requestId,
            Integer status,
            String body,
            Boolean base64Encoded,
            Long capturedAtMs
    ) {
        public Page {
            if (url == null) {
                url = "";
            }
            if (requestId == null) {
                requestId = "";
            }
            if (body == null) {
                body = "";
            }
            if (base64Encoded == null) {
                base64Encoded = false;
            }
        }
    }
}
