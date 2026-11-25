package com.hiking.gymsaas.store.service.impl;

import com.hiking.gymsaas.common.exception.BusinessException;
import com.hiking.gymsaas.common.exception.ErrorCode;
import com.hiking.gymsaas.common.idempotency.IdempotencyService;
import com.hiking.gymsaas.common.security.CurrentIdentity;
import com.hiking.gymsaas.common.util.Hashes;
import com.hiking.gymsaas.common.util.Ids;
import com.hiking.gymsaas.store.config.StorageProperties;
import com.hiking.gymsaas.store.mapper.StoreMapper;
import com.hiking.gymsaas.store.model.vo.StoreRuleViews;
import com.hiking.gymsaas.store.service.LocalObjectStorage;
import com.hiking.gymsaas.store.service.StoreImageService;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

/**
 * 4.8 门店展示图片上传（M5 开发实现）。
 *
 * <p>仅 JPEG/PNG/WebP（魔数）+ 可解码 + ≤5MiB；只接受公开展示图，不接受人脸；
 * objectKey 属本商家（stores/{merchantId}/...），上传后用 4.7 引用。
 * 幂等键的请求摘要包含文件字节摘要，重放不会重复写文件。
 */
@Service
@Profile("!bootstrap")
public class StoreImageServiceImpl implements StoreImageService {
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final StoreMapper stores;
    private final LocalObjectStorage storage;
    private final StorageProperties properties;
    private final IdempotencyService idempotency;

    public StoreImageServiceImpl(StoreMapper stores, LocalObjectStorage storage,
                                 StorageProperties properties, IdempotencyService idempotency) {
        this.stores = stores;
        this.storage = storage;
        this.properties = properties;
        this.idempotency = idempotency;
    }

    @Override
    @PreAuthorize("hasRole('ADMIN')")
    public IdempotencyService.Outcome<StoreRuleViews.UploadSnapshot> upload(String storeId, MultipartFile file,
                                                                   String key) {
        long id = Ids.parse(storeId);
        long merchantId = CurrentIdentity.merchantId();
        if (stores.selectById(id) == null) throw new BusinessException(ErrorCode.NOT_FOUND);
        if (file == null || file.isEmpty()) throw new BusinessException(ErrorCode.VALIDATION_ERROR);
        if (file.getSize() > properties.getMaxImageBytes()) throw new BusinessException(ErrorCode.FILE_TOO_LARGE);
        byte[] bytes;
        try { bytes = file.getBytes(); }
        catch (IOException ex) { throw new BusinessException(ErrorCode.VALIDATION_ERROR); }
        String contentType = detectType(bytes);
        validateDecodable(bytes);
        String extension = "image/jpeg".equals(contentType) ? "jpg"
                : "image/png".equals(contentType) ? "png" : "webp";
        String objectKey = "stores/" + merchantId + "/" + LocalDate.now().format(DAY) + "/"
                + UUID.randomUUID().toString().replace("-", "") + "." + extension;
        String digest = sha256(bytes);
        return idempotency.execute("uploadStoreImage", "POST", "/api/v1/admin/stores/" + id + "/images", key,
                Map.of("digest", digest, "size", bytes.length, "contentType", contentType),
                StoreRuleViews.UploadSnapshot.class, () -> {
                    storage.save(objectKey, bytes);
                    return new IdempotencyService.Outcome<>(201, new StoreRuleViews.UploadSnapshot(objectKey,
                            storage.publicUrl(objectKey), contentType, bytes.length));
                });
    }

    private String detectType(byte[] bytes) {
        if (bytes.length >= 3 && (bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xD8
                && (bytes[2] & 0xFF) == 0xFF) return "image/jpeg";
        if (bytes.length >= 8 && (bytes[0] & 0xFF) == 0x89 && bytes[1] == 'P' && bytes[2] == 'N'
                && bytes[3] == 'G') return "image/png";
        if (bytes.length >= 12 && bytes[0] == 'R' && bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == 'F'
                && bytes[8] == 'W' && bytes[9] == 'E' && bytes[10] == 'B' && bytes[11] == 'P') return "image/webp";
        throw new BusinessException(ErrorCode.UNSUPPORTED_MEDIA_TYPE);
    }

    private void validateDecodable(byte[] bytes) {
        try {
            if (ImageIO.read(new ByteArrayInputStream(bytes)) == null) {
                throw new BusinessException(ErrorCode.UNSUPPORTED_MEDIA_TYPE);
            }
        } catch (IOException ex) { throw new BusinessException(ErrorCode.UNSUPPORTED_MEDIA_TYPE); }
    }

    private String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
