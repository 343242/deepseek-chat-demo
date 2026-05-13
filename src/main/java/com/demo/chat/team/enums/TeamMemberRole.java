package com.demo.chat.team.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 团队成员角色枚举
 * <p>
 * CREATOR(30) > ADMIN(20) > MEMBER(10)
 * 数值越大权限越高，便于比较。
 * DB 存 int（@EnumValue），API 返回字符串（@JsonValue → name()）
 */
public enum TeamMemberRole {

    MEMBER(10),
    ADMIN(20),
    CREATOR(30);

    @EnumValue
    private final int code;

    TeamMemberRole(int code) {
        this.code = code;
    }

    public int getCode() {
        return code;
    }

    @JsonValue
    public String getName() {
        return name();
    }
}
