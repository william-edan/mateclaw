package vip.mate.auth.sms;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class InMemoryVerificationCodeStoreTest {

    private final AtomicLong now = new AtomicLong(1_000_000L);
    private InMemoryVerificationCodeStore store;

    @BeforeEach
    void setUp() {
        Clock clock = mock(Clock.class);
        when(clock.millis()).thenAnswer(inv -> now.get());
        store = new InMemoryVerificationCodeStore(clock);
    }

    private void advance(Duration d) {
        now.addAndGet(d.toMillis());
    }

    @Test
    void verifyAndConsumeHappyPathThenExpiredOnSecondTry() {
        store.putCode("p", "123456", Duration.ofSeconds(300));
        assertEquals(VerifyOutcome.OK, store.verifyAndConsume("p", "123456", 5));
        assertEquals(VerifyOutcome.EXPIRED, store.verifyAndConsume("p", "123456", 5));
    }

    @Test
    void codeExpiresAfterTtl() {
        store.putCode("p", "123456", Duration.ofSeconds(300));
        advance(Duration.ofSeconds(301));
        assertEquals(VerifyOutcome.EXPIRED, store.verifyAndConsume("p", "123456", 5));
    }

    @Test
    void mismatchIncrementsUntilTooMany() {
        store.putCode("p", "123456", Duration.ofSeconds(300));
        assertEquals(VerifyOutcome.MISMATCH, store.verifyAndConsume("p", "000000", 3));
        assertEquals(VerifyOutcome.MISMATCH, store.verifyAndConsume("p", "000000", 3));
        assertEquals(VerifyOutcome.TOO_MANY, store.verifyAndConsume("p", "000000", 3));
        assertEquals(VerifyOutcome.EXPIRED, store.verifyAndConsume("p", "123456", 3));
    }

    @Test
    void sendLockBlocksUntilTtlThenReleasable() {
        assertTrue(store.tryAcquireSendLock("p", Duration.ofSeconds(60)));
        assertFalse(store.tryAcquireSendLock("p", Duration.ofSeconds(60)));
        advance(Duration.ofSeconds(61));
        assertTrue(store.tryAcquireSendLock("p", Duration.ofSeconds(60)));
        store.releaseSendLock("p");
        assertTrue(store.tryAcquireSendLock("p", Duration.ofSeconds(60)));
    }

    @Test
    void overwriteSendLockShortensCooldown() {
        assertTrue(store.tryAcquireSendLock("p", Duration.ofSeconds(60)));
        store.overwriteSendLock("p", Duration.ofSeconds(10));
        advance(Duration.ofSeconds(11));
        assertTrue(store.tryAcquireSendLock("p", Duration.ofSeconds(60)));
    }

    @Test
    void windowIncrementsThenRollsOver() {
        assertEquals(1, store.incrementWindow("k", Duration.ofHours(24)));
        assertEquals(2, store.incrementWindow("k", Duration.ofHours(24)));
        advance(Duration.ofHours(24));
        assertEquals(1, store.incrementWindow("k", Duration.ofHours(24)));
    }

    @Test
    void decrementWindowRollsBack() {
        store.incrementWindow("k", Duration.ofMinutes(1));
        store.incrementWindow("k", Duration.ofMinutes(1));
        store.decrementWindow("k", Duration.ofMinutes(1));
        assertEquals(2, store.incrementWindow("k", Duration.ofMinutes(1)));
    }
}
