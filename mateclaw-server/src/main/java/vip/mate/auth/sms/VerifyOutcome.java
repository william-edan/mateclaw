package vip.mate.auth.sms;

/** 验证码校验结果。 */
public enum VerifyOutcome {
    /** 命中并已一次性消费。 */
    OK,
    /** 码错误，错误次数 +1（未达上限）。 */
    MISMATCH,
    /** 码不存在或已过期。 */
    EXPIRED,
    /** 错误次数达到上限，已作废。 */
    TOO_MANY
}
