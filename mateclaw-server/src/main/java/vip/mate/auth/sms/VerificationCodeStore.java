package vip.mate.auth.sms;

import java.time.Duration;

/**
 * 验证码与限流计数的存储抽象。所有方法对单 key 原子。
 * 当前唯一实现为进程内内存（不引入 Redis，保桌面/单机形态）。
 *
 * @author MateClaw Team
 */
public interface VerificationCodeStore {

    /** 锁不存在/已过期则占用并返回 true；存在未过期返回 false（不刷新）。 */
    boolean tryAcquireSendLock(String phone, Duration ttl);

    /**
     * 无条件覆盖重发锁为新的 ttl（用于发送失败缩短冷却）。
     * 单次写入原子；仅用于单用户对自己手机号的补偿路径，不保证与并发 tryAcquire 的逻辑顺序。
     */
    void overwriteSendLock(String phone, Duration ttl);

    /** 释放重发锁（注册业务失败补偿，允许立即重试）。 */
    void releaseSendLock(String phone);

    /** 滚动窗口计数自增，返回自增后的值；窗口首个事件锚定起点。 */
    long incrementWindow(String key, Duration window);

    /**
     * 回退一次计数（占额度后失败时使用）。
     * 注意：若窗口已过期则为 no-op——此时计数本就会在下次 {@link #incrementWindow} 重置，无需回退。
     */
    void decrementWindow(String key, Duration window);

    /** 写入验证码，覆盖旧码并清零错误次数。 */
    void putCode(String phone, String code, Duration ttl);

    /** 删除验证码。 */
    void removeCode(String phone);

    /** 原子校验并在命中/超次数时删除。 */
    VerifyOutcome verifyAndConsume(String phone, String code, int maxAttempts);
}
