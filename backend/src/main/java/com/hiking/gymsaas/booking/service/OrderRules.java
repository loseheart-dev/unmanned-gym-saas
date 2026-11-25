package com.hiking.gymsaas.booking.service;

import com.hiking.gymsaas.store.model.entity.Store;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 下单/支付规则纯函数（Q030–Q039）：
 * 支付截止取下单后 15 分钟与时段开始中较早者；截止必须是严格早于才开始支付；
 * 订单号服务端生成；规则快照字段固定（版本/分钟/宽限/超时单价与封顶/舍入/时区/商家名/门店名）。
 */
public final class OrderRules {
    private static final DateTimeFormatter ORDER_STAMP = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final char[] ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789".toCharArray();

    private OrderRules() { }

    public static LocalDateTime payDeadline(LocalDateTime nowUtc, LocalDateTime startUtc) {
        LocalDateTime inFifteenMinutes = nowUtc.plusMinutes(15);
        return inFifteenMinutes.isBefore(startUtc) ? inFifteenMinutes : startUtc;
    }

    public static String orderNo(LocalDateTime nowUtc) {
        var random = ThreadLocalRandom.current();
        StringBuilder suffix = new StringBuilder(6);
        for (int i = 0; i < 6; i++) suffix.append(ALPHABET[random.nextInt(ALPHABET.length)]);
        return "O" + nowUtc.format(ORDER_STAMP) + suffix;
    }

    public static String paymentNo(LocalDateTime nowUtc) {
        var random = ThreadLocalRandom.current();
        StringBuilder suffix = new StringBuilder(8);
        for (int i = 0; i < 8; i++) suffix.append(ALPHABET[random.nextInt(ALPHABET.length)]);
        return "P" + nowUtc.format(ORDER_STAMP) + suffix;
    }

    /** 订单规则快照：门店名称与计费口径在下单时固化，后续改价不影响既有订单。 */
    public static java.util.Map<String, Object> ruleSnapshot(Store store, String merchantName) {
        return new java.util.LinkedHashMap<>(java.util.Map.of(
                "version", "v1.0",
                "slotMinutes", 60,
                "entryGraceMinutes", 0,
                "exitGraceMinutes", 0,
                "overtimePriceCent", store.getOvertimePriceCent() == null ? 0 : store.getOvertimePriceCent(),
                "overtimeCapCent", store.getOvertimeCapCent() == null ? 0 : store.getOvertimeCapCent(),
                "rounding", "CEIL_MINUTE",
                "timezone", "Asia/Shanghai",
                "merchantName", merchantName,
                "storeName", store.getName()));
    }

    public static long baseAmount(Store store) {
        return store.getSlotPriceCent() == null ? 0 : store.getSlotPriceCent();
    }
    /** 只接受严格早于支付截止的确认（Q034：恰好截止时刻视为过期）。 */
    public static boolean paymentAllowed(LocalDateTime nowUtc, LocalDateTime payDeadline) {
        return payDeadline != null && nowUtc.isBefore(payDeadline);
    }

    /** 未支付过期判定：截止时刻及之后都算过期。 */
    public static boolean expired(LocalDateTime nowUtc, LocalDateTime payDeadline) {
        return payDeadline == null || !nowUtc.isBefore(payDeadline);
    }
}