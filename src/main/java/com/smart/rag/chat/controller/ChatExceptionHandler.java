package com.smart.rag.chat.controller;

import com.smart.rag.infrastructure.exception.errorcode.ErrorCode;
import com.smart.rag.infrastructure.response.GlobalResponse;
import com.smart.rag.infrastructure.exception.BusinessException;
import com.smart.rag.infrastructure.exception.ContentFilteredException;
import com.smart.rag.infrastructure.exception.ModelNotFoundException;
import com.smart.rag.infrastructure.exception.ProviderNotFoundException;
import com.smart.rag.infrastructure.exception.RateLimitExceededException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 聊天模块全局异常处理
 * <p>
 * 统一将各类异常转换为 {@link GlobalResponse} 格式，避免异常堆栈泄漏到客户端。
 * <ul>
 *   <li>{@link BusinessException} — 返回 ErrorCode 对应的错误码和消息</li>
 *   <li>{@link ContentFilteredException} — 返回 40004 内容过滤错误</li>
 *   <li>{@link RateLimitExceededException} — 返回 429 限流错误</li>
 *   <li>{@link ModelNotFoundException} — 返回 40002 模型不存在</li>
 *   <li>{@link ProviderNotFoundException} — 返回 40003 厂商未配置</li>
 *   <li>通用 {@link Exception} — 记录日志，返回脱敏的 500 错误</li>
 * </ul>
 * <p>
 * 注意：SSE 流式端点（{@code text/event-stream}）产生的异常无法被此 Handler 捕获，
 * 需在流式处理链的 doOnError / doFinally 中通过 SseEmitter 手动发送错误事件。
 */
@RestControllerAdvice(basePackages = "com.smart.rag.chat.controller")
public class ChatExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ChatExceptionHandler.class);

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<GlobalResponse<Void>> handleBusinessException(BusinessException e) {
        log.warn("BusinessException: code={}, message={}", e.getErrorCode().getCode(), e.getUserMessage());
        GlobalResponse<Void> response = GlobalResponse.error(e.getErrorCode(), e.getUserMessage());
        return ResponseEntity.status(mapHttpStatus(e.getErrorCode())).body(response);
    }

    @ExceptionHandler(ContentFilteredException.class)
    public ResponseEntity<GlobalResponse<Void>> handleContentFilteredException(ContentFilteredException e) {
        log.warn("Content filtered: {}", e.getMessage());
        return ResponseEntity.ok(GlobalResponse.error(ErrorCode.CONTENT_FILTERED, e.getMessage()));
    }

    @ExceptionHandler(RateLimitExceededException.class)
    public ResponseEntity<GlobalResponse<Void>> handleRateLimitExceededException(RateLimitExceededException e) {
        log.warn("Rate limit exceeded: {}", e.getMessage());
        GlobalResponse<Void> response = GlobalResponse.error(ErrorCode.RATE_LIMITED, e.getMessage());
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(response);
    }

    @ExceptionHandler(ModelNotFoundException.class)
    public ResponseEntity<GlobalResponse<Void>> handleModelNotFoundException(ModelNotFoundException e) {
        log.warn("Model not found: modelId={}, message={}", e.getModelId(), e.getMessage());
        return ResponseEntity.ok(GlobalResponse.error(ErrorCode.MODEL_NOT_FOUND, e.getMessage()));
    }

    @ExceptionHandler(ProviderNotFoundException.class)
    public ResponseEntity<GlobalResponse<Void>> handleProviderNotFoundException(ProviderNotFoundException e) {
        log.warn("Provider not found: providerId={}, message={}", e.getProviderId(), e.getMessage());
        return ResponseEntity.ok(GlobalResponse.error(ErrorCode.PROVIDER_NOT_FOUND, e.getMessage()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<GlobalResponse<Void>> handleGenericException(Exception e) {
        String traceId = MDC.get("traceId");
        log.error("Unhandled exception (traceId={}): {}", traceId, e.getMessage(), e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(GlobalResponse.error(ErrorCode.INTERNAL_ERROR));
    }

    /**
     * 将 ErrorCode 映射为 HTTP 状态码
     * <p>
     * 大部分业务错误返回 200 + 非零 code（前端通过 code 字段判断），
     * 仅认证/权限相关错误返回对应 HTTP 状态码。
     */
    private HttpStatus mapHttpStatus(ErrorCode errorCode) {
        return switch (errorCode) {
            case UNAUTHORIZED -> HttpStatus.UNAUTHORIZED;
            case FORBIDDEN -> HttpStatus.FORBIDDEN;
            case RATE_LIMITED -> HttpStatus.TOO_MANY_REQUESTS;
            default -> HttpStatus.OK;
        };
    }
}
