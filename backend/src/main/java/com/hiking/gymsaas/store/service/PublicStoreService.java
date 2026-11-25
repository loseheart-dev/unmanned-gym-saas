package com.hiking.gymsaas.store.service;

import com.hiking.gymsaas.common.page.PageQuery;
import com.hiking.gymsaas.common.page.PageResult;

/** 公开门店与时段（接口文档 §4.1–§4.3，无需登录）。 */
public interface PublicStoreService {
    PageResult<com.hiking.gymsaas.store.model.vo.StoreViews.PublicStoreItem> list(PageQuery query);

    com.hiking.gymsaas.store.model.vo.StoreViews.PublicStoreDetail detail(String storeId);

    com.hiking.gymsaas.store.model.vo.StoreViews.PublicSlotList slots(String storeId, String date);
}
