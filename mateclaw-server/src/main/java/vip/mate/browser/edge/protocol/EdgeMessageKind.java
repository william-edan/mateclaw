package vip.mate.browser.edge.protocol;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum EdgeMessageKind {
    // -----------------------------------------------------------------
    // Protocol v1.0 — handshake + liveness (Phase 1)
    // -----------------------------------------------------------------
    HELLO("hello"),
    HELLO_ACK("hello.ack"),
    HEARTBEAT("heartbeat"),
    HEARTBEAT_ACK("heartbeat.ack"),
    PING("ping"),
    PONG("pong"),
    ERROR("error"),

    // -----------------------------------------------------------------
    // Protocol v1.1 — atomic browser actions (Phase 2 P-stream)
    //
    // CP → NH → Ext: action.execute / action.cancel
    // Ext → NH → CP: action.result
    //
    // Every envelope addressed at a tab carries a `tab_ref` field
    // ("main" | "active" | <int>); see docs/specs/edge-protocol.md.
    // -----------------------------------------------------------------
    ACTION_EXECUTE("action.execute"),
    ACTION_RESULT("action.result"),
    ACTION_CANCEL("action.cancel"),

    // -----------------------------------------------------------------
    // Protocol v1.1 — visual indicators (cursor / glow / stop button)
    //
    // tool_use_hide / tool_use_show flank screenshot capture so the
    // overlay never appears in the model's screenshot.
    // -----------------------------------------------------------------
    INDICATOR_SHOW("indicator.show"),
    INDICATOR_HIDE("indicator.hide"),
    INDICATOR_CURSOR("indicator.cursor"),
    INDICATOR_TOOL_USE_HIDE("indicator.tool_use_hide"),
    INDICATOR_TOOL_USE_SHOW("indicator.tool_use_show"),
    /** User clicked the in-page stop button — flows Ext → NH → CP. */
    INDICATOR_STOP_CLICKED("indicator.stop_clicked"),

    // -----------------------------------------------------------------
    // Protocol v1.1 — accessibility tree snapshot (grounding source)
    // -----------------------------------------------------------------
    A11Y_SNAPSHOT_REQUEST("a11y.snapshot.request"),
    A11Y_SNAPSHOT_RESPONSE("a11y.snapshot.response"),

    // -----------------------------------------------------------------
    // Protocol v1.2 — screenshot capture (Phase 3 T3.2)
    //
    // CP → NH → Ext: screenshot.capture.request
    // Ext → NH → CP: screenshot.capture.response
    // Response carries base64-encoded PNG; large payloads (>500 KB at the
    // 1MB NM cap) should fail Failure(SCREENSHOT_TOO_LARGE) rather than
    // truncate.
    // -----------------------------------------------------------------
    SCREENSHOT_CAPTURE_REQUEST("screenshot.capture.request"),
    SCREENSHOT_CAPTURE_RESPONSE("screenshot.capture.response"),

    // -----------------------------------------------------------------
    // Protocol v2 — Browser Runtime structured action/observation/artifact
    // envelopes. These are declared now so Control Plane, Native Host, and
    // Extension can migrate without scattering raw strings.
    // -----------------------------------------------------------------
    BROWSER_ACTION_REQUEST("browser.action.request"),
    BROWSER_ACTION_RESULT("browser.action.result"),
    BROWSER_OBSERVATION_CAPTURE("browser.observation.capture"),
    BROWSER_OBSERVATION_RESULT("browser.observation.result"),
    BROWSER_ARTIFACT_UPLOAD_CHUNK("browser.artifact.upload_chunk"),
    BROWSER_TELEMETRY_BATCH("browser.telemetry.batch"),
    BROWSER_HUMAN_TAKEOVER("browser.human.takeover"),

    // -----------------------------------------------------------------
    // Protocol v1.1 — unsolicited page-lifecycle events from Ext → CP
    // -----------------------------------------------------------------
    EVENT_PAGE_NAVIGATED("event.page.navigated"),
    EVENT_TAB_CLOSED("event.tab.closed"),

    // -----------------------------------------------------------------
    // Connection control — CP → NH/bridge → Ext:后端主动请求扩展断开(用户在桌面点"断开连接")。
    // 桌面 SPA 在 Electron 内够不到 Chrome 扩展,无法 chrome.runtime 直发 unpair,故经此 session
    // 下行;扩展收到后置"用户已断开"闩 + 断开 loopback,且不再自动重连。
    // -----------------------------------------------------------------
    CONNECTION_DISCONNECT("connection.disconnect"),

    /** Unknown wire kind. Forward-compat: receivers warn-and-drop, do not close. */
    UNKNOWN("__unknown__");

    private final String wire;

    EdgeMessageKind(String wire) { this.wire = wire; }

    @JsonValue
    public String wire() { return wire; }

    @JsonCreator
    public static EdgeMessageKind fromWire(String s) {
        if (s == null) return UNKNOWN;
        for (EdgeMessageKind k : values()) {
            if (k.wire.equals(s)) return k;
        }
        return UNKNOWN;
    }
}
