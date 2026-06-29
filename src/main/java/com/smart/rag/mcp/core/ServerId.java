package com.smart.rag.mcp.core;

import com.smart.rag.infrastructure.exception.ClientException;
import com.smart.rag.infrastructure.exception.errorcode.ClientErrorCode;

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
        // null → requireNonNull（§4 明文允许的前置校验，与 McpTool/McpArgs 一致）；
        // blank → ClientException（§7：禁止 IllegalArgumentException，统一异常体系）
        Objects.requireNonNull(value, "value");
        if (value.isBlank()) {
            throw new ClientException(ClientErrorCode.BAD_REQUEST, "ServerId value 不能为空");
        }
    }

    @Override
    public String toString() {
        return value;
    }
}
