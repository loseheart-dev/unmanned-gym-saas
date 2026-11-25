package com.hiking.gymsaas.store.service;

import com.hiking.gymsaas.store.model.entity.Store;
import com.hiking.gymsaas.store.model.vo.StoreViews;
import java.util.List;

/** 门店视图组装（规则服务与门店服务共用，避免重复拼装）。 */
public final class StoreSupport {
    private StoreSupport() { }

    public static StoreViews.AdminStore view(Store store) {
        return new StoreViews.AdminStore(store.getId().toString(), store.getName(), store.getAddress(),
                StoreRules.decimal(store.getLatitude()), StoreRules.decimal(store.getLongitude()),
                store.getDescription(), store.getImages() == null ? List.of() : store.getImages(),
                StoreRules.time(store.getOpenTime()), StoreRules.time(store.getCloseTime()),
                Boolean.TRUE.equals(store.getIs24h()), Boolean.TRUE.equals(store.getEndsNextDay()),
                store.getCapacity() == null ? 0 : store.getCapacity(),
                store.getSlotPriceCent() == null ? 0 : store.getSlotPriceCent(),
                store.getOvertimePriceCent() == null ? 0 : store.getOvertimePriceCent(),
                store.getOvertimeCapCent() == null ? 0 : store.getOvertimeCapCent(),
                store.getStatus(), Boolean.TRUE.equals(store.getAdmissionPaused()), store.getPauseReason(),
                StoreRules.etag(store));
    }

    public static StoreViews.AdminStoreEntry entry(Store store) {
        return new StoreViews.AdminStoreEntry(view(store), StoreRules.etag(store));
    }
}
