package com.hiking.gymsaas.booking.model.vo;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.OffsetDateTime;

/** §6 订单视图；字段与 first-loop.openapi.yaml 的 Order/Payment/OrderConfirmation 对齐。 */
public final class OrderViews {
    private OrderViews() { }

    public record Order(String id, String orderNo, String merchantId, String storeId, String storeName,
                        String slotId, OffsetDateTime startAt, OffsetDateTime endAt, String benefitType,
                        long baseAmountCent, OffsetDateTime payDeadline, String status, boolean capacityHeld,
                        OffsetDateTime confirmedAt, String cancelStage, OffsetDateTime cancelRequestedAt,
                        OffsetDateTime cancelledAt, String cancelReason, boolean reviewRequired,
                        JsonNode ruleSnapshot, String paymentMode) { }

    public record Payment(String id, String paymentNo, String kind, String channel, long amountCent,
                          String status, OffsetDateTime completedAt) { }

    public record OrderConfirmation(Order order, Payment payment, boolean zeroAmount) { }
}
