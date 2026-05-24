package com.smart.rag.exception;

import com.smart.rag.common.errorcode.ErrorCode;
import com.smart.rag.common.response.GlobalResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 全局异常处理器
 * <p>
 * 所有异常统一转为 {@link GlobalResponse} 格式，携带结构化错误码。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<GlobalResponse<Void>> handleBusiness(BusinessException e) {
        ErrorCode errorCode = e.getErrorCode();
        log.warn("Business error: code={}, message={}", errorCode.getCode(), e.getUserMessage());
        HttpStatus httpStatus = mapToHttpStatus(errorCode);
        return ResponseEntity.status(httpStatus)
                .body(GlobalResponse.error(errorCode, e.getUserMessage()));
    }

    @ExceptionHandler(RateLimitExceededException.class)
    public ResponseEntity<GlobalResponse<Void>> handleRateLimit(RateLimitExceededException e) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .body(GlobalResponse.error(ErrorCode.RATE_LIMITED, e.getMessage()));
    }

    @ExceptionHandler(ContentFilteredException.class)
    public ResponseEntity<GlobalResponse<Void>> handleContentFilter(ContentFilteredException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(GlobalResponse.error(ErrorCode.CONTENT_FILTERED, e.getMessage()));
    }

    @ExceptionHandler(ModelNotFoundException.class)
    public ResponseEntity<GlobalResponse<Void>> handleModelNotFound(ModelNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(GlobalResponse.error(ErrorCode.MODEL_NOT_FOUND, e.getMessage()));
    }

    @ExceptionHandler(ProviderNotFoundException.class)
    public ResponseEntity<GlobalResponse<Void>> handleProviderNotFound(ProviderNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(GlobalResponse.error(ErrorCode.PROVIDER_NOT_FOUND, e.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<GlobalResponse<Void>> handleIllegalArgument(IllegalArgumentException e) {
        log.warn("Illegal argument: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(GlobalResponse.error(ErrorCode.BAD_REQUEST));
    }

    @ExceptionHandler(org.springframework.web.bind.MethodArgumentNotValidException.class)
    public ResponseEntity<GlobalResponse<Void>> handleValidation(
            org.springframework.web.bind.MethodArgumentNotValidException e) {
        String msg = e.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                .reduce((a, b) -> a + "; " + b)
                .orElse("参数校验失败");
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(GlobalResponse.error(ErrorCode.VALIDATION_ERROR, msg));
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<GlobalResponse<Void>> handleAccessDenied(AccessDeniedException e) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(GlobalResponse.error(ErrorCode.FORBIDDEN));
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<GlobalResponse<Void>> handleAuthentication(AuthenticationException e) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(GlobalResponse.error(ErrorCode.UNAUTHORIZED));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<GlobalResponse<Void>> handleGeneric(Exception e) {
        log.error("Unhandled exception: {}", e.getClass().getName());
        log.debug("Unhandled exception details", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(GlobalResponse.error(ErrorCode.INTERNAL_ERROR));
    }

    // ==================== 内部方法 ====================

    /**
     * 将 ErrorCode 映射为 HTTP 状态码
     */
    private HttpStatus mapToHttpStatus(ErrorCode errorCode) {
        int code = errorCode.getCode();
        if (code == 0) return HttpStatus.OK;
        // 认证类 10xxx → 401
        if (code >= 10000 && code < 20000) return HttpStatus.UNAUTHORIZED;
        // 默认按千位映射
        int httpCode = (code / 100) * 100;
        // 常见映射
        return switch (httpCode) {
            case 40100 -> HttpStatus.UNAUTHORIZED;
            case 40300 -> HttpStatus.FORBIDDEN;
            case 40400 -> HttpStatus.NOT_FOUND;
            case 42900 -> HttpStatus.TOO_MANY_REQUESTS;
            default -> HttpStatus.BAD_REQUEST; // 40000, 20000~50000 等
        };
    }
}
