package vip.mate.auth.sms;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 进程内内存实现：ConcurrentHashMap + 注入 Clock，compute 保证单 key 原子。
 * 多实例非粘性部署下验证码/限流不共享——见 spec §13，注册需单实例或会话粘性。
 *
 * @author MateClaw Team
 */
@Component
public class InMemoryVerificationCodeStore implements VerificationCodeStore {

    private static final long WINDOW_SWEEP_MAX_AGE_MS = Duration.ofHours(24).toMillis();

    private static final class CodeEntry {
        final String code;
        int attempts;
        final long expiresAtMs;

        CodeEntry(String code, long expiresAtMs) {
            this.code = code;
            this.attempts = 0;
            this.expiresAtMs = expiresAtMs;
        }
    }

    private static final class Window {
        final long startMs;
        int count;

        Window(long startMs) {
            this.startMs = startMs;
            this.count = 1;
        }
    }

    private final Clock clock;
    private final ConcurrentHashMap<String, CodeEntry> codes = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> locks = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();

    /** 生产构造：系统 UTC 时钟。 */
    public InMemoryVerificationCodeStore() {
        this(Clock.systemUTC());
    }

    /** 测试构造：注入可控时钟。 */
    InMemoryVerificationCodeStore(Clock clock) {
        this.clock = clock;
    }

    @Override
    public boolean tryAcquireSendLock(String phone, Duration ttl) {
        long now = clock.millis();
        long until = now + ttl.toMillis();
        boolean[] acquired = {false};
        locks.compute(phone, (k, existing) -> {
            if (existing != null && existing > now) {
                acquired[0] = false;
                return existing;
            }
            acquired[0] = true;
            return until;
        });
        return acquired[0];
    }

    @Override
    public void overwriteSendLock(String phone, Duration ttl) {
        locks.put(phone, clock.millis() + ttl.toMillis());
    }

    @Override
    public void releaseSendLock(String phone) {
        locks.remove(phone);
    }

    @Override
    public long incrementWindow(String key, Duration window) {
        long now = clock.millis();
        long windowMs = window.toMillis();
        long[] result = {0};
        windows.compute(key, (k, w) -> {
            if (w == null || now - w.startMs >= windowMs) {
                Window nw = new Window(now);
                result[0] = nw.count;
                return nw;
            }
            w.count++;
            result[0] = w.count;
            return w;
        });
        return result[0];
    }

    @Override
    public void decrementWindow(String key, Duration window) {
        long now = clock.millis();
        long windowMs = window.toMillis();
        windows.computeIfPresent(key, (k, w) -> {
            if (now - w.startMs >= windowMs) {
                return w;
            }
            w.count = Math.max(0, w.count - 1);
            return w;
        });
    }

    @Override
    public void putCode(String phone, String code, Duration ttl) {
        codes.put(phone, new CodeEntry(code, clock.millis() + ttl.toMillis()));
    }

    @Override
    public void removeCode(String phone) {
        codes.remove(phone);
    }

    @Override
    public VerifyOutcome verifyAndConsume(String phone, String code, int maxAttempts) {
        long now = clock.millis();
        VerifyOutcome[] outcome = {VerifyOutcome.EXPIRED};
        codes.compute(phone, (k, e) -> {
            if (e == null || e.expiresAtMs <= now) {
                outcome[0] = VerifyOutcome.EXPIRED;
                return null;
            }
            if (e.code.equals(code)) {
                outcome[0] = VerifyOutcome.OK;
                return null;
            }
            e.attempts++;
            if (e.attempts >= maxAttempts) {
                outcome[0] = VerifyOutcome.TOO_MANY;
                return null;
            }
            outcome[0] = VerifyOutcome.MISMATCH;
            return e;
        });
        return outcome[0];
    }

    /** 周期清扫过期条目，界定内存占用。 */
    @Scheduled(fixedDelay = 300_000L)
    void sweepExpired() {
        long now = clock.millis();
        codes.entrySet().removeIf(en -> en.getValue().expiresAtMs <= now);
        locks.entrySet().removeIf(en -> en.getValue() <= now);
        windows.entrySet().removeIf(en -> now - en.getValue().startMs >= WINDOW_SWEEP_MAX_AGE_MS);
    }
}
