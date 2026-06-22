package vip.mate.auth.support;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 取客户端 IP。与 LoginRateLimitFilter 同一策略：X-Forwarded-For 首段 → X-Real-IP → remoteAddr。
 * 注意：信任 XFF 头，无可信代理收敛时可被伪造（见 spec §13 残余风险）。
 *
 * @author MateClaw Team
 */
public final class ClientIp {

    private ClientIp() {
    }

    public static String from(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isEmpty()) {
            return xff.split(",")[0].trim();
        }
        String realIp = request.getHeader("X-Real-IP");
        if (realIp != null && !realIp.isEmpty()) {
            return realIp;
        }
        return request.getRemoteAddr();
    }
}
