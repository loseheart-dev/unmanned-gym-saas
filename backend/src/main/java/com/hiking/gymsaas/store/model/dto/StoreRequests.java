package com.hiking.gymsaas.store.model.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalTime;
import java.util.List;

/** 门店请求；§4.6 创建（§4.7 展示资料修改用 JsonNode，以区分“缺省”与“显式 null”）。 */
public final class StoreRequests {
    private StoreRequests() { }

    public record Create(
            @NotBlank @Size(min = 1, max = 100) String name,
            @NotBlank @Size(min = 1, max = 255) String address,
            @Size(max = 2000) String description,
            List<String> images,
            String latitude,
            String longitude,
            @NotNull LocalTime openTime,
            @NotNull LocalTime closeTime,
            @NotNull Boolean is24h,
            @NotNull Boolean endsNextDay,
            @NotNull @Min(1) @Max(100000) Integer capacity,
            @NotNull @Min(0) Long slotPriceCent,
            @NotNull @Min(0) Long overtimePriceCent,
            @NotNull @Min(0) Long overtimeCapCent,
            @NotBlank @Size(min = 1, max = 500) String reason) { }
}
