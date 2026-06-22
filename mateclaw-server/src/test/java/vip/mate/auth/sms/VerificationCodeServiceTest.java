package vip.mate.auth.sms;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import vip.mate.exception.MateClawException;

import java.time.Clock;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class VerificationCodeServiceTest {

    private final AtomicLong now = new AtomicLong(1_000_000L);
    private InMemoryVerificationCodeStore store;
    private SmsCodeSender sender;
    private SmsProperties props;
    private VerificationCodeService service;

    @BeforeEach
    void setUp() {
        Clock clock = mock(Clock.class);
        when(clock.millis()).thenAnswer(inv -> now.get());
        store = new InMemoryVerificationCodeStore(clock);
        sender = mock(SmsCodeSender.class);
        props = new SmsProperties();
        service = new VerificationCodeService(store, sender, props);
    }

    @Test
    void sendGeneratesSixDigitCodeAndDelivers() {
        service.sendRegisterCode("13800138000", "1.1.1.1");
        ArgumentCaptor<String> codeCaptor = ArgumentCaptor.forClass(String.class);
        verify(sender).send(eq("13800138000"), codeCaptor.capture());
        String code = codeCaptor.getValue();
        assertTrue(code.matches("\\d{6}"), "验证码必须是 6 位数字，实际=" + code);
        service.verifyAndConsume("13800138000", code);
    }

    @Test
    void resendWithinIntervalRejected() {
        service.sendRegisterCode("p", "1.1.1.1");
        MateClawException ex = assertThrows(MateClawException.class,
                () -> service.sendRegisterCode("p", "1.1.1.1"));
        assertEquals(429, ex.getCode());
    }

    @Test
    void sendFailureRollsBackAndShortensCooldown() {
        doThrow(new SmsSendException("boom")).when(sender).send(anyString(), anyString());
        MateClawException ex = assertThrows(MateClawException.class,
                () -> service.sendRegisterCode("p", "1.1.1.1"));
        assertEquals(502, ex.getCode());
        now.addAndGet(11_000L);
        assertThrows(MateClawException.class, () -> service.sendRegisterCode("p", "1.1.1.1"));
        verify(sender, org.mockito.Mockito.times(2)).send(anyString(), anyString());
    }

    @Test
    void perPhoneDailyLimitEnforced() {
        props.setDailyLimitPerPhone(2);
        props.setResendIntervalSeconds(0);
        service.sendRegisterCode("p", "1.1.1.1");
        service.sendRegisterCode("p", "1.1.1.1");
        MateClawException ex = assertThrows(MateClawException.class,
                () -> service.sendRegisterCode("p", "1.1.1.1"));
        assertEquals(429, ex.getCode());
    }

    @Test
    void mockUniversalCodeAcceptedOnlyWhenMock() {
        props.setMock(true);
        service.verifyAndConsume("p", "888888");

        props.setMock(false);
        MateClawException ex = assertThrows(MateClawException.class,
                () -> service.verifyAndConsume("p", "888888"));
        assertEquals(400, ex.getCode());
    }

    @Test
    void verifyExpiredAndTooManyMapped() {
        store.putCode("p", "123456", java.time.Duration.ofSeconds(300));
        for (int i = 0; i < props.getMaxVerifyAttempts() - 1; i++) {
            assertThrows(MateClawException.class, () -> service.verifyAndConsume("p", "000000"));
        }
        MateClawException tooMany = assertThrows(MateClawException.class,
                () -> service.verifyAndConsume("p", "000000"));
        assertEquals(400, tooMany.getCode());
        verify(sender, never()).send(anyString(), anyString());
    }
}
