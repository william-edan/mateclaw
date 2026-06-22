package vip.mate.auth.support;

import java.util.regex.Pattern;

/**
 * 手机号归一化与校验。注册与发送验证码端点共用同一口径（先 normalize 再 match）。
 * 逻辑从 AuthService 抽取，保持与原 PHONE_PATTERN / normalizePhone 完全一致。
 *
 * @author MateClaw Team
 */
public final class PhoneNumbers {

    private static final Pattern PHONE_PATTERN = Pattern.compile("^\\+?\\d{6,20}$");

    private PhoneNumbers() {
    }

    /** 去掉空格与连字符；null → 空串。 */
    public static String normalize(String phone) {
        if (phone == null) {
            return "";
        }
        return phone.trim().replaceAll("[\\s-]", "");
    }

    /** 对已 normalize 的号码做格式校验。 */
    public static boolean isValid(String normalizedPhone) {
        return PHONE_PATTERN.matcher(normalizedPhone).matches();
    }
}
