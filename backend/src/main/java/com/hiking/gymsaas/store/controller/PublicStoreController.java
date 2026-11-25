package com.hiking.gymsaas.store.controller;

import com.hiking.gymsaas.common.page.PageQuery;
import com.hiking.gymsaas.common.page.PageResult;
import com.hiking.gymsaas.common.web.ApiResponse;
import com.hiking.gymsaas.store.model.vo.StoreViews;
import com.hiking.gymsaas.store.service.PublicStoreService;
import jakarta.validation.Valid;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.*;

/** 顾客端公开门店与时段（§4.1–§4.3，无需登录）。 */
@RestController
@Profile("!bootstrap")
@RequestMapping("/app/stores")
public class PublicStoreController {
    private final PublicStoreService stores;

    public PublicStoreController(PublicStoreService stores) { this.stores = stores; }

    @GetMapping
    public ApiResponse<PageResult<StoreViews.PublicStoreItem>> list(@Valid PageQuery page) {
        return ApiResponse.ok(stores.list(page));
    }

    @GetMapping("/{storeId}")
    public ApiResponse<StoreViews.PublicStoreDetail> detail(@PathVariable String storeId) {
        return ApiResponse.ok(stores.detail(storeId));
    }

    @GetMapping("/{storeId}/slots")
    public ApiResponse<StoreViews.PublicSlotList> slots(@PathVariable String storeId,
                                                        @RequestParam String date) {
        return ApiResponse.ok(stores.slots(storeId, date));
    }
}
