package com.hiking.gymsaas.store.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hiking.gymsaas.store.model.entity.StoreSlot;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 后台时段访问。商家隔离由拦截器注入，**门店范围必须显式限定 store_id**：
 * 规则预览、承诺量统计和批量调整都不允许跨门店读写（同一商家多门店）。
 */
@Mapper
public interface StoreSlotMapper extends BaseMapper<StoreSlot> {

    @Select("SELECT * FROM store_slot WHERE id = #{id} AND store_id = #{storeId} FOR UPDATE")
    StoreSlot lock(@Param("id") long id, @Param("storeId") long storeId);

    @Select("SELECT * FROM store_slot WHERE store_id = #{storeId} AND start_at > #{now} AND reserved_count = 0")
    List<StoreSlot> futureUnsold(@Param("storeId") long storeId, @Param("now") LocalDateTime now);

    @Select("SELECT * FROM store_slot WHERE store_id = #{storeId} AND start_at > #{now} "
            + "AND reserved_count > 0 ORDER BY start_at")
    List<StoreSlot> futureCommitted(@Param("storeId") long storeId, @Param("now") LocalDateTime now);

    @Select("SELECT COALESCE(MAX(reserved_count),0) FROM store_slot "
            + "WHERE store_id = #{storeId} AND start_at > #{now}")
    int maxFutureReserved(@Param("storeId") long storeId, @Param("now") LocalDateTime now);

    /** 规则应用：只更新目标门店未来未售时段的容量与价格，已售时段的订单规则快照不变。 */
    @Update("UPDATE store_slot SET capacity = #{capacity}, price_cent = #{priceCent} "
            + "WHERE store_id = #{storeId} AND start_at > #{now} AND reserved_count = 0")
    int updateUnsoldRules(@Param("storeId") long storeId, @Param("now") LocalDateTime now,
                          @Param("capacity") int capacity, @Param("priceCent") long priceCent);

    /** 规则应用后不再落在新营业窗口内的未售时段：关闭而不是删除，保留历史。 */
    @Update("UPDATE store_slot SET status = 'CLOSED' "
            + "WHERE store_id = #{storeId} AND id = #{slotId} AND start_at > #{now} AND reserved_count = 0")
    int closeUnsold(@Param("storeId") long storeId, @Param("slotId") long slotId,
                    @Param("now") LocalDateTime now);

    /** 时段生成：唯一键防重，重复静默忽略；只补完整小时段，已存在的人工覆盖（4.15）不被覆盖。 */
    @Insert("INSERT IGNORE INTO store_slot(merchant_id, store_id, start_at, end_at, capacity, reserved_count, "
            + "price_cent, status) VALUES(#{merchantId}, #{storeId}, #{startAt}, #{endAt}, #{capacity}, 0, "
            + "#{priceCent}, 'OPEN')")
    int insertIgnore(@Param("merchantId") long merchantId, @Param("storeId") long storeId,
                     @Param("startAt") LocalDateTime startAt, @Param("endAt") LocalDateTime endAt,
                     @Param("capacity") int capacity, @Param("priceCent") long priceCent);
}
