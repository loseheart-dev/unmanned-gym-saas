package com.hiking.gymsaas.account.model.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public final class AuthRequests {
    private AuthRequests() { }

    public record AdminLogin(@NotBlank @Size(max = 64) String username,
                             @NotBlank @Size(max = 128) @JsonProperty(access = JsonProperty.Access.WRITE_ONLY) String password) {
        @Override public String toString() { return "AdminLogin[credentials=REDACTED]"; }
    }

    public record WechatLogin(@NotBlank @Size(max = 256) @JsonProperty(access = JsonProperty.Access.WRITE_ONLY) String code) {
        @Override public String toString() { return "WechatLogin[credentials=REDACTED]"; }
    }

    public record Nickname(@NotBlank @Size(max = 64) String nickname) { }
    public record PhoneBinding(@NotBlank @Size(max = 512) @JsonProperty(access = JsonProperty.Access.WRITE_ONLY) String phoneCode) {
        @Override public String toString() { return "PhoneBinding[credentials=REDACTED]"; }
    }
    public record ChangePassword(
            @NotBlank @Size(max = 128) @JsonProperty(access = JsonProperty.Access.WRITE_ONLY) String oldPassword,
            @NotBlank @Size(min = 12, max = 128) @JsonProperty(access = JsonProperty.Access.WRITE_ONLY) String newPassword) {
        @Override public String toString() { return "ChangePassword[credentials=REDACTED]"; }
    }

    public enum Fixture { CUSTOMER_A, CUSTOMER_B }
    public record DevLogin(@NotNull Fixture fixtureKey) { }
    public record MerchantName(@NotBlank @Size(max = 100) String name, @NotBlank @Size(max = 500) String reason) { }
}
