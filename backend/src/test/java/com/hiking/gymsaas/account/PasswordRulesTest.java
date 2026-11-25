package com.hiking.gymsaas.account;

import static org.junit.jupiter.api.Assertions.*;

import com.hiking.gymsaas.account.service.PasswordRules;
import com.hiking.gymsaas.common.exception.BusinessException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/** 密码口径边界测试：字符数、UTF-8 字节上限（BCrypt 72）、不得等于登录名。 */
class PasswordRulesTest {

    @Test
    void acceptsMinimumAndMaximumCharacterLengths() {
        String twelve = "Abcdefgh1234";
        assertEquals(12, twelve.codePointCount(0, twelve.length()));
        assertDoesNotThrow(() -> PasswordRules.validate(twelve, "admin"));
        // 128 个字符（128 字节）超过 BCrypt 72 字节上限，必须拒绝；72 字节是实际可实现上限
        String oneHundredTwentyEight = "a".repeat(128);
        assertEquals(128, oneHundredTwentyEight.getBytes(StandardCharsets.UTF_8).length);
        assertThrows(BusinessException.class, () -> PasswordRules.validate(oneHundredTwentyEight, "admin"));
        String seventyTwo = "a".repeat(72);
        assertDoesNotThrow(() -> PasswordRules.validate(seventyTwo, "admin"));
    }

    @Test
    void rejectsTooShortAndTooLong() {
        assertThrows(BusinessException.class, () -> PasswordRules.validate("a".repeat(11), "admin"));
        assertThrows(BusinessException.class, () -> PasswordRules.validate("a".repeat(129), "admin"));
        assertThrows(BusinessException.class, () -> PasswordRules.validate(null, "admin"));
    }

    @Test
    void rejectsMoreThanSeventyTwoUtf8BytesEvenWhenCharacterCountIsLegal() {
        // 73 个 ASCII 字符：字符数合法，但 BCrypt 只处理前 72 字节，必须拒绝
        String seventyThree = "a".repeat(73);
        assertEquals(73, seventyThree.getBytes(StandardCharsets.UTF_8).length);
        assertThrows(BusinessException.class, () -> PasswordRules.validate(seventyThree, "admin"));
        // 25 个中文（75 字节）：25 个字符在 12–128 内，但字节超限
        String chinese = "密码".repeat(12) + "密";
        assertEquals(25, chinese.codePointCount(0, chinese.length()));
        assertEquals(75, chinese.getBytes(StandardCharsets.UTF_8).length);
        assertThrows(BusinessException.class, () -> PasswordRules.validate(chinese, "admin"));
        // 24 个中文（72 字节）刚好可接受
        String atLimit = "密码".repeat(12);
        assertEquals(72, atLimit.getBytes(StandardCharsets.UTF_8).length);
        assertDoesNotThrow(() -> PasswordRules.validate(atLimit, "admin"));
    }

    @Test
    void rejectsPasswordEqualToUsernameIgnoringCase() {
        assertThrows(BusinessException.class, () -> PasswordRules.validate("abcdef12345678", "abcdef12345678"));
        assertThrows(BusinessException.class, () -> PasswordRules.validate("ABCDEF12345678", "abcdef12345678"));
    }
}
