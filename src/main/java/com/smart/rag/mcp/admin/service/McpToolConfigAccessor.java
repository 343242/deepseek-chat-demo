package com.smart.rag.mcp.admin.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.smart.rag.mcp.admin.entity.McpToolConfig;
import com.smart.rag.mcp.admin.mapper.McpToolConfigMapper;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * MCP 工具配置 accessor（独立 Bean，避免循环依赖）。
 * <p>
 * 缓存 per-prefixedName 的 {@link McpToolConfig}（含 risk / intent / descriptionOverride），
 * 供 {@code McpSecurityGuard.risk(name)} / {@code McpDescriptionSanitizer.descriptionOverride(name)} 读取。
 * ADMIN 改 tool config 时调 {@link #invalidate(String)} 清缓存。
 * <p>
 * <b>与 McpAdminService.toolListCache 区别</b>：本 accessor 按 prefixedName 索引（per-tool），
 * toolListCache 按 serverId 索引（per-server 列表）；短期双缓存可接受，后续可合并。
 */
@Component
public class McpToolConfigAccessor {

    private static final Duration TTL = Duration.ofMinutes(10);

    private final McpToolConfigMapper mapper;
    private final Cache<String, McpToolConfig> cache = Caffeine.newBuilder()
            .expireAfterWrite(TTL).maximumSize(10_000).build();

    public McpToolConfigAccessor(McpToolConfigMapper mapper) {
        this.mapper = mapper;
    }

    /** 按 prefixedName 查 tool 配置；DB 未找到返回 null */
    public McpToolConfig get(String prefixedName) {
        return cache.get(prefixedName, k -> mapper.selectByPrefixedName(k));
    }

    /** ADMIN 改 tool config 后调；如不知具体 name 可调 {@link #invalidateAll()} */
    public void invalidate(String prefixedName) {
        cache.invalidate(prefixedName);
    }

    public void invalidateAll() {
        cache.invalidateAll();
    }
}
