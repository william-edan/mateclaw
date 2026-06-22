package vip.mate.auth.sms;

import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SmsStartupValidatorTest {

    private Environment env(String... profiles) {
        Environment e = mock(Environment.class);
        when(e.getActiveProfiles()).thenReturn(profiles);
        return e;
    }

    @Test
    void prodWithMockTrueFailsFast() {
        SmsProperties p = new SmsProperties();
        p.setMock(true);
        SmsStartupValidator v = new SmsStartupValidator(p, env("prod"));
        assertThrows(IllegalStateException.class, v::afterPropertiesSet);
    }

    @Test
    void mockFalseWithEmptyKeysFailsFast() {
        SmsProperties p = new SmsProperties();
        SmsStartupValidator v = new SmsStartupValidator(p, env("prod"));
        assertThrows(IllegalStateException.class, v::afterPropertiesSet);
    }

    @Test
    void mockFalseWithKeysOk() {
        SmsProperties p = new SmsProperties();
        p.getAliyun().setAccessKeyId("ak");
        p.getAliyun().setAccessKeySecret("sk");
        SmsStartupValidator v = new SmsStartupValidator(p, env("prod"));
        assertDoesNotThrow(v::afterPropertiesSet);
    }

    @Test
    void mockTrueNonProdOk() {
        SmsProperties p = new SmsProperties();
        p.setMock(true);
        SmsStartupValidator v = new SmsStartupValidator(p, env("dev"));
        assertDoesNotThrow(v::afterPropertiesSet);
    }
}
