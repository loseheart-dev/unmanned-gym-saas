package com.hiking.gymsaas.store.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import com.hiking.gymsaas.store.model.entity.Store;
import com.hiking.gymsaas.store.model.entity.StoreSlot;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.ResultMap;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;

/**
 * 公开门店查询：匿名请求没有租户上下文，必须显式忽略商家隔离拦截器，
 * 并在 SQL 中自行约束"启用商家 + 公开营业门店"，禁止用于任何写操作。
 */
@Mapper
@InterceptorIgnore(tenantLine = "true")
public interface PublicStoreMapper {

    @Select("SELECT COUNT(*) FROM store s JOIN merchant m ON m.id = s.merchant_id "
            + "WHERE m.status = 'ACTIVE' AND s.status = 'OPEN'")
    long countOpenStores();

    @Select("SELECT s.id FROM store s JOIN merchant m ON m.id = s.merchant_id "
            + "WHERE m.status = 'ACTIVE' AND s.status = 'OPEN' ORDER BY s.id LIMIT #{offset}, #{size}")
    List<Long> pageOpenStoreIds(@Param("offset") long offset, @Param("size") long size);

    @Results(id = "publicStoreRow", value = @Result(column = "images", property = "images",
            typeHandler = JacksonTypeHandler.class))
    @Select("SELECT id, merchant_id, name, address, latitude, longitude, description, images, open_time, "
            + "close_time, is_24h, ends_next_day, capacity, slot_price_cent, overtime_price_cent, "
            + "overtime_cap_cent, status, admission_paused, pause_reason FROM store WHERE id = #{id}")
    Store store(long id);

    @ResultMap("publicStoreRow")
    @Select("<script>SELECT id, merchant_id, name, address, latitude, longitude, description, images, "
            + "open_time, close_time, is_24h, ends_next_day, capacity, slot_price_cent, overtime_price_cent, "
            + "overtime_cap_cent, status, admission_paused, pause_reason FROM store WHERE id IN "
            + "<foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach> ORDER BY id</script>")
    List<Store> storesByIds(@Param("ids") List<Long> ids);

    @Select("<script>SELECT id, name, status FROM merchant WHERE id IN "
            + "<foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach></script>")
    List<MerchantStatus> merchantsByIds(@Param("ids") List<Long> ids);

    @Select("SELECT id, name, status FROM merchant WHERE id = #{id}")
    MerchantStatus merchant(long id);

    @Select("SELECT id, store_id, start_at, end_at, capacity, reserved_count, price_cent, status "
            + "FROM store_slot WHERE store_id = #{storeId} AND start_at >= #{from} AND start_at < #{to} "
            + "ORDER BY start_at")
    List<StoreSlot> slots(@Param("storeId") long storeId, @Param("from") LocalDateTime from,
                          @Param("to") LocalDateTime to);

    /** 公开只读视图：商家名与启用状态。 */
    record MerchantStatus(Long id, String name, String status) { }
}
