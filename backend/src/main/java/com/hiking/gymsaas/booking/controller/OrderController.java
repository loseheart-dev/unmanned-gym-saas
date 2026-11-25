package com.hiking.gymsaas.booking.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.hiking.gymsaas.booking.model.dto.OrderRequests;
import com.hiking.gymsaas.booking.model.vo.OrderViews;
import com.hiking.gymsaas.booking.service.OrderService;
import com.hiking.gymsaas.common.page.PageQuery;
import com.hiking.gymsaas.common.page.PageResult;
import com.hiking.gymsaas.common.web.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** 顾客订单（§6.1–§6.4）：身份来自 JWT，金额/时间/状态全由服务端推导。 */
@RestController
@Profile("!bootstrap")
@RequestMapping("/app/orders")
public class OrderController {
    private final OrderService orders;

    public OrderController(OrderService orders) { this.orders = orders; }

    @PostMapping
    public ResponseEntity<ApiResponse<OrderViews.Order>> create(@Valid @RequestBody OrderRequests.Create input,
            @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        var result = orders.create(input, key);
        return ResponseEntity.status(result.status()).body(ApiResponse.ok(result.data()));
    }

    @GetMapping
    public ApiResponse<PageResult<OrderViews.Order>> list(@Valid PageQuery page,
            @RequestParam(required = false) String status) {
        return ApiResponse.ok(orders.listMine(page, status));
    }

    @GetMapping("/{orderId}")
    public ApiResponse<OrderViews.Order> detail(@PathVariable String orderId) {
        return ApiResponse.ok(orders.getMine(orderId));
    }

    @PostMapping("/{orderId}/mock-payment")
    public ApiResponse<OrderViews.OrderConfirmation> mockPayment(@PathVariable String orderId,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @RequestBody(required = false) JsonNode body) {
        return ApiResponse.ok(orders.mockPayment(orderId, key).data());
    }
}
