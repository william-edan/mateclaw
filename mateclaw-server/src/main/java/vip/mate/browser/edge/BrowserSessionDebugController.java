package vip.mate.browser.edge;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import vip.mate.browser.edge.session.BrowserSessionRegistry;
import vip.mate.browser.edge.session.BrowserSessionView;
import vip.mate.common.result.R;

import java.util.List;
import java.util.Map;

/**
 * Browser edge session endpoint. Admin-only — protected by {@code SecurityConfig}.
 * 桌面客户端读 {@link #list()} 判断浏览器扩展是否连接(它在 Electron 内 ping 不到 Chrome 扩展,故以
 * 服务端 live session 为准,并据 {@code extensionAttached} 区分"bridge 活着但扩展不在场/已被开关式断开"
 * 的假阳性)。{@link #disconnect()} / {@link #connect()} 是【开关式断开/连接】:不动扩展(够不到),只在
 * 后端把会话 {@code disabled} 置真/假 —— 桌面可逆、不卡死。
 */
@RestController
@RequestMapping("/api/v1/browser/sessions")
@RequiredArgsConstructor
public class BrowserSessionDebugController {

    private final BrowserSessionRegistry registry;

    @GetMapping
    public R<List<BrowserSessionView>> list() {
        return R.ok(registry.snapshot());
    }

    /**
     * 开关式"断开连接":把所有 live 会话置为 disabled —— UI 转"未连接"、获客不再路由动作,但扩展保持
     * 连着(不关 WS、不 evict)。桌面 SPA 够不到 Chrome 扩展,故由后端开关实现;随时可 {@link #connect()} 恢复。
     */
    @PostMapping("/disconnect")
    public R<Map<String, Object>> disconnect() {
        int n = registry.setDisabledAllLive(true);
        return R.ok(Map.of("disconnected", n));
    }

    /** 开关式"连接":解禁所有 live 会话(disabled=false)→ 恢复连接与动作路由。与 {@link #disconnect()} 互逆。 */
    @PostMapping("/connect")
    public R<Map<String, Object>> connect() {
        int n = registry.setDisabledAllLive(false);
        return R.ok(Map.of("connected", n));
    }
}
