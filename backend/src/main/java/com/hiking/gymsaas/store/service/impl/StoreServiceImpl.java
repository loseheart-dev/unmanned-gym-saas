package com.hiking.gymsaas.store.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.hiking.gymsaas.common.exception.BusinessException;
import com.hiking.gymsaas.common.exception.ErrorCode;
import com.hiking.gymsaas.common.idempotency.IdempotencyService;
import com.hiking.gymsaas.common.page.PageQuery;
import com.hiking.gymsaas.common.page.PageResult;
import com.hiking.gymsaas.common.security.CurrentIdentity;
import com.hiking.gymsaas.common.util.Ids;
import com.hiking.gymsaas.common.web.ConfigurationEtags;
import com.hiking.gymsaas.operation.service.AuditService;
import com.hiking.gymsaas.store.mapper.StoreMapper;
import com.hiking.gymsaas.store.model.dto.StoreRequests;
import com.hiking.gymsaas.store.model.entity.Store;
import com.hiking.gymsaas.store.model.vo.StoreViews;
import com.hiking.gymsaas.store.service.StoreRules;
import com.hiking.gymsaas.store.service.StoreService;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;

/**
 * 后台门店（§4.4–§4.7、§4.13）。
 *
 * <p>读请求需要 ADMIN 或 STORE_READ；创建/修改仅 ADMIN。创建后固定 CLOSED；
 * 展示资料修改必须带 Idempotency-Key 与 If-Match（锁内用当前字段重算 ETag 再比对）。
 */
@Service
@Profile("!bootstrap")
public class StoreServiceImpl implements StoreService {
    private static final List<String> PROFILE_FIELDS =
            List.of("name", "address", "latitude", "longitude", "description", "images");

    private final StoreMapper mapper;
    private final IdempotencyService idempotency;
    private final AuditService audit;

    public StoreServiceImpl(StoreMapper mapper, IdempotencyService idempotency, AuditService audit) {
        this.mapper = mapper;
        this.idempotency = idempotency;
        this.audit = audit;
    }

    @Override
    @PreAuthorize("hasRole('ADMIN') or hasAuthority('STORE_READ')")
    public PageResult<StoreViews.AdminStore> list(PageQuery query, String status) {
        var filter = new LambdaQueryWrapper<Store>()
                .eq(status != null && !status.isBlank(), Store::getStatus, status)
                .orderByAsc(Store::getId);
        var page = mapper.selectPage(query.toPage(), filter);
        return new PageResult<>(page.getCurrent(), page.getSize(), page.getTotal(),
                page.getRecords().stream().map(this::view).toList());
    }

    @Override
    @PreAuthorize("hasRole('ADMIN') or hasAuthority('STORE_READ')")
    public StoreViews.AdminStoreEntry detail(String storeId) {
        var store = mapper.selectById(Ids.parse(storeId));
        if (store == null) throw new BusinessException(ErrorCode.NOT_FOUND);
        return entry(store);
    }

    @Override
    @PreAuthorize("hasRole('ADMIN')")
    public IdempotencyService.Outcome<StoreViews.AdminStoreEntry> create(StoreRequests.Create input, String key) {
        StoreRules.validateOpening(input.openTime(), input.closeTime(),
                Boolean.TRUE.equals(input.is24h()), Boolean.TRUE.equals(input.endsNextDay()));
        long merchantId = CurrentIdentity.merchantId();
        List<String> images = validateImages(input.images(), merchantId);
        var latitude = StoreRules.coordinate(input.latitude(), "latitude");
        var longitude = StoreRules.coordinate(input.longitude(), "longitude");
        return idempotency.execute("createStore", "POST", "/api/v1/admin/stores", key, input,
                StoreViews.AdminStoreEntry.class, () -> {
                    var store = new Store();
                    store.setMerchantId(merchantId);
                    store.setName(input.name());
                    store.setAddress(input.address());
                    store.setDescription(input.description());
                    store.setImages(images);
                    store.setLatitude(latitude);
                    store.setLongitude(longitude);
                    store.setOpenTime(input.openTime());
                    store.setCloseTime(input.closeTime());
                    store.setIs24h(Boolean.TRUE.equals(input.is24h()));
                    store.setEndsNextDay(Boolean.TRUE.equals(input.endsNextDay()));
                    store.setCapacity(input.capacity());
                    store.setSlotPriceCent(input.slotPriceCent());
                    store.setOvertimePriceCent(input.overtimePriceCent());
                    store.setOvertimeCapCent(input.overtimeCapCent());
                    // 创建即 CLOSED：启用前必须配置设备并生成可售时段
                    store.setStatus("CLOSED");
                    store.setAdmissionPaused(false);
                    mapper.insert(store);
                    audit.append(merchantId, "STORE_CREATED", "STORE", store.getId(), input.reason(),
                            Map.of("summary", "创建门店",
                                    "after", Map.of("name", input.name(), "address", input.address())));
                    return new IdempotencyService.Outcome<>(201,
                            entry(mapper.selectById(store.getId())));
                });
    }

