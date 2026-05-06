package com.demo.deepseekchat.exception;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/**
 * 全局异常处理器
 * <p>
 * 将业务异常转为友好的 HTTP 响应。
 * 依赖独立异常类，不依赖任何 Advisor 或具体实现类。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(RateLimitExceededException.class)
    public ResponseEntity<Map<String, Object>> handleRateLimit(RateLimitExceededException e) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .body(Map.of(
                        "error", "rate_limit_exceeded",
                        "message", e.getMessage(),
                        "status", 429
                ));
    }

    @ExceptionHandler(ContentFilteredException.class)
    public ResponseEntity<Map<String, Object>> handleContentFilter(ContentFilteredException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of(
                        "error", "content_filtered",
                        "message", e.getMessage(),
                        "status", 400
                ));
    }

    @ExceptionHandler(ModelNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleModelNotFound(ModelNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Map.of(
                        "error", "model_not_found",
                        "message", e.getMessage(),
                        "modelId", e.getModelId(),
                        "status", 404
                ));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalArgument(IllegalArgumentException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of(
                        "error", "bad_request",
                        "message", e.getMessage(),
                        "status", 400
                ));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleGeneric(Exception e) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of(
                        "error", "internal_error",
                        "message", "服务内部错误，请稍后重试",
                        "status", 500
                ));
    }
}
