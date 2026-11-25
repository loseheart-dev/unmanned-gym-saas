package com.hiking.gymsaas.account.model.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;

/** 员工管理请求；对应接口文档 §3.3–§3.8。密码字段只可写、toString 一律脱敏。 */
public final class StaffRequests {
    private StaffRequests() { }

    public record Create(
            @NotBlank @Pattern(regexp = "^[A-Za-z0-9_]{4,64}$") String username,
            @NotBlank @Size(min = 12, max = 128) @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
            String initialPassword,
            @NotBlank @Pattern(regexp = "^(ADMIN|OPERATOR)$") String role,
            @NotNull List<String> permissions,
            @NotBlank @Size(min = 1, max = 500) String reason) {
        @Override public String toString() { return "Create[credentials=REDACTED]"; }
    }

    public record UpdateRole(
            @NotBlank @Pattern(regexp = "^(ADMIN|OPERATOR)$") String role,
            @NotNull List<String> permissions,
            @NotBlank @Size(min = 1, max = 500) String reason) { }

    public record UpdateStatus(
            @NotBlank @Pattern(regexp = "^(ACTIVE|DISABLED)$") String status,
            @NotBlank @Size(min = 1, max = 500) String reason) { }

    public record ResetPassword(
            @NotBlank @Size(min = 12, max = 128) @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
            String newPassword,
            @NotBlank @Size(min = 1, max = 500) String reason) {
        @Override public String toString() { return "ResetPassword[credentials=REDACTED]"; }
    }
}
