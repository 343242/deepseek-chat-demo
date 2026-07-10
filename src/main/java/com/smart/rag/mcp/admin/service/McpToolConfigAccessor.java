package com.smart.rag.mcp.admin.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.smart.rag.mcp.admin.entity.McpToolConfig;
import com.smart.rag.mcp.admin.mapper.McpToolConfigMapper;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;

/**
 * MCP 工具配置 accessor（独立 Bean，避免循环依赖）。
 * <p>
 * 缓存 per-prefixedName 的 {@link McpToolConfig}（含 risk / intent / descriptionOverride），
 * 供 {@code McpSecurityGuard}、{@code McpDescriptionSanitizer} 和授权过滤读取。
 * ADMIN 改 tool config 时调 {@link #invalidate(String)} 清缓存。
 * Tool Admin owns the per-server list cache; this accessor owns the per-name policy cache.
 */
@Component
public class McpToolConfigAccessor {

    private static final Duration TTL = Duration.ofMinutes(10);

    private final McpToolConfigMapper mapper;
    private final Cache<String, Optional<McpToolConfig>> cache = Caffeine.newBuilder()
            .expireAfterWrite(TTL).maximumSize(10_000).build();

    public McpToolConfigAccessor(McpToolConfigMapper mapper) {
        this.mapper = mapper;
    }

    /** 按 prefixedName 查 tool 配置；DB 未找到返回 null */
    public McpToolConfig get(String prefixedName) {
        Optional<McpToolConfig> cached = cache.get(prefixedName,
                key -> Optional.ofNullable(mapper.selectByPrefixedName(key)));
        return cached.orElse(null);
    }

    /** ADMIN 改 tool config 后调；如不知具体 name 可调 {@link #invalidateAll()} */
    public void invalidate(String prefixedName) {
        cache.invalidate(prefixedName);
    }

    public void invalidateAll() {
        cache.invalidateAll();
    }
}
