package com.smart.rag.infrastructure.exception;

import com.smart.rag.infrastructure.exception.errorcode.IErrorCode;

/**
 * Messaging bus error codes (D class, 400001-499999).
 */
public enum MessagingErrorCode implements IErrorCode {
    PUBLISH_FAILED(400001, "消息发送失败"),
    CONSUME_FAILED(400002, "消息消费失败"),
    PERMANENT_CONSUME_ERROR(400003, "永久性消费错误"),
    SUBSCRIPTION_ERROR(400004, "订阅异常"),
    CIRCUIT_BREAKER_OPEN(400005, "熔断器开启，拒绝发送"),
    ;

    private final int code;
    private final String message;

    MessagingErrorCode(int code, String message) {
        this.code = code;
        this.message = message;
    }

    @Override
    public int getCode() {
        return code;
    }

    @Override
    public String getMessage() {
        return message;
    }
}
