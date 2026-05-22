package com.smart.rag.user.enums;

/**
 * 用户状态枚举
 */
public enum UserStatus {
    DISABLED(0, "禁用"),
    ENABLED(1, "启用");

    public final int code;
    public final String desc;

    UserStatus(int code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    public static UserStatus fromCode(int code) {
        for (UserStatus s : values()) {
            if (s.code == code) return s;
        }
        throw new IllegalArgumentException("无效的用户状态: " + code);
    }

    public static boolean isValid(int code) {
        return code == 0 || code == 1;
    }
}
