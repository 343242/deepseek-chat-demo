package com.smart.rag.mcp.core;

import java.util.Objects;

/**
 * MCP server 命名空间标识（领域值对象）。
 * <p>
 * 由远端 server 握手自报的 {@code serverInfo.name()} 充当（即 {@code McpServer.id()}），
 * 同时与工具前缀（{@code <id>_<tool>}，见 §9）及熔断器 per-key 同源——三者共用同一字符串，
 * 使按前缀过滤工具、按 id 路由调用、按 id 计熔断彼此一致。
 * <p>
 * 同名即视为配置冲突（registry 显式抛错，不静默合并，见 design R-10）。
 */
public record ServerId(String value) {

    public ServerId {
        Objects.requireNonNull(value, "value");
        if (value.isBlank()) {
            throw new IllegalArgumentException("ServerId value must not be blank");
        }
    }

    @Override
    public String toString() {
        return value;
    }
}
