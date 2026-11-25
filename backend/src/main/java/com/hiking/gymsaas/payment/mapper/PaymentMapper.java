package com.hiking.gymsaas.payment.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hiking.gymsaas.payment.model.entity.PaymentRecord;
import java.time.LocalDateTime;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** 顾客侧现金流水：显式忽略租户改写，business_key 唯一防重复收费。 */
@Mapper
@InterceptorIgnore(tenantLine = "true")
public interface PaymentMapper extends BaseMapper<PaymentRecord> {

    @Select("SELECT * FROM payment_record WHERE merchant_id = #{merchantId} AND business_key = #{businessKey}")
    PaymentRecord byBusinessKey(@Param("merchantId") long merchantId, @Param("businessKey") String businessKey);

    @Select("SELECT * FROM payment_record WHERE order_id = #{orderId} AND kind = 'RECEIPT' "
            + "AND status = 'SUCCEEDED' ORDER BY id LIMIT 1")
    PaymentRecord orderReceipt(long orderId);

    @Insert("INSERT INTO payment_record(merchant_id, user_id, payment_no, order_id, business_key, kind, channel, "
            + "amount_cent, status, completed_at) VALUES(#{merchantId}, #{userId}, #{paymentNo}, #{orderId}, "
            + "#{businessKey}, 'RECEIPT', 'MOCK', #{amountCent}, 'SUCCEEDED', #{completedAt})")
    int insertReceipt(@Param("merchantId") long merchantId, @Param("userId") long userId,
                      @Param("paymentNo") String paymentNo, @Param("orderId") long orderId,
                      @Param("businessKey") String businessKey, @Param("amountCent") long amountCent,
                      @Param("completedAt") LocalDateTime completedAt);
}
