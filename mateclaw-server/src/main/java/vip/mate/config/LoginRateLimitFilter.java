package vip.mate.config;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import vip.mate.auth.support.ClientIp;

import java.io.IOException;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Rate limiter for anonymous auth endpoints — prevents brute force attacks.
 * Allows max 5 attempts per IP per minute.
 *
 * @author MateClaw Team
 */
@Slf4j
@Component
public class LoginRateLimitFilter implements Filter {

    private static final int MAX_ATTEMPTS = 5;
    private static final Set<String> AUTH_PATHS = Set.of(
            "/api/v1/auth/login",
            "/api/v1/auth/register",
            "/api/v1/auth/send-register-code"
    );

    /** IP → attempt count, auto-expires after 1 minute */
    private final Cache<String, AtomicInteger> attempts = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofMinutes(1))
            .maximumSize(10_000)
            .build();

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest httpReq = (HttpServletRequest) request;

        if ("POST".equalsIgnoreCase(httpReq.getMethod()) && AUTH_PATHS.contains(httpReq.getRequestURI())) {
            String ip = getClientIp(httpReq);
            AtomicInteger count = attempts.get(ip, k -> new AtomicInteger(0));
            int current = count.incrementAndGet();

            if (current > MAX_ATTEMPTS) {
                log.warn("[RateLimit] Auth rate limit exceeded for IP: {} path={} attempts={}",
                        ip, httpReq.getRequestURI(), current);
                HttpServletResponse httpResp = (HttpServletResponse) response;
                httpResp.setStatus(429);
                httpResp.setContentType("application/json;charset=UTF-8");
                httpResp.getWriter().write("{\"code\":429,\"msg\":\"Too many authentication attempts, please try again later\",\"data\":null}");
                return;
            }
        }

        chain.doFilter(request, response);
    }

    private static String getClientIp(HttpServletRequest request) {
        return ClientIp.from(request);
    }
}
