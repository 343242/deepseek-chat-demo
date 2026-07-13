package com.smart.rag.mcp.admin.service;

import com.smart.rag.mcp.admin.entity.McpToolConfig;
import com.smart.rag.mcp.admin.mapper.McpToolConfigMapper;
import org.springframework.stereotype.Component;

/**
 * MCP 工具配置 accessor — direct indexed DB reads (design §R1, Phase E).
 * <p>
 * No cache layer. Reads are indexed on {@code prefixed_tool_name} and return
 * committed rows. No invalidation ordering is required.
 */
@Component
public class McpToolConfigAccessor {

    private final McpToolConfigMapper mapper;

    public McpToolConfigAccessor(McpToolConfigMapper mapper) {
        this.mapper = mapper;
    }

    /** 按 prefixedName 查 tool 配置；DB 未找到返回 null */
    public McpToolConfig get(String prefixedName) {
        return mapper.selectByPrefixedName(prefixedName);
    }
}
