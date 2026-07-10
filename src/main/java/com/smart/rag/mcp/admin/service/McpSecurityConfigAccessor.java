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
 * MCP 安全配置 accessor——独立只读 Bean，供安全热路径复用并避免业务 facade 反向依赖。
 * <p>
 * <b>双重缓存</b>：
 * <ul>
 *   <li>{@code viewCache}：反序列化视图（10min TTL，key=singleton）</li>
 *   <li>{@code patternsCache}：编译产物（volatile + DCL），admin 更新触发 {@link #invalidate()} 重置</li>
 * </ul>
 * chat 热路径经 {@code McpSecurityGuard} 调 {@link #patterns()} 命中缓存 O(1)，
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
    private final McpSecurityConfigValidator validator;

    private final Cache<String, McpSecurityConfigView> viewCache = Caffeine.newBuilder()
            .expireAfterWrite(TTL).maximumSize(1).build();

    private volatile List<Pattern> patternsCache;

    public McpSecurityConfigAccessor(McpSecurityConfigMapper mapper,
                                     ObjectMapper objectMapper,
                                     McpSecurityConfigValidator validator) {
        this.mapper = mapper;
        this.objectMapper = objectMapper;
        this.validator = validator;
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
        synchronized (this) {
            viewCache.invalidate("singleton");
            patternsCache = null;
        }
    }

    private McpSecurityConfigView loadFromDb() {
        try {
            McpSecurityConfig row = mapper.selectSingleton();
            if (row == null || row.getConfigJson() == null) {
                return McpSecurityConfigView.defaults();
            }
            McpSecurityConfigView view = objectMapper.readValue(row.getConfigJson(), McpSecurityConfigView.class);
            return validator.validate(view);
        } catch (Exception e) {
            log.warn("MCP 安全配置无效，已回退默认值，errorType={}", e.getClass().getSimpleName());
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
