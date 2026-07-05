package com.smart.rag.mcp.admin.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.smart.rag.mcp.admin.entity.McpSecurityConfig;
import com.smart.rag.mcp.admin.entity.McpSecurityConfigView;
import com.smart.rag.mcp.admin.mapper.McpSecurityConfigMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.regex.Pattern;

/**
 * MCP 安全配置 accessor——独立 Bean，无业务依赖，避免 McpAdminService 注入到 McpSecurityGuard/Sanitizer 形成循环。
 * <p>
 * <b>双重缓存</b>（v4 B5）：
 * <ul>
 *   <li>{@code viewCache}：反序列化视图（10min TTL，key=singleton）</li>
 *   <li>{@code patternsCache}：编译产物（volatile + DCL），admin 更新触发 {@link #invalidate()} 重置</li>
 * </ul>
 * chat 热路径（{@code McpSecurityGuard.guard()}）调 {@link #patterns()} 命中缓存 O(1)，
 * 不会每次调 {@code Pattern.compile}。
 * <p>
 * <b>失败回退</b>：DB 为空 / 反序列化失败 → {@link McpSecurityConfigView#defaults()}。
 */
@Component
public class McpSecurityConfigAccessor {

    private static final Logger log = LoggerFactory.getLogger(McpSecurityConfigAccessor.class);
    private static final Duration TTL = Duration.ofMinutes(10);

    private final McpSecurityConfigMapper mapper;
    private final ObjectMapper objectMapper;

    private final Cache<String, McpSecurityConfigView> viewCache = Caffeine.newBuilder()
            .expireAfterWrite(TTL).maximumSize(1).build();

    private volatile List<Pattern> patternsCache;

    public McpSecurityConfigAccessor(McpSecurityConfigMapper mapper, ObjectMapper objectMapper) {
        this.mapper = mapper;
        this.objectMapper = objectMapper;
    }

    /** 反序列化视图（10min TTL，DB 空/失败回退 defaults） */
    public McpSecurityConfigView get() {
        return viewCache.get("singleton", k -> loadFromDb());
    }

    /**
     * 已编译的敏感参数正则列表（DCL + 视图缓存）。
     * <p>
     * 命中缓存 O(1)；miss 时 {@code Pattern.compile} 所有 {@code get().sensitiveArgPatterns()}。
     * admin 更新触发 {@link #invalidate()} 后下次调用重新编译。
     */
    public List<Pattern> patterns() {
        List<Pattern> cached = patternsCache;
        if (cached != null) {
            return cached;
        }
        synchronized (this) {
            if (patternsCache == null) {
                patternsCache = compile(get().sensitiveArgPatterns());
            }
            return patternsCache;
        }
    }

    /** admin 更新后调，清两层缓存 */
    public void invalidate() {
        viewCache.invalidate("singleton");
        patternsCache = null;
    }

    private McpSecurityConfigView loadFromDb() {
        try {
            McpSecurityConfig row = mapper.selectSingleton();
            if (row == null || row.getConfigJson() == null) {
                return McpSecurityConfigView.defaults();
            }
            return objectMapper.readValue(row.getConfigJson(), McpSecurityConfigView.class);
        } catch (Exception e) {
            log.warn("McpSecurityConfig load failed, fallback to defaults: {}", e.getMessage());
            return McpSecurityConfigView.defaults();
        }
    }

    private static List<Pattern> compile(List<String> patterns) {
        if (patterns == null || patterns.isEmpty()) {
            return List.of();
        }
        return patterns.stream().map(Pattern::compile).toList();
    }
}
