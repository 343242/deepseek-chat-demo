package com.smart.rag.infrastructure.exception;

import com.smart.rag.infrastructure.exception.errorcode.IErrorCode;
import com.smart.rag.infrastructure.exception.errorcode.RemoteErrorCode;

/**
 * 第三方服务异常 (C类)
 * <p>
 * 调用外部模型、向量数据库、文件存储等第三方服务失败。
 * 错误码范围: 300001–399999
 */
public class RemoteException extends AbstractException {

    public RemoteException(RemoteErrorCode errorCode) {
        super(errorCode);
    }

    public RemoteException(IErrorCode errorCode, String detail) {
        super(errorCode, detail);
    }

    public RemoteException(IErrorCode errorCode, String detail, Throwable cause) {
        super(errorCode, detail, cause);
    }
}
