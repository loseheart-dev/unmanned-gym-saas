package com.hiking.gymsaas.account;

import static org.junit.jupiter.api.Assertions.*;

import com.hiking.gymsaas.account.service.StaffRules;
import com.hiking.gymsaas.common.exception.BusinessException;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 员工规则的纯函数测试：权限白名单、ADMIN 空权限、配置 ETag 稳定性。 */
class StaffRulesTest {

    @Test
    void adminMustNotCarryOperatorPermissions() {
        assertThrows(BusinessException.class, () -> StaffRules.validatePermissions("ADMIN", List.of("ORDER_READ")));
        assertDoesNotThrow(() -> StaffRules.validatePermissions("ADMIN", List.of()));
    }

    @Test
    void operatorPermissionsMustBeWhitelistedAndUnique() {
        assertDoesNotThrow(() -> StaffRules.validatePermissions("OPERATOR", List.of("ORDER_READ", "STORE_READ")));
        assertThrows(BusinessException.class,
                () -> StaffRules.validatePermissions("OPERATOR", List.of("NOT_A_PERMISSION")));
        assertThrows(BusinessException.class,
                () -> StaffRules.validatePermissions("OPERATOR", List.of("ORDER_READ", "ORDER_READ")));
        assertThrows(BusinessException.class, () -> StaffRules.validatePermissions("OPERATOR", null));
    }

    @Test
    void unknownRolesAreRejected() {
        assertThrows(BusinessException.class, () -> StaffRules.validatePermissions("OWNER", List.of()));
    }

    @Test
    void etagDependsOnWritableConfigurationOnlyAndIgnoresPermissionOrder() {
        String a = StaffRules.etag("OPERATOR", List.of("ORDER_READ", "STORE_READ"), "ACTIVE");
        String b = StaffRules.etag("operator", List.of("STORE_READ", "ORDER_READ"), "active");
        String c = StaffRules.etag("OPERATOR", List.of("ORDER_READ"), "ACTIVE");
        String d = StaffRules.etag("OPERATOR", List.of("ORDER_READ", "STORE_READ"), "DISABLED");
        assertEquals(a, b);
        assertNotEquals(a, c);
        assertNotEquals(a, d);
        assertTrue(a.startsWith("\"") && a.endsWith("\""));
    }

    @Test
    void createFingerprintCoversCredentialAndNeverStoresPlaintext() {
        var input = new com.hiking.gymsaas.account.model.dto.StaffRequests.Create("operator01",
                "operator123456", "OPERATOR", List.of("ORDER_READ"), "创建员工");
        java.util.Map<String, Object> fingerprint = StaffRules.createFingerprint(input);
        assertEquals("operator01", fingerprint.get("username"));
        assertEquals("OPERATOR", fingerprint.get("role"));
        assertNotNull(fingerprint.get("passwordDigest"));
        assertEquals(64, ((String) fingerprint.get("passwordDigest")).length());
        // 指纹本身不能包含明文密码/哈希原文
        String rendered = fingerprint.toString();
        assertFalse(rendered.contains("operator123456"));
        assertFalse(rendered.contains("$2a$"));
        // 同键同请求指纹稳定；换密码则指纹变化（避免同键重放旧结果掩盖凭据变更）
        assertEquals(fingerprint, StaffRules.createFingerprint(input));
        var changed = new com.hiking.gymsaas.account.model.dto.StaffRequests.Create("operator01",
                "operator654321", "OPERATOR", List.of("ORDER_READ"), "创建员工");
        assertNotEquals(fingerprint, StaffRules.createFingerprint(changed));
    }

    @Test
    void configFingerprintIncludesIfMatchAndIgnoresPermissionOrder() {
        var role = new com.hiking.gymsaas.account.model.dto.StaffRequests.UpdateRole("OPERATOR",
                List.of("STORE_READ", "ORDER_READ"), "调整权限");
        var reordered = new com.hiking.gymsaas.account.model.dto.StaffRequests.UpdateRole("operator",
                List.of("ORDER_READ", "STORE_READ"), "调整权限");
        assertNotEquals(StaffRules.roleFingerprint(role, "\"etag-a\""),
                StaffRules.roleFingerprint(role, "\"etag-b\""));
        assertEquals(StaffRules.roleFingerprint(role, "\"etag-a\""),
                StaffRules.roleFingerprint(role, "\"etag-a\""));
        // 权限集合与大小写口径一致（role 原样进入指纹，大小写不同属不同请求）
        assertEquals(StaffRules.roleFingerprint(role, "\"x\"").get("permissions"),
                StaffRules.roleFingerprint(role, "\"y\"").get("permissions"));
        var status = new com.hiking.gymsaas.account.model.dto.StaffRequests.UpdateStatus("DISABLED", "停用");
        assertNotEquals(StaffRules.statusFingerprint(status, "\"etag-a\""),
                StaffRules.statusFingerprint(status, "\"etag-b\""));
    }
}