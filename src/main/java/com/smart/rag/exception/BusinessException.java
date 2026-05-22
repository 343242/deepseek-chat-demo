package com.smart.rag.exception;

import com.smart.rag.common.errorcode.ErrorCode;

/**
 * 业务异常
 * <p>
 * 支持两种构造方式：
 * <ul>
 *   <li>{@code new BusinessException(ErrorCode.XXX)} — 使用 ErrorCode 预定义消息</li>
 *   <li>{@code new BusinessException(ErrorCode.XXX, "动态详情")} — 覆盖默认消息</li>
 *   <li>{@code new BusinessException("消息")} — 兼容旧代码，自动映射到 BAD_REQUEST</li>
 * </ul>
 */
public class BusinessException extends RuntimeException {

    private final ErrorCode errorCode;
    private final String detail;

    public BusinessException(ErrorCode errorCode) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
        this.detail = null;
    }

    public BusinessException(ErrorCode errorCode, String detail) {
        super(detail != null ? detail : errorCode.getMessage());
        this.errorCode = errorCode;
        this.detail = detail;
    }

    /**
     * 兼容旧代码：纯 message 构造，映射到 BAD_REQUEST
     */
    public BusinessException(String message) {
        super(message);
        this.errorCode = ErrorCode.BAD_REQUEST;
        this.detail = message;
    }

    public BusinessException(String message, Throwable cause) {
        super(message, cause);
        this.errorCode = ErrorCode.BAD_REQUEST;
        this.detail = message;
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }

    /**
     * 获取用户可见的消息。
     * 优先使用 detail（动态覆盖），否则使用 ErrorCode 默认消息。
     */
    public String getUserMessage() {
        return detail != null ? detail : errorCode.getMessage();
    }
}
