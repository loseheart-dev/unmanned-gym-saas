package com.hiking.gymsaas.store.model.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalTime;
import java.util.List;

/** §4.8–§4.12、§4.15 请求体。 */
public final class StoreRuleRequests {
    private StoreRuleRequests() { }

    /** 4.9 规则预览：展示字段可选，营业与计费核心字段必填。 */
    public record RulePreview(
            String name,
            String address,
            String latitude,
            String longitude,
            String description,
            List<String> images,
            @NotNull LocalTime openTime,
            @NotNull LocalTime closeTime,
            @NotNull Boolean is24h,
            @NotNull Boolean endsNextDay,
            @NotNull @Min(1) Integer capacity,
            @NotNull @Min(0) Long slotPriceCent,
            @NotNull @Min(0) Long overtimePriceCent,
            @NotNull @Min(0) Long overtimeCapCent,
            @NotBlank @Size(min = 1, max = 500) String reason) { }

    /** 4.10 应用规则：核心规则 + 预览摘要 + reason（不含 name/address/images）。 */
    public record ApplyRules(
            @NotNull LocalTime openTime,
            @NotNull LocalTime closeTime,
            @NotNull Boolean is24h,
            @NotNull Boolean endsNextDay,
            @NotNull @Min(1) Integer capacity,
            @NotNull @Min(0) Long slotPriceCent,
            @NotNull @Min(0) Long overtimePriceCent,
            @NotNull @Min(0) Long overtimeCapCent,
            @NotBlank String previewHash,
            @NotBlank @Size(min = 1, max = 500) String reason) { }

    /** 4.11 营业状态。 */
    public record UpdateStatus(
            @NotBlank @Pattern(regexp = "^(OPEN|CLOSED|DISABLED)$") String status,
            @NotBlank @Size(min = 1, max = 500) String reason) { }

    /** 4.12 准入暂停/恢复。 */
    public record UpdateAdmission(
            @NotNull Boolean paused,
            @NotBlank @Size(min = 1, max = 500) String reason) { }

    /** 4.14 补齐时段。 */
    public record GenerateSlots(@NotBlank @Size(min = 1, max = 500) String reason) { }

    /** 4.15 调整未售时段。 */
    public record AdjustSlot(
            @NotNull @Min(1) Integer capacity,
            @NotNull @Min(0) Long priceCent,
            @NotBlank @Pattern(regexp = "^(OPEN|CLOSED)$") String status,
            @NotBlank @Size(min = 1, max = 500) String reason) { }
}
