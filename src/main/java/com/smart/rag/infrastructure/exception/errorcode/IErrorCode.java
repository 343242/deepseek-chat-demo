package com.smart.rag.infrastructure.exception.errorcode;

/**
 * 错误码接口
 * <p>
 * 所有错误码枚举实现此接口，支持跨枚举类型的统一引用。
 */
public interface IErrorCode {

    int getCode();

    String getMessage();
}
