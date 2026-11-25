package com.hiking.gymsaas.store.mapper;

import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 架构例外的只读跨模块检查：门店停用/准入恢复前需要看未结束到店、未履约订单与未决设备命令。
 *
 * <p>这些事实属于 visit / booking / device 模块；在对应模块提供端口前，这里只做 SELECT 计数与 ID 列表，
 * 不写任何跨模块表（后台请求带商家身份，租户拦截器会自动限定 merchant_id）。
 */
@Mapper
public interface StoreOpsMapper {

    @Select("SELECT COUNT(*) FROM visit_session WHERE store_id = #{storeId} AND status = 'IN_STORE'")
    long inStoreVisits(@Param("storeId") long storeId);

    @Select("SELECT COUNT(*) FROM booking_order WHERE store_id = #{storeId} "
            + "AND status IN ('PENDING_PAYMENT','RESERVED','IN_USE','CANCEL_PENDING','NO_SHOW_PENDING') "
            + "AND end_at > #{now}")
    long unfulfilledOrders(@Param("storeId") long storeId, @Param("now") LocalDateTime now);

    @Select("SELECT COUNT(*) FROM device_operation WHERE store_id = #{storeId} "
            + "AND status IN ('PENDING','RUNNING','UNKNOWN')")
    long pendingOperations(@Param("storeId") long storeId);

    @Select("SELECT id FROM booking_order WHERE store_id = #{storeId} "
            + "AND status IN ('PENDING_PAYMENT','RESERVED','IN_USE','CANCEL_PENDING','NO_SHOW_PENDING') "
            + "AND end_at > #{now} ORDER BY id LIMIT 50")
    List<Long> futureOrderIds(@Param("storeId") long storeId, @Param("now") LocalDateTime now);
    @Select("SELECT COUNT(*) FROM review_task WHERE store_id = #{storeId} "
            + "AND status IN ('OPEN','PROCESSING')")
    long openReviewTasks(@Param("storeId") long storeId);
}