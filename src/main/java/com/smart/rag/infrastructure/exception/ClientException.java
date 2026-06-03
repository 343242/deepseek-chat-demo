package com.smart.rag.infrastructure.exception;

import com.smart.rag.infrastructure.exception.errorcode.ClientErrorCode;
import com.smart.rag.infrastructure.exception.errorcode.IErrorCode;

/**
 * 客户端异常 (A类)
 * <p>
 * 用户提交参数错误、权限不足、重复提交、内容过滤等客户端引起的错误。
 * 错误码范围: 100001–199999
 */
public class ClientException extends AbstractException {

    public ClientException(ClientErrorCode errorCode) {
        super(errorCode);
    }

    public ClientException(IErrorCode errorCode, String detail) {
        super(errorCode, detail);
    }

    public ClientException(IErrorCode errorCode, String detail, Throwable cause) {
        super(errorCode, detail, cause);
    }
}
