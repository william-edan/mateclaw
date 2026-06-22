package vip.mate.auth.sms;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import vip.mate.exception.MateClawException;

import java.security.SecureRandom;
import java.time.Duration;

/**
 * 注册验证码编排：生成 6 位码、多维原子限流、发送、校验、一次性消费、mock 万能码旁路。
 *
 * @author MateClaw Team
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VerificationCodeService {

    /** 验证码固定 6 位（与前端写死一致，不可配）。 */
    private static final int CODE_BOUND = 1_000_000;

    private final SecureRandom random = new SecureRandom();

    private final VerificationCodeStore store;
    private final SmsCodeSender sender;
    private final SmsProperties props;

    /**
     * 发送注册验证码（先原子占位，再发送，失败回退）。
     *
     * @param phone 已 normalize 的手机号
     * @param ip    客户端 IP
     * @throws MateClawException 429（限流）/ 502（发送失败）
     */
    public void sendRegisterCode(String phone, String ip) {
        Duration resend = Duration.ofSeconds(props.getResendIntervalSeconds());
        if (props.getResendIntervalSeconds() > 0 && !store.tryAcquireSendLock(phone, resend)) {
            throw new MateClawException("err.sms.too_frequent", 429, "操作太频繁，请稍后再试");
        }

        String phoneKey = "daily:phone:" + phone;
        String ipKey = "daily:ip:" + ip;
        Duration day = Duration.ofHours(24);
        Duration minute = Duration.ofMinutes(1);
        boolean phoneInc = false;
        boolean ipInc = false;
        boolean globalInc = false;
        try {
            // 每个计数：先自增，再置回滚标志，最后判限。标志在自增成功之后置位，
            // 保证只回滚真正发生过的自增（即便 incrementWindow 抛异常也不会误退）。
            long phoneCount = store.incrementWindow(phoneKey, day);
            phoneInc = true;
            if (phoneCount > props.getDailyLimitPerPhone()) {
                throw new MateClawException("err.sms.daily_limit", 429, "今日验证码发送次数已达上限");
            }
            long ipCount = store.incrementWindow(ipKey, day);
            ipInc = true;
            if (ipCount > props.getDailyLimitPerIp()) {
                throw new MateClawException("err.sms.daily_limit", 429, "今日验证码发送次数已达上限");
            }
            long globalCount = store.incrementWindow("global", minute);
            globalInc = true;
            if (globalCount > props.getGlobalLimitPerMinute()) {
                throw new MateClawException("err.sms.busy", 429, "系统繁忙，请稍后再试");
            }

            String code = generateCode();
            store.putCode(phone, code, Duration.ofSeconds(props.getCodeTtlSeconds()));
            try {
                sender.send(phone, code);
            } catch (SmsSendException e) {
                store.removeCode(phone);
                throw new MateClawException("err.sms.send_failed", 502, "验证码发送失败，请稍后重试");
            }
        } catch (RuntimeException e) {
            if (phoneInc) {
                store.decrementWindow(phoneKey, day);
            }
            if (ipInc) {
                store.decrementWindow(ipKey, day);
            }
            if (globalInc) {
                store.decrementWindow("global", minute);
            }
            store.overwriteSendLock(phone, Duration.ofSeconds(props.getFailureCooldownSeconds()));
            throw e;
        }
    }

    /**
     * 校验并一次性消费验证码。mock=true 时额外接受万能码（仅 dev/集成）。
     *
     * @throws MateClawException 400（错误/过期/超次数）
     */
    public void verifyAndConsume(String phone, String code) {
        if (props.isMock() && props.getUniversalCode() != null && props.getUniversalCode().equals(code)) {
            return;
        }
        VerifyOutcome outcome = store.verifyAndConsume(phone, code, props.getMaxVerifyAttempts());
        switch (outcome) {
            case OK:
                return;
            case MISMATCH:
                throw new MateClawException("err.auth.invalid_verification_code", 400, "验证码错误");
            case TOO_MANY:
                throw new MateClawException("err.sms.too_many_attempts", 400, "验证码错误次数过多，请重新获取");
            case EXPIRED:
            default:
                throw new MateClawException("err.sms.code_expired", 400, "验证码已失效，请重新获取");
        }
    }

    /** 注册业务失败补偿：释放重发锁，允许用户立即重新获取验证码。 */
    public void releaseSendLock(String phone) {
        store.releaseSendLock(phone);
    }

    private String generateCode() {
        return String.format("%06d", random.nextInt(CODE_BOUND));
    }
}
