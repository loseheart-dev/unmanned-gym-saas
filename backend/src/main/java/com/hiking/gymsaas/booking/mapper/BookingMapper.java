package com.hiking.gymsaas.booking.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.hiking.gymsaas.store.model.entity.Store;
import com.hiking.gymsaas.store.model.entity.StoreSlot;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 顾客侧预约读取/占位。
 *
 * <p>顾客身份没有后台的"当前商家"，商家隔离拦截器无法自动注入；因此这里显式忽略租户改写，
 * 每条 SQL 都自行绑定已验证的 user_id 或 slot/store 归属，禁止被后台写路径复用。
 *
 * <p>锁顺序遵循 {@code database/transaction_patterns.sql}：
 * customer → merchant_customer → store(FOR SHARE) → store_slot(FOR UPDATE) → locked_now
 * → 重叠订单(FOR UPDATE) → 占位；写入事务内所有资格判断使用当前读。
 */
@Mapper
@InterceptorIgnore(tenantLine = "true")
public interface BookingMapper {

    /** 锁内当前时间（DB UTC），避免应用时钟偏差影响支付截止边界。 */
    @Select("SELECT UTC_TIMESTAMP(3)")
    LocalDateTime lockedNow();

    @Select("SELECT id FROM customer_user WHERE id = #{id} FOR UPDATE")
    Long lockCustomerUser(long id);

    /** booking_order 外键要求 (merchant_id,user_id) 已有商家顾客关系；首次预约时建立。 */
    @Insert("INSERT IGNORE INTO merchant_customer(merchant_id, user_id) VALUES(#{merchantId}, #{userId})")
    int ensureMerchantCustomer(@Param("merchantId") long merchantId, @Param("userId") long userId);

    @Select("SELECT merchant_id, user_id, blocked, block_reason, credit_score, booking_restricted_until "
            + "FROM merchant_customer WHERE merchant_id = #{merchantId} AND user_id = #{userId} FOR UPDATE")
    MerchantCustomerRow lockMerchantCustomer(@Param("merchantId") long merchantId, @Param("userId") long userId);

    /** 锁门店配置（FOR SHARE）：规则/状态更新会拿 FOR UPDATE，读写互斥。 */
    @Select("SELECT id, merchant_id, name, address, open_time, close_time, is_24h, ends_next_day, capacity, "
            + "slot_price_cent, overtime_price_cent, overtime_cap_cent, status, admission_paused "
            + "FROM store WHERE id = #{storeId} AND merchant_id = #{merchantId} FOR SHARE")
    Store lockStoreConfig(@Param("storeId") long storeId, @Param("merchantId") long merchantId);

    @Select("SELECT id, store_id, merchant_id, start_at, end_at, capacity, reserved_count, price_cent, status "
            + "FROM store_slot WHERE id = #{slotId} AND merchant_id = #{merchantId} AND store_id = #{storeId} "
            + "FOR UPDATE")
    StoreSlot lockSlotScoped(@Param("slotId") long slotId, @Param("merchantId") long merchantId,
                             @Param("storeId") long storeId);

    @Select("SELECT id, store_id, merchant_id, start_at, end_at, capacity, reserved_count, price_cent, status "
            + "FROM store_slot WHERE id = #{id}")
    StoreSlot slotForRead(long id);

    @Select("SELECT id, merchant_id, name, address, open_time, close_time, is_24h, ends_next_day, capacity, "
            + "slot_price_cent, overtime_price_cent, overtime_cap_cent, status, admission_paused "
            + "FROM store WHERE id = #{id}")
    Store storeForRead(long id);

    @Select("SELECT id, name, status FROM merchant WHERE id = #{id}")
    MerchantRow merchant(long id);

    /** 当前读锁定同一用户重叠的有效预约（相邻边界不算重叠）。 */
    @Select("SELECT id FROM booking_order WHERE user_id = #{userId} AND end_at > #{start} AND start_at < #{end} "
            + "AND status IN ('PENDING_PAYMENT','RESERVED','IN_USE','CANCEL_PENDING') ORDER BY id FOR UPDATE")
    List<Long> lockOverlappingOrders(@Param("userId") long userId, @Param("start") LocalDateTime start,
                                     @Param("end") LocalDateTime end);

    @Select("SELECT COUNT(*) FROM visit_settlement WHERE merchant_id = #{merchantId} AND user_id = #{userId} "
            + "AND status = 'PAYMENT_DUE'")
    long outstandingPayments(@Param("merchantId") long merchantId, @Param("userId") long userId);

    /** 原子占位：容量不足或时段非 OPEN 时返回 0，绝不超卖（门店/商家范围显式绑定）。 */
    @Update("UPDATE store_slot SET reserved_count = reserved_count + 1 "
            + "WHERE id = #{slotId} AND merchant_id = #{merchantId} AND store_id = #{storeId} "
            + "AND status = 'OPEN' AND reserved_count < capacity")
    int holdCapacity(@Param("slotId") long slotId, @Param("merchantId") long merchantId,
                     @Param("storeId") long storeId);

    /** 释放占位（过期/取消）：不会把 reserved_count 减成负数。 */
    @Update("UPDATE store_slot SET reserved_count = reserved_count - 1 "
            + "WHERE id = #{slotId} AND merchant_id = #{merchantId} AND store_id = #{storeId} "
            + "AND reserved_count > 0")
    int releaseCapacity(@Param("slotId") long slotId, @Param("merchantId") long merchantId,
                        @Param("storeId") long storeId);

    @Select("<script>SELECT id, name FROM store WHERE id IN "
            + "<foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach></script>")
    List<StoreNameRow> storeNames(@Param("ids") List<Long> ids);

    record MerchantRow(Long id, String name, String status) { }
    record MerchantCustomerRow(Long merchantId, Long userId, Boolean blocked, String blockReason,
                               Integer creditScore, LocalDateTime bookingRestrictedUntil) { }
    record StoreNameRow(Long id, String name) { }
}
