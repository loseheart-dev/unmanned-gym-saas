package com.hiking.gymsaas.store.service.impl;

import com.hiking.gymsaas.common.exception.BusinessException;
import com.hiking.gymsaas.common.exception.ErrorCode;
import com.hiking.gymsaas.common.page.PageQuery;
import com.hiking.gymsaas.common.page.PageResult;
import com.hiking.gymsaas.common.util.UtcTimes;
import com.hiking.gymsaas.store.mapper.PublicStoreMapper;
import com.hiking.gymsaas.store.model.entity.Store;
import com.hiking.gymsaas.store.model.vo.StoreViews;
import com.hiking.gymsaas.store.service.PublicStoreService;
import com.hiking.gymsaas.store.service.StoreRules;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/**
 * 公开门店与时段（§4.1–§4.3）。
 *
 * <p>匿名请求没有租户上下文，全部查询走显式忽略商家隔离的只读 Mapper，
 * 并自行约束"启用商家 + OPEN 门店"；停用商家或不可见门店一律 404。
 * 访问量、设备来源在相应模块实现前明确返回 UNKNOWN，不伪造估计值。
 */
@Service
@Profile("!bootstrap")
public class PublicStoreServiceImpl implements PublicStoreService {
    private final PublicStoreMapper mapper;

    public PublicStoreServiceImpl(PublicStoreMapper mapper) { this.mapper = mapper; }

    @Override
    public PageResult<StoreViews.PublicStoreItem> list(PageQuery query) {
        long total = mapper.countOpenStores();
        if (total == 0) return new PageResult<>(query.getPage(), query.getPageSize(), 0, List.of());
        List<Long> ids = mapper.pageOpenStoreIds((long) (query.getPage() - 1) * query.getPageSize(),
                query.getPageSize());
        // 当前页无记录时直接返回空页，避免空集合查询；total 保持过滤后总数
        if (ids.isEmpty()) return new PageResult<>(query.getPage(), query.getPageSize(), total, List.of());
        List<Store> stores = mapper.storesByIds(ids);
        Map<Long, String> merchantNames = mapper.merchantsByIds(
                        stores.stream().map(Store::getMerchantId).distinct().toList()).stream()
                .filter(merchant -> "ACTIVE".equals(merchant.status()))
                .collect(Collectors.toMap(PublicStoreMapper.MerchantStatus::id,
                        PublicStoreMapper.MerchantStatus::name));
        List<StoreViews.PublicStoreItem> items = stores.stream()
                .filter(store -> merchantNames.containsKey(store.getMerchantId()))
                .map(store -> item(store, merchantNames.get(store.getMerchantId())))
                .toList();
        return new PageResult<>(query.getPage(), query.getPageSize(), total, items);
    }

    @Override
    public StoreViews.PublicStoreDetail detail(String storeId) {
        var store = visibleStore(com.hiking.gymsaas.common.util.Ids.parse(storeId));
        var merchant = mapper.merchant(store.getMerchantId());
        return new StoreViews.PublicStoreDetail(item(store, merchant.name()), store.getDescription(),
                store.getImages() == null ? List.of() : store.getImages(), store.getLatitude(),
                store.getLongitude(), StoreRules.time(store.getOpenTime()), StoreRules.time(store.getCloseTime()),
                Boolean.TRUE.equals(store.getIs24h()), Boolean.TRUE.equals(store.getEndsNextDay()),
                StoreRules.TIMEZONE, StoreRules.SLOT_MINUTES, StoreRules.BOOKING_DAYS,
                store.getOvertimePriceCent(), store.getOvertimeCapCent(),
                Boolean.TRUE.equals(store.getAdmissionPaused()), store.getPauseReason(), 0, 0);
    }

    @Override
    public StoreViews.PublicSlotList slots(String storeId, String date) {
        LocalDate businessDate = parseDate(date);
        LocalDate today = StoreRules.today();
        LocalDate lastBookable = StoreRules.lastBookableDate();
        if (businessDate.isBefore(today) || businessDate.isAfter(lastBookable)) {
            throw new BusinessException(ErrorCode.INVALID_TIME_RANGE);
        }
        var store = visibleStore(com.hiking.gymsaas.common.util.Ids.parse(storeId));
        LocalDateTime now = LocalDateTime.now(java.time.ZoneOffset.UTC);
        var items = mapper.slots(store.getId(), StoreRules.businessDayStartUtc(businessDate),
                        StoreRules.businessDayEndUtc(businessDate)).stream()
                .filter(slot -> slot.getStartAt() != null && slot.getStartAt().isAfter(now))
                .map(slot -> {
                    // 与下单规则一致：门店 CLOSED / 暂停准入时展示为不可预约
                    var state = StoreRules.slotState(slot, store.getStatus(),
                            Boolean.TRUE.equals(store.getAdmissionPaused()), now);
                    return new StoreViews.PublicSlot(slot.getId().toString(), slot.getStoreId().toString(),
                            StoreRules.utc(slot.getStartAt()), StoreRules.utc(slot.getEndAt()),
                            slot.getCapacity() == null ? 0 : slot.getCapacity(),
                            StoreRules.remaining(slot), slot.getPriceCent() == null ? 0 : slot.getPriceCent(),
                            slot.getStatus(), state.bookable(), state.unavailableReason());
                })
                .toList();
        return new StoreViews.PublicSlotList(store.getId().toString(), businessDate, lastBookable, items);
    }

    private Store visibleStore(long storeId) {
        Store store = mapper.store(storeId);
        if (store == null) throw new BusinessException(ErrorCode.NOT_FOUND);
        var merchant = mapper.merchant(store.getMerchantId());
        boolean visible = merchant != null && "ACTIVE".equals(merchant.status())
                && ("OPEN".equals(store.getStatus()) || "CLOSED".equals(store.getStatus()));
        if (!visible) throw new BusinessException(ErrorCode.NOT_FOUND);
        return store;
    }

    private StoreViews.PublicStoreItem item(Store store, String merchantName) {
        return new StoreViews.PublicStoreItem(store.getId().toString(), store.getMerchantId().toString(),
                merchantName, store.getName(), store.getAddress(), store.getStatus(),
                store.getSlotPriceCent() == null ? 0 : store.getSlotPriceCent(), null, "UNKNOWN", "UNKNOWN");
    }

    private LocalDate parseDate(String raw) {
        if (raw == null || raw.isBlank()) throw new BusinessException(ErrorCode.VALIDATION_ERROR);
        try { return LocalDate.parse(raw.trim()); }
        catch (DateTimeParseException ex) { throw new BusinessException(ErrorCode.VALIDATION_ERROR); }
    }
}
