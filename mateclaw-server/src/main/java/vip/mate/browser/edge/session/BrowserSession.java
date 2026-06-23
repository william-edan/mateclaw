package vip.mate.browser.edge.session;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import org.springframework.web.socket.WebSocketSession;

import java.time.Instant;
import java.util.Set;

/**
 * Live, in-memory representation of one Browser Agent edge session.
 * One subject -> at most one active session in Phase 1.
 *
 * <p>"Subject" carries either the JWT subject (username) or the PAT's
 * {@code userId.toString()}, depending on which auth path established the
 * session. Long-typed user identity is introduced in Phase 4 when sessions
 * become DB-persistent.
 */
@Data
@Builder
@AllArgsConstructor
public class BrowserSession {

    /** Server-issued opaque id, communicated to Native Host via hello.ack. */
    private final String id;

    /**
     * Authenticated principal — either JWT subject (username) or
     * PAT user id as string. Source of truth for the binding check
     * in {@link vip.mate.browser.edge.EdgeWebSocketHandler}.
     */
    private final String subject;

    /** Native Host agent version, from hello payload. */
    private final String agentVersion;

    /** Underlying WebSocket; do not leak outside the registry. */
    private final WebSocketSession ws;

    /** Last time we received any message (heartbeat or otherwise). */
    private volatile Instant lastHeartbeatAt;

    /**
     * 常驻 bridge 的 loopback 上当前是否真的挂着浏览器扩展。由 {@code EdgeWebSocketHandler.onHeartbeat}
     * 从 bridge heartbeat 的 {@code extension_attached} 字段更新。默认 true —— 非 bridge 会话(直连 WSS /
     * Claude Code)不上报该字段,保持原"有 session 即视为连上"的语义。常驻 bridge 会随扩展删/禁用把它置
     * false,使 UI 显示真实连接状态(bridge WSS 活着 ≠ 扩展在场)。
     */
    @Builder.Default
    private volatile boolean extensionAttached = true;

    /**
     * 扩展自报的版本号(常驻 bridge 经 heartbeat 的 {@code extension_version} 转报)。用于 UI 显示当前
     * 实际挂载的扩展版本、并对过旧版本标红("exe 与扩展版本错配"自诊断)。null=未知(非 bridge 会话 /
     * 旧扩展不上报)。
     */
    @Builder.Default
    private volatile String extensionVersion = null;

    /**
     * 用户在桌面"断开连接"后的【开关式禁用】态。true 时:UI 视为未连接(snapshot 的 extensionAttached
     * 报 false)、获客路由 {@code findLiveBySubject} 跳过此会话(不下发动作),但【不】关闭 WS、不 evict
     * —— 扩展保持连着,后端只是"闸住"它,故桌面"连接"可随时解禁恢复(可逆,不像旧的扩展闩会卡死)。
     */
    @Builder.Default
    private volatile boolean disabled = false;

    /**
     * Every subject-string key this session is reachable under in the registry's
     * alias map — at minimum {@link #subject}, plus (契约4) the PAT {@code userId}
     * string and the owning username when the registrar could resolve both. The
     * registry owns this set; it is used to remove ALL alias entries when the
     * session is dropped (reap / ws-close / conflict) so no alias dangles to a
     * dead session. Defaults to just {@link #subject} for the legacy 3-arg
     * {@code register} path. Never {@code null}.
     */
    @Builder.Default
    private final Set<String> aliasKeys = Set.of();
}
