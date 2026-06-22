package vip.mate.auth.sms;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class SmsPropertiesTest {

    @Test
    void defaultsAreFailSafe() {
        SmsProperties p = new SmsProperties();
        assertFalse(p.isMock(), "mock 默认必须为 false（fail-safe）");
        assertEquals("888888", p.getUniversalCode());
        assertEquals(300, p.getCodeTtlSeconds());
        assertEquals(60, p.getResendIntervalSeconds());
        assertEquals(10, p.getFailureCooldownSeconds());
        assertEquals(5, p.getMaxVerifyAttempts());
        assertEquals(10, p.getDailyLimitPerPhone());
        assertEquals(30, p.getDailyLimitPerIp());
        assertEquals(200, p.getGlobalLimitPerMinute());
        assertEquals("dysmsapi.aliyuncs.com", p.getAliyun().getEndpoint());
        assertEquals("迪伍科技", p.getAliyun().getSignName());
        assertEquals("SMS_508735089", p.getAliyun().getTemplateCode());
        assertEquals("", p.getAliyun().getAccessKeyId());
        assertEquals("", p.getAliyun().getAccessKeySecret());
    }
}
