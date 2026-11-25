package com.hiking.gymsaas.common.exception;

import org.springframework.http.HttpStatus;

public enum ErrorCode {
    VALIDATION_ERROR(HttpStatus.BAD_REQUEST, "请求参数不合法"),
    UNAUTHENTICATED(HttpStatus.UNAUTHORIZED, "请先登录"),
    FORBIDDEN(HttpStatus.FORBIDDEN, "没有该操作权限"),
    NOT_FOUND(HttpStatus.NOT_FOUND, "资源不存在或不可访问"),
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "不支持该请求方式"),
    STATE_CONFLICT(HttpStatus.CONFLICT, "当前状态不允许该操作"),
    IDEMPOTENCY_CONFLICT(HttpStatus.CONFLICT, "同一幂等键的请求内容不一致"),
    ETAG_MISMATCH(HttpStatus.PRECONDITION_FAILED, "配置已变化，请刷新后重试"),
    PRECONDITION_REQUIRED(HttpStatus.PRECONDITION_REQUIRED, "缺少并发更新前置条件"),
    RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS, "请求过于频繁"),
    PHONE_BINDING_UNAVAILABLE(HttpStatus.UNPROCESSABLE_ENTITY, "手机号绑定能力未开通"),
    INVALID_TIME_RANGE(HttpStatus.UNPROCESSABLE_ENTITY, "时间范围不合法"),
    DEPENDENCY_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "依赖服务暂不可用"),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "服务暂不可用");

    private final HttpStatus status;
    private final String message;

    ErrorCode(HttpStatus status, String message) {
        this.status = status;
        this.message = message;
    }

    public HttpStatus status() { return status; }
    public String message() { return message; }
}
