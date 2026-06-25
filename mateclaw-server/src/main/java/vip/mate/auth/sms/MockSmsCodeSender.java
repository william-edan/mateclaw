package vip.mate.auth.sms;

import lombok.extern.slf4j.Slf4j;

/**
 * Mock 发送器：mock=true 时装配，不真实发送，仅日志打印（仅限非生产）。
 *
 * @author MateClaw Team
 */
@Slf4j
public class MockSmsCodeSender implements SmsCodeSender {

    @Override
    public void send(String phone, String code) {
        log.warn("[SMS-MOCK] 不真实发送，phone={} code={}（仅限非生产）", phone, code);
    }
}
