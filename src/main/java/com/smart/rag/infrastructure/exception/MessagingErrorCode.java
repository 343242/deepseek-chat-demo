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
    INVALID_TOPIC(400006, "非法Topic名称"),
    INVALID_TAG(400007, "非法标签名称"),
    INVALID_GROUP(400008, "非法消费者组名称"),
    MESSAGE_TOO_LARGE(400009, "消息体超限"),
    INVALID_CONFIG(400010, "消费配置无效"),
    UNSUPPORTED_OPERATION(400011, "不支持的操作"),
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
