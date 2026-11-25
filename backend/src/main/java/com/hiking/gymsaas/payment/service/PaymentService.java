package com.hiking.gymsaas.payment.service;

import com.hiking.gymsaas.payment.model.entity.PaymentRecord;

/** §6.4 模拟支付的现金流水写入；业务键保证同一订单不重复收费。 */
public interface PaymentService {
    /** 订单预约费收入；零元返回 null（不建现金流水）。 */
    PaymentRecord recordOrderFee(long merchantId, long userId, long orderId, long amountCent);

    /** 已成功的订单预约费流水，用于换键重复确认时返回原结果。 */
    PaymentRecord orderReceipt(long orderId);
}
