package com.hiking.gymsaas.account.service;

import com.hiking.gymsaas.common.exception.BusinessException;
import com.hiking.gymsaas.common.exception.ErrorCode;
import java.nio.charset.StandardCharsets;

/**
 * 密码规则（与登录、修改密码共用同一口径）：
 * <ul>
 *   <li>字符数 12–128（按 Unicode code point 计，中文也算 1 个字符）；</li>
 *   <li>UTF-8 字节数 ≤ 72：BCrypt 只处理前 72 字节，超长必须拒绝而不是静默截断；</li>
 *   <li>不得与登录名相同（忽略大小写）。</li>
 * </ul>
 * 员工创建、管理员重置员工密码、本人修改密码都走这里，避免三处口径漂移。
 */
public final class PasswordRules {
    public static final int MIN_CHARS = 12;
    public static final int MAX_CHARS = 128;
    /** BCrypt 的 72 字节上限。 */
    public static final int MAX_UTF8_BYTES = 72;

    private PasswordRules() { }

    public static void validate(String password, String username) {
        if (password == null) throw new BusinessException(ErrorCode.VALIDATION_ERROR);
        int characters = password.codePointCount(0, password.length());
        if (characters < MIN_CHARS || characters > MAX_CHARS
                || password.getBytes(StandardCharsets.UTF_8).length > MAX_UTF8_BYTES) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR);
        }
        if (username != null && password.equalsIgnoreCase(username)) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR);
        }
    }
}
