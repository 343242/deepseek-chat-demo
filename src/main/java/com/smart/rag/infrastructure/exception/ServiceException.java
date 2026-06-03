package com.smart.rag.infrastructure.exception;

import com.smart.rag.infrastructure.exception.errorcode.IErrorCode;
import com.smart.rag.infrastructure.exception.errorcode.ServiceErrorCode;

/**
 * 服务端异常 (B类)
 * <p>
 * 业务逻辑不符合预期、数据不存在、状态异常等服务端内部错误。
 * 错误码范围: 200001–299999
 */
public class ServiceException extends AbstractException {

    public ServiceException(ServiceErrorCode errorCode) {
        super(errorCode);
    }

    public ServiceException(IErrorCode errorCode, String detail) {
        super(errorCode, detail);
    }

    public ServiceException(IErrorCode errorCode, String detail, Throwable cause) {
        super(errorCode, detail, cause);
    }
}
