package com.demo.chat.common.response;

import com.demo.chat.common.errorcode.ErrorCode;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 统一响应包装
 * <p>
 * 所有非流式接口统一返回此格式：
 * <pre>
 * 成功: {"code":0, "message":"ok", "data":{...}}
 * 失败: {"code":40001, "message":"用户名已存在", "data":null}
 * </pre>
 *
 * @param code    状态码，0=成功，非0=错误码
 * @param message 友好提示
 * @param data    业务数据（成功时有值，失败时为 null）
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record GlobalResponse<T>(
    int code,
    String message,
    T data
) {
    // ==================== 成功 ====================

    public static <T> GlobalResponse<T> ok(T data) {
        return new GlobalResponse<>(0, "ok", data);
    }

    public static <T> GlobalResponse<T> ok(T data, String message) {
        return new GlobalResponse<>(0, message, data);
    }

    public static GlobalResponse<Void> ok() {
        return new GlobalResponse<>(0, "ok", null);
    }

    public static GlobalResponse<Void> ok(String message) {
        return new GlobalResponse<>(0, message, null);
    }

    // ==================== 失败 ====================

    public static <T> GlobalResponse<T> error(ErrorCode errorCode) {
        return new GlobalResponse<>(errorCode.getCode(), errorCode.getMessage(), null);
    }

    /**
     * 覆盖默认错误消息（用于需要动态 detail 的场景）
     */
    public static <T> GlobalResponse<T> error(ErrorCode errorCode, String detail) {
        return new GlobalResponse<>(errorCode.getCode(), detail, null);
    }
}
