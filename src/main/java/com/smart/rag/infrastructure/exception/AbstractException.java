package com.smart.rag.infrastructure.exception;

import com.smart.rag.infrastructure.exception.errorcode.IErrorCode;

/**
 * 异常抽象基类
 * <p>
 * 统一携带 {@link IErrorCode}，所有面向用户的全局异常必须继承此类。
 * <ul>
 *   <li>{@link ClientException} — 客户端错误 (A类)</li>
 *   <li>{@link ServiceException} — 服务端错误 (B类)</li>
 *   <li>{@link RemoteException} — 第三方服务错误 (C类)</li>
 * </ul>
 */
public abstract class AbstractException extends RuntimeException {

    private final IErrorCode errorCode;
    private final String detail;

    protected AbstractException(IErrorCode errorCode) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
        this.detail = null;
    }

    protected AbstractException(IErrorCode errorCode, String detail) {
        super(detail != null ? detail : errorCode.getMessage());
        this.errorCode = errorCode;
        this.detail = detail;
    }

    protected AbstractException(IErrorCode errorCode, String detail, Throwable cause) {
        super(detail != null ? detail : errorCode.getMessage(), cause);
        this.errorCode = errorCode;
        this.detail = detail;
    }

    public IErrorCode getErrorCode() {
        return errorCode;
    }

    /**
     * 用户可见消息。优先 detail（动态覆盖），否则使用 ErrorCode 默认消息。
     */
    public String getUserMessage() {
        return detail != null ? detail : errorCode.getMessage();
    }
}
