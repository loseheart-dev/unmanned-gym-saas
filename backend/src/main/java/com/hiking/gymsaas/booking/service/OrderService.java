package com.hiking.gymsaas.booking.service;

import com.hiking.gymsaas.booking.model.dto.OrderRequests;
import com.hiking.gymsaas.booking.model.vo.OrderViews;
import com.hiking.gymsaas.common.idempotency.IdempotencyService;
import com.hiking.gymsaas.common.page.PageQuery;
import com.hiking.gymsaas.common.page.PageResult;

/** §6 首闭环订单：创建待支付、模拟支付、本人查询与后台查询。 */
public interface OrderService {
    IdempotencyService.Outcome<OrderViews.Order> create(OrderRequests.Create input, String key);

    PageResult<OrderViews.Order> listMine(PageQuery query, String status);

    OrderViews.Order getMine(String orderId);

    IdempotencyService.Outcome<OrderViews.OrderConfirmation> mockPayment(String orderId, String key);

    PageResult<OrderViews.Order> adminList(PageQuery query, String storeId, String status);

    OrderViews.Order adminGet(String orderId);
}
