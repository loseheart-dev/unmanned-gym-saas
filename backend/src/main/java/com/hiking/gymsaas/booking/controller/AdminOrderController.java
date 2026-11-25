package com.hiking.gymsaas.booking.controller;

import com.hiking.gymsaas.booking.model.vo.OrderViews;
import com.hiking.gymsaas.booking.service.OrderService;
import com.hiking.gymsaas.common.page.PageQuery;
import com.hiking.gymsaas.common.page.PageResult;
import com.hiking.gymsaas.common.web.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.*;

/** 后台订单查询（§6.6–§6.7）；商家隔离来自 JWT，权限 ORDER_READ 或 ADMIN。 */
@RestController
@Profile("!bootstrap")
@RequestMapping("/admin/orders")
public class AdminOrderController {
    private final OrderService orders;

    public AdminOrderController(OrderService orders) { this.orders = orders; }

    @GetMapping
    public ApiResponse<PageResult<OrderViews.Order>> list(@Valid PageQuery page,
            @RequestParam(required = false) String storeId,
            @RequestParam(required = false) String status) {
        return ApiResponse.ok(orders.adminList(page, storeId, status));
    }

    @GetMapping("/{orderId}")
    public ApiResponse<OrderViews.Order> detail(@PathVariable String orderId) {
        return ApiResponse.ok(orders.adminGet(orderId));
    }
}
