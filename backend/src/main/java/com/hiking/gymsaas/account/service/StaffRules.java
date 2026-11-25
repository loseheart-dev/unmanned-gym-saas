package com.hiking.gymsaas.account.service;

import com.hiking.gymsaas.account.model.dto.StaffRequests;
import com.hiking.gymsaas.common.exception.BusinessException;
import com.hiking.gymsaas.common.exception.ErrorCode;
import com.hiking.gymsaas.common.util.Hashes;
import com.hiking.gymsaas.common.web.ConfigurationEtags;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

/**
 * 员工规则（可单测的纯函数）：
 * <ul>
 *   <li>权限必须是文档 §1.3 的权限码白名单；ADMIN 的 permissions 必须为空数组；</li>
 *   <li>员工配置 ETag 只由可写配置字段（role / permissions / status）生成；</li>
 *   <li>角色与状态枚举固定 ADMIN|OPERATOR、ACTIVE|DISABLED。</li>
 * </ul>
 */
public final class StaffRules {
    public static final String ROLE_ADMIN = "ADMIN";
    public static final String ROLE_OPERATOR = "OPERATOR";
    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_DISABLED = "DISABLED";

    public static final Set<String> PERMISSIONS = Set.copyOf(List.of(
            "STORE_READ", "STORE_CONTROL", "CUSTOMER_READ", "ORDER_READ", "VISIT_READ", "CARD_READ",
            "FINANCE_READ", "DEVICE_READ", "DEVICE_CONTROL", "VIDEO_READ", "LIGHT_CONTROL",
            "REVIEW_READ", "REVIEW_HANDLE", "REPORT_READ", "REPORT_REFRESH", "AUDIT_READ"));

    private StaffRules() { }

    public static void validatePermissions(String role, List<String> permissions) {
        if (!ROLE_ADMIN.equals(role) && !ROLE_OPERATOR.equals(role)) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR);
        }
        if (permissions == null) throw new BusinessException(ErrorCode.VALIDATION_ERROR);
        if (ROLE_ADMIN.equals(role)) {
            // ADMIN 覆盖全部权限码，不接受额外授权
            if (!permissions.isEmpty()) throw new BusinessException(ErrorCode.VALIDATION_ERROR);
            return;
        }
        if (new HashSet<>(permissions).size() != permissions.size()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR);
        }
        for (String permission : permissions) {
            if (permission == null || !PERMISSIONS.contains(permission)) {
                throw new BusinessException(ErrorCode.VALIDATION_ERROR);
            }
        }
    }

    /** 配置摘要：排序后的权限集合，避免顺序影响 ETag。 */
    public static String etag(String role, List<String> permissions, String status) {
        String canonical = role.toLowerCase(Locale.ROOT) + "|"
                + String.join(",", new TreeSet<>(permissions == null ? List.of() : permissions)) + "|"
                + status.toLowerCase(Locale.ROOT);
        return ConfigurationEtags.of(canonical);
    }

    public static boolean isActiveAdmin(String role, String status) {
        return ROLE_ADMIN.equals(role) && STATUS_ACTIVE.equals(status);
    }

    // ---------- 幂等指纹：覆盖完整业务语义（含凭据变更与 If-Match），且不含任何明文凭据 ----------

    /** 凭据摘要只用于幂等指纹，不进入响应快照或审计详情。 */
    public static String passwordDigest(String password) {
        return Hashes.sha256(password);
    }

    public static Map<String, Object> createFingerprint(StaffRequests.Create input) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("username", input.username());
        body.put("role", input.role());
        body.put("permissions", new TreeSet<>(input.permissions()));
        body.put("passwordDigest", passwordDigest(input.initialPassword()));
        body.put("reason", input.reason());
        return body;
    }

    public static Map<String, Object> resetFingerprint(StaffRequests.ResetPassword input) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("passwordDigest", passwordDigest(input.newPassword()));
        body.put("reason", input.reason());
        return body;
    }

    public static Map<String, Object> roleFingerprint(StaffRequests.UpdateRole input, String ifMatch) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("role", input.role());
        body.put("permissions", new TreeSet<>(input.permissions()));
        body.put("reason", input.reason());
        body.put("ifMatch", ifMatch == null ? "" : ifMatch);
        return body;
    }

    public static Map<String, Object> statusFingerprint(StaffRequests.UpdateStatus input, String ifMatch) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", input.status());
        body.put("reason", input.reason());
        body.put("ifMatch", ifMatch == null ? "" : ifMatch);
        return body;
    }
}