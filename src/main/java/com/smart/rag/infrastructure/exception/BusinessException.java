package com.smart.rag.infrastructure.exception;

import com.smart.rag.infrastructure.exception.errorcode.ErrorCode;
import com.smart.rag.infrastructure.exception.errorcode.IErrorCode;

/**
 * 业务异常（旧版，逐步迁移中）
 *
 * @deprecated 使用 {@link ClientException}、{@link ServiceException} 或 {@link RemoteException} 替代。
 *             保留此类仅为过渡期兼容，预计在下个大版本移除。
 */
@Deprecated
public class BusinessException extends AbstractException {

    private final ErrorCode legacyErrorCode;

    public BusinessException(ErrorCode errorCode) {
        super(errorCode);
        this.legacyErrorCode = errorCode;
    }

    public BusinessException(ErrorCode errorCode, String detail) {
        super(errorCode, detail);
        this.legacyErrorCode = errorCode;
    }

    public BusinessException(ErrorCode errorCode, String detail, Throwable cause) {
        super(errorCode, detail, cause);
        this.legacyErrorCode = errorCode;
    }

    /**
     * 兼容旧代码：纯 message 构造，映射到 BAD_REQUEST
     */
    public BusinessException(String message) {
        super(ErrorCode.BAD_REQUEST, message);
        this.legacyErrorCode = ErrorCode.BAD_REQUEST;
    }

    public BusinessException(String message, Throwable cause) {
        super(ErrorCode.BAD_REQUEST, message, cause);
        this.legacyErrorCode = ErrorCode.BAD_REQUEST;
    }

    /**
     * 返回原始 ErrorCode 枚举（兼容旧调用方）。
     * 新代码应使用 {@link #getErrorCode()} 获取 {@link IErrorCode}。
     */
    public ErrorCode getLegacyErrorCode() {
        return legacyErrorCode;
    }
}
