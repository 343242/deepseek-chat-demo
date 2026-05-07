package com.demo.deepseekchat.exception;

/**
 * 业务异常基类
 * <p>
 * 用于已知业务规则校验失败的场景，消息可安全返回给前端。
 * 与 IllegalArgumentException（可能包含系统内部信息）区分。
 */
public class BusinessException extends RuntimeException {

    public BusinessException(String message) {
        super(message);
    }

    public BusinessException(String message, Throwable cause) {
        super(message, cause);
    }
}
