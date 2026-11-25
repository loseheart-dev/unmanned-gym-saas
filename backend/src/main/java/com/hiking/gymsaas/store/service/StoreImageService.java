package com.hiking.gymsaas.store.service;

import com.hiking.gymsaas.common.idempotency.IdempotencyService;
import com.hiking.gymsaas.store.model.vo.StoreRuleViews;
import org.springframework.web.multipart.MultipartFile;

/** 4.8 门店展示图片上传。 */
public interface StoreImageService {
    IdempotencyService.Outcome<StoreRuleViews.UploadSnapshot> upload(String storeId, MultipartFile file, String key);
}
