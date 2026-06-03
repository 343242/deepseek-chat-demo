package com.smart.rag.infrastructure.exception.errorcode;

/**
 * 第三方服务错误码 (C类, 300001–399999)
 * <p>
 * 调用外部模型、向量数据库、文件存储等第三方服务失败。
 */
public enum RemoteErrorCode implements IErrorCode {

    PROVIDER_NOT_FOUND(300001, "厂商未配置"),
    MODEL_TIMEOUT(300002, "模型调用超时"),
    VECTOR_DB_UNAVAILABLE(300003, "向量数据库不可用"),
    ;

    private final int code;
    private final String message;

    RemoteErrorCode(int code, String message) {
        this.code = code;
        this.message = message;
    }

    @Override
    public int getCode() { return code; }

    @Override
    public String getMessage() { return message; }
}