    @Override
    @PreAuthorize("hasRole('ADMIN')")
    public IdempotencyService.Outcome<StoreViews.AdminStoreEntry> updateProfile(String storeId, JsonNode body,
            String key, String ifMatch) {
        long id = Ids.parse(storeId);
        long merchantId = CurrentIdentity.merchantId();
        String reason = requireReason(body);
        List<String> present = presentFields(body);
        if (present.isEmpty()) throw new BusinessException(ErrorCode.VALIDATION_ERROR);
        return idempotency.execute("updateStoreProfile", "PATCH", "/api/v1/admin/stores/" + id, key,
                body.toString(), StoreViews.AdminStoreEntry.class, () -> {
                    var store = mapper.lock(id);
                    if (store == null) throw new BusinessException(ErrorCode.NOT_FOUND);
                    // 锁内用当前字段重算 ETag 再比对
                    ConfigurationEtags.requireMatch(ifMatch, StoreRules.etag(store));

                    var before = new LinkedHashMap<String, Object>();
                    var after = new LinkedHashMap<String, Object>();
                    var update = new LambdaUpdateWrapper<Store>().eq(Store::getId, id);

                    if (body.has("name")) {
                        String name = requireText(body.get("name"), 1, 100);
                        before.put("name", store.getName());
                        after.put("name", name);
                        update.set(Store::getName, name);
                    }
                    if (body.has("address")) {
                        String address = requireText(body.get("address"), 1, 255);
                        before.put("address", store.getAddress());
                        after.put("address", address);
                        update.set(Store::getAddress, address);
                    }
                    if (body.has("description")) {
                        String description = body.get("description").isNull() ? null
                                : requireText(body.get("description"), 0, 2000);
                        before.put("description", store.getDescription());
                        after.put("description", description);
                        update.set(Store::getDescription, description);
                    }
                    if (body.has("latitude")) {
                        var latitude = body.get("latitude").isNull() ? null
                                : StoreRules.coordinate(nodeText(body.get("latitude")), "latitude");
                        before.put("latitude", StoreRules.decimal(store.getLatitude()));
                        after.put("latitude", StoreRules.decimal(latitude));
                        update.set(Store::getLatitude, latitude);
                    }
                    if (body.has("longitude")) {
                        var longitude = body.get("longitude").isNull() ? null
                                : StoreRules.coordinate(nodeText(body.get("longitude")), "longitude");
                        before.put("longitude", StoreRules.decimal(store.getLongitude()));
                        after.put("longitude", StoreRules.decimal(longitude));
                        update.set(Store::getLongitude, longitude);
                    }
                    if (body.has("images")) {
                        List<String> images = body.get("images").isNull() ? List.of()
                                : validateImagesNode(body.get("images"), merchantId);
                        before.put("images", store.getImages() == null ? List.of() : store.getImages());
                        after.put("images", images);
                        // JSON 列走实体更新，才能套用 JacksonTypeHandler
                        var imagePatch = new Store();
                        imagePatch.setId(id);
                        imagePatch.setImages(images);
                        mapper.updateById(imagePatch);
                    }
                    if (!update.isEmptyOfWhere() && (body.has("name") || body.has("address")
                            || body.has("description") || body.has("latitude") || body.has("longitude"))) {
                        mapper.update(null, update);
                    }
                    audit.append(merchantId, "STORE_PROFILE_CHANGED", "STORE", id, reason,
                            Map.of("summary", "修改门店展示资料", "before", before, "after", after));
                    return new IdempotencyService.Outcome<>(200, entry(mapper.selectById(id)));
                });
    }

