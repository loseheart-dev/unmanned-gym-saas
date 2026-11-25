package com.hiking.gymsaas.booking.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import com.hiking.gymsaas.booking.model.entity.BookingOrder;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.ResultMap;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** 顾客侧订单写/读：显式忽略租户改写，由 Service 校验 user_id 归属。 */
@Mapper
@InterceptorIgnore(tenantLine = "true")
public interface BookingOrderMapper extends BaseMapper<BookingOrder> {

    // 自定义查询不套用实体 autoResultMap：rule_snapshot JSON 列必须显式声明 TypeHandler。
    @Select("SELECT * FROM booking_order WHERE id = #{id} FOR UPDATE")
    @Results(id = "bookingOrderRow", value = @Result(column = "rule_snapshot", property = "ruleSnapshot",
            typeHandler = JacksonTypeHandler.class))
    BookingOrder lockOrderById(long id);

    @Select("SELECT * FROM booking_order WHERE id = #{id} AND merchant_id = #{merchantId} "
            + "AND user_id = #{userId} FOR UPDATE")
    @ResultMap("bookingOrderRow")
    BookingOrder lockOrderScoped(@Param("id") long id, @Param("merchantId") long merchantId,
                                 @Param("userId") long userId);

    /** 确认支付：条件更新与截止判断在同一行锁内完成（严格早于截止）。 */
    @Update("UPDATE booking_order SET status = 'RESERVED', confirmed_at = #{now} "
            + "WHERE id = #{id} AND merchant_id = #{merchantId} AND user_id = #{userId} "
            + "AND status = 'PENDING_PAYMENT' AND capacity_held = 1 AND pay_deadline > #{now}")
    int confirmOrder(@Param("id") long id, @Param("merchantId") long merchantId, @Param("userId") long userId,
                     @Param("now") LocalDateTime now);

    /** 未支付超时取消：条件更新，与支付确认互斥。 */
    @Update("UPDATE booking_order SET status = 'CANCELLED', capacity_held = 0, cancel_stage = 'COMPLETED', "
            + "cancel_requested_at = #{now}, cancelled_at = #{now}, cancel_reason = #{reason} "
            + "WHERE id = #{id} AND status = 'PENDING_PAYMENT' AND capacity_held = 1")
    int cancelUnpaid(@Param("id") long id, @Param("now") LocalDateTime now, @Param("reason") String reason);

    /** 过期候选：只返回待支付且已过截止的订单，批量回收名额。 */
    @Select("SELECT id FROM booking_order WHERE status = 'PENDING_PAYMENT' AND capacity_held = 1 "
            + "AND pay_deadline <= #{now} ORDER BY pay_deadline, id LIMIT #{limit}")
    List<Long> expiredCandidates(@Param("now") LocalDateTime now, @Param("limit") int limit);
}
