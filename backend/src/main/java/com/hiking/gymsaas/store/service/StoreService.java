package com.hiking.gymsaas.store.service;

import com.hiking.gymsaas.common.idempotency.IdempotencyService;
import com.hiking.gymsaas.common.page.PageQuery;
import com.hiking.gymsaas.common.page.PageResult;
import com.hiking.gymsaas.store.model.dto.StoreRequests;
import com.hiking.gymsaas.store.model.vo.StoreViews;

/** 后台门店管理（接口文档 §4.4–§4.7、§4.13）。 */
public interface StoreService {
    PageResult<StoreViews.AdminStore> list(PageQuery query, String status);

    StoreViews.AdminStoreEntry detail(String storeId);

    IdempotencyService.Outcome<StoreViews.AdminStoreEntry> create(StoreRequests.Create input, String key);

    IdempotencyService.Outcome<StoreViews.AdminStoreEntry> updateProfile(String storeId,
            com.fasterxml.jackson.databind.JsonNode body, String key, String ifMatch);

    StoreViews.AdminSlotList slots(String storeId, String date);
}
