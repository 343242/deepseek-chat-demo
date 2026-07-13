package com.smart.rag.mcp.admin.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smart.rag.mcp.admin.entity.McpSecurityConfig;
import com.smart.rag.mcp.admin.entity.McpSecurityConfigView;
import com.smart.rag.mcp.admin.mapper.McpSecurityConfigMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.regex.Pattern;

/**
 * MCP 安全配置 accessor — direct DB reads, no cache.
 * <p>
 * The security config is a singleton row. Direct reads are indexed and cheap.
 * Compiled patterns use DCL for hot-path performance.
 * <p>
 * Fallback: DB empty / deserialize failure → {@link McpSecurityConfigView#defaults()}.
 */
@Component
public class McpSecurityConfigAccessor {

    private static final Logger log = LoggerFactory.getLogger(McpSecurityConfigAccessor.class);

    private final McpSecurityConfigMapper mapper;
    private final ObjectMapper objectMapper;
    private final McpSecurityConfigValidator validator;

    private volatile List<Pattern> patternsCache;

    public McpSecurityConfigAccessor(McpSecurityConfigMapper mapper,
                                     ObjectMapper objectMapper,
                                     McpSecurityConfigValidator validator) {
        this.mapper = mapper;
        this.objectMapper = objectMapper;
        this.validator = validator;
    }

    /** Direct DB read of singleton security config (defaults on failure). */
    public McpSecurityConfigView get() {
        return loadFromDb();
    }

    /**
     * Compiled sensitive-argument regex patterns (DCL).
     * Hot-path O(1) hit; miss recompiles from current DB config.
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

    /** Admin update → clear compiled patterns (DB is always fresh on next get()). */
    public void invalidate() {
        synchronized (this) {
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
