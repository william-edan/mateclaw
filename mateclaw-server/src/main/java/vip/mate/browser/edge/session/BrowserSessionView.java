package vip.mate.browser.edge.session;

import java.time.Instant;

/** Read-only projection of a session — no WebSocket reference. Used by D3. */
public record BrowserSessionView(
        String sessionId,
        String subject,
        String agentVersion,
        Instant lastHeartbeatAt,
        /**
         * 常驻 bridge 的 loopback 上当前是否真的挂着浏览器扩展(由 bridge 在每次 heartbeat 上报)。
         * bridge 的 WSS session 活着 ≠ 扩展在场:删/禁用扩展后 bridge 仍持 session,此值转 false,
         * 前端据此显示"未连接"。非 bridge 会话(直连/Claude Code)不上报 → 后端默认 true,行为不变。
         */
        boolean extensionAttached,
        /** 扩展自报的版本号(可能为 null=未知);UI 据此显示当前挂载的扩展版本并对过旧版本标红。 */
        String extensionVersion
) {}