    @Override
    @PreAuthorize("hasRole('ADMIN') or hasAuthority('STORE_READ')")
    public StoreViews.AdminSlotList slots(String storeId, String date) {
        long id = Ids.parse(storeId);
        var store = mapper.selectById(id);
        if (store == null) throw new BusinessException(ErrorCode.NOT_FOUND);
        LocalDate businessDate = parseDate(date);
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        var items = mapper.slots(id, StoreRules.businessDayStartUtc(businessDate),
                        StoreRules.businessDayEndUtc(businessDate)).stream()
                .map(slot -> {
                    var state = StoreRules.slotState(slot, store.getStatus(),
                            Boolean.TRUE.equals(store.getAdmissionPaused()), now);
                    return new StoreViews.AdminSlot(slot.getId().toString(), slot.getStoreId().toString(),
                            StoreRules.utc(slot.getStartAt()), StoreRules.utc(slot.getEndAt()),
                            slot.getCapacity() == null ? 0 : slot.getCapacity(),
                            StoreRules.remaining(slot), slot.getPriceCent() == null ? 0 : slot.getPriceCent(),
                            slot.getStatus(), state.bookable(), state.unavailableReason(),
                            StoreRules.slotEtag(slot));
                })
                .toList();
        return new StoreViews.AdminSlotList(store.getId().toString(), businessDate,
                StoreRules.lastBookableDate(), items);
    }

    // ---------- 校验与视图 ----------

    private StoreViews.AdminStoreEntry entry(Store store) {
        return new StoreViews.AdminStoreEntry(view(store), StoreRules.etag(store));
    }

    private StoreViews.AdminStore view(Store store) {
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

    private String requireReason(JsonNode body) {
        if (body == null || !body.hasNonNull("reason")) throw new BusinessException(ErrorCode.VALIDATION_ERROR);
        return requireText(body.get("reason"), 1, 500);
    }

    private List<String> presentFields(JsonNode body) {
        List<String> present = new ArrayList<>();
        if (body == null) return present;
        var iterator = body.fieldNames();
        while (iterator.hasNext()) {
            String field = iterator.next();
            if (PROFILE_FIELDS.contains(field)) present.add(field);
        }
        return present;
    }

    private String requireText(JsonNode node, int min, int max) {
        if (node == null || !node.isTextual()) throw new BusinessException(ErrorCode.VALIDATION_ERROR);
        String value = node.asText();
        if (value.length() < min || value.length() > max) throw new BusinessException(ErrorCode.VALIDATION_ERROR);
        return value;
    }

    private List<String> validateImages(List<String> images, long merchantId) {
        if (images == null) return List.of();
        if (images.size() > 20) throw new BusinessException(ErrorCode.VALIDATION_ERROR);
        for (String key : images) {
            if (key == null || !key.startsWith("stores/" + merchantId + "/")) {
                throw new BusinessException(ErrorCode.VALIDATION_ERROR);
            }
        }
        return List.copyOf(images);
    }

    private List<String> validateImagesNode(JsonNode node, long merchantId) {
        if (!node.isArray()) throw new BusinessException(ErrorCode.VALIDATION_ERROR);
        List<String> images = new ArrayList<>();
        for (JsonNode item : node) {
            if (!item.isTextual()) throw new BusinessException(ErrorCode.VALIDATION_ERROR);
            images.add(item.asText());
        }
        return validateImages(images, merchantId);
    }

    private String nodeText(JsonNode node) {
        if (!node.isNumber() && !node.isTextual()) throw new BusinessException(ErrorCode.VALIDATION_ERROR);
        return node.asText();
    }

    private LocalDate parseDate(String raw) {
        if (raw == null || raw.isBlank()) throw new BusinessException(ErrorCode.VALIDATION_ERROR);
        try { return LocalDate.parse(raw.trim()); }
        catch (DateTimeParseException ex) { throw new BusinessException(ErrorCode.VALIDATION_ERROR); }
    }
}
