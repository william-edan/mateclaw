package vip.mate.auth.support;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PhoneNumbersTest {

    @Test
    void normalizeStripsSpacesAndHyphens() {
        assertEquals("13800138000", PhoneNumbers.normalize(" 138 0013-8000 "));
        assertEquals("", PhoneNumbers.normalize(null));
    }

    @Test
    void isValidMatchesExistingPattern() {
        assertTrue(PhoneNumbers.isValid("13800138000"));
        assertTrue(PhoneNumbers.isValid("+8613800138000"));
        assertFalse(PhoneNumbers.isValid("555abc1212"));
        assertFalse(PhoneNumbers.isValid(""));
    }
}
