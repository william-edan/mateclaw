package vip.mate.auth.sms;

/** 验证码发送抽象，屏蔽具体服务商。失败抛 {@link SmsSendException}。 */
public interface SmsCodeSender {
    void send(String phone, String code);
}
