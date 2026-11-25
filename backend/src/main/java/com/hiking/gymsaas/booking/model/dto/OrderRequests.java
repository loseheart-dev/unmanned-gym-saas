package com.hiking.gymsaas.booking.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/** 6.1 创建预约；首个闭环只支持时段付费，身份/金额/时间/状态全部由服务端推导。 */
public final class OrderRequests {
    private OrderRequests() { }

    public record Create(
            @NotBlank @Pattern(regexp = "^[1-9][0-9]{0,18}$") String slotId,
            @NotBlank @Pattern(regexp = "^TIME_FEE$") String benefitType) { }
}
