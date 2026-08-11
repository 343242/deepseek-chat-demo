package com.smart.rag.infrastructure.exception;

import com.smart.rag.infrastructure.exception.errorcode.ClientErrorCode;
import com.smart.rag.infrastructure.exception.errorcode.IErrorCode;
import com.smart.rag.infrastructure.exception.errorcode.ServiceErrorCode;
import com.smart.rag.infrastructure.response.GlobalResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * 统一全局异常处理器
 * <p>
 * 所有异常统一返回 HTTP 200 + {@link GlobalResponse}，通过 code 字段区分业务错误。
 * <ul>
 *   <li>{@link ClientException} — 客户端错误 (A类, 100001–199999)</li>
 *   <li>{@link ServiceException} — 服务端错误 (B类, 200001–299999)</li>
 *   <li>{@link RemoteException} — 第三方服务错误 (C类, 300001–399999)</li>
 *   <li>{@link MessagingException} — 消息总线错误 (D类, 400001–499999)</li>
 * </ul>
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    // ==================== 新异常体系 ====================

    @ExceptionHandler(ClientException.class)
    public ResponseEntity<GlobalResponse<Void>> handleClient(ClientException e) {
        IErrorCode errorCode = e.getErrorCode();
        log.warn("Client error: code={}, message={}", errorCode.getCode(), e.getUserMessage());
        return ResponseEntity.ok(GlobalResponse.error(errorCode, e.getUserMessage()));
    }

    @ExceptionHandler(ServiceException.class)
    public ResponseEntity<GlobalResponse<Void>> handleService(ServiceException e) {
        IErrorCode errorCode = e.getErrorCode();
        log.warn("Service error: code={}, message={}", errorCode.getCode(), e.getUserMessage());
        return ResponseEntity.ok(GlobalResponse.error(errorCode, e.getUserMessage()));
    }

    @ExceptionHandler(RemoteException.class)
    public ResponseEntity<GlobalResponse<Void>> handleRemote(RemoteException e) {
        IErrorCode errorCode = e.getErrorCode();
        log.error("Remote service error: code={}, message={}", errorCode.getCode(), e.getUserMessage());
        return ResponseEntity.ok(GlobalResponse.error(errorCode, e.getUserMessage()));
    }

    @ExceptionHandler(MessagingException.class)
    public ResponseEntity<GlobalResponse<Void>> handleMessaging(MessagingException e) {
        IErrorCode errorCode = e.getErrorCode();
        log.error("Messaging error: code={}, message={}", errorCode.getCode(), e.getUserMessage());
        return ResponseEntity.ok(GlobalResponse.error(errorCode, e.getUserMessage()));
    }

    // ==================== 框架异常 ====================

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<GlobalResponse<Void>> handleIllegalArgument(IllegalArgumentException e) {
        log.warn("Illegal argument: {}", e.getMessage());
        return ResponseEntity.ok(GlobalResponse.error(ClientErrorCode.BAD_REQUEST, e.getMessage()));
    }

    @ExceptionHandler(org.springframework.web.bind.MethodArgumentNotValidException.class)
    public ResponseEntity<GlobalResponse<Void>> handleValidation(
            org.springframework.web.bind.MethodArgumentNotValidException e) {
        String msg = e.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                .reduce((a, b) -> a + "; " + b)
                .orElse("参数校验失败");
        return ResponseEntity.ok(GlobalResponse.error(ClientErrorCode.VALIDATION_ERROR, msg));
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<GlobalResponse<Void>> handleAccessDenied(AccessDeniedException e) {
        return ResponseEntity.ok(GlobalResponse.error(ClientErrorCode.FORBIDDEN));
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<GlobalResponse<Void>> handleAuthentication(AuthenticationException e) {
        return ResponseEntity.ok(GlobalResponse.error(ClientErrorCode.UNAUTHORIZED));
    }

    /**
     * @RequestBody 反序列化失败（如畸形 JSON、无法解析的时间串）——客户端错误。
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<GlobalResponse<Void>> handleNotReadable(HttpMessageNotReadableException e) {
        log.warn("Malformed request body: {}", e.getMessage());
        return ResponseEntity.ok(GlobalResponse.error(ClientErrorCode.BAD_REQUEST, "请求体格式错误或字段无法解析"));
    }

    /**
     * @RequestParam 类型转换失败（如全局 Formatter 无法解析的时间查询参数）——客户端错误。
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<GlobalResponse<Void>> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        log.warn("Param type mismatch: name={}, value={}, msg={}", e.getName(), e.getValue(), e.getMessage());
        return ResponseEntity.ok(GlobalResponse.error(ClientErrorCode.BAD_REQUEST,
                "参数 " + e.getName() + " 格式错误或无法解析"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<GlobalResponse<Void>> handleGeneric(Exception e) {
        String traceId = MDC.get("traceId");
        log.error("Unhandled exception (traceId={}): {}", traceId, e.getClass().getName());
        log.error("Unhandled exception details", e);
        return ResponseEntity.ok(GlobalResponse.error(ServiceErrorCode.INTERNAL_ERROR));
    }
}
