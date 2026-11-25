package com.hiking.gymsaas.common.web;

import com.hiking.gymsaas.common.exception.BusinessException;
import com.hiking.gymsaas.common.exception.ErrorCode;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;

@RestControllerAdvice
public class GlobalExceptionHandler {
    private static final Logger LOG = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BusinessException.class)
    ResponseEntity<ApiError> business(BusinessException ex) {
        return error(ex.code());
    }

    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<ApiError> forbidden(AccessDeniedException ex) { return error(ErrorCode.FORBIDDEN); }

    @ExceptionHandler(DuplicateKeyException.class)
    ResponseEntity<ApiError> duplicate(DuplicateKeyException ex) { return error(ErrorCode.STATE_CONFLICT); }

    @ExceptionHandler(DataAccessException.class)
    ResponseEntity<ApiError> database(DataAccessException ex) { return error(ErrorCode.DEPENDENCY_UNAVAILABLE); }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiError> fields(MethodArgumentNotValidException ex) {
        var errors = ex.getBindingResult().getFieldErrors().stream()
                .map(e -> new ApiError.FieldError(e.getField(), e.getDefaultMessage())).toList();
        return ResponseEntity.badRequest().body(ApiError.of(ErrorCode.VALIDATION_ERROR, errors));
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, ConstraintViolationException.class,
            MethodArgumentTypeMismatchException.class, HandlerMethodValidationException.class, IllegalArgumentException.class})
    ResponseEntity<ApiError> validation(Exception ex) {
        return error(ErrorCode.VALIDATION_ERROR);
    }

    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<ApiError> notFound(NoResourceFoundException ex) {
        return error(ErrorCode.NOT_FOUND);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ResponseEntity<ApiError> method(HttpRequestMethodNotSupportedException ex) {
        return error(ErrorCode.METHOD_NOT_ALLOWED);
    }

    @ExceptionHandler(org.springframework.web.multipart.MaxUploadSizeExceededException.class)
    ResponseEntity<ApiError> tooLarge(org.springframework.web.multipart.MaxUploadSizeExceededException ex) {
        return error(ErrorCode.FILE_TOO_LARGE);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiError> unexpected(Exception ex) {
        // Exception messages may contain SQL parameters or credentials; never echo or log them here.
        LOG.error("Unhandled request exception type={}, requestId={}",
                ex.getClass().getName(), RequestIds.current());
        return error(ErrorCode.INTERNAL_ERROR);
    }

    private ResponseEntity<ApiError> error(ErrorCode code) {
        return ResponseEntity.status(code.status()).body(ApiError.of(code));
    }
}
