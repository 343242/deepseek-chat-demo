package com.smart.rag.modelconfig.service.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smart.rag.infrastructure.llm.ChatCandidate;
import com.smart.rag.infrastructure.llm.LlmCapability;
import com.smart.rag.infrastructure.llm.config.ByokConfigSource;
import com.smart.rag.infrastructure.llm.metrics.LlmMetrics;
import com.smart.rag.infrastructure.llm.registry.LlmClientFactory;
import com.smart.rag.modelconfig.entity.LlmModelConfig;
import com.smart.rag.modelconfig.service.LlmModelConfigService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 用户级 BYOK 配置源的 DB 实现（{@link ByokConfigSource} 的 modelconfig 模块实现）。
 * <p>
 * 归属于 modelconfig 包：本类持有对 {@link LlmModelConfigService} 与 {@link LlmModelConfig}
 * 的依赖（DB schema 知识本就属 modelconfig），实现 infrastructure 定义的 SPI 接口，
 * 依赖方向为 modelconfig -> infrastructure（合法），避免 infrastructure 反向依赖业务包。
 * <p>
 * 三态语义（design §5.4 / R1）：
 * <ul>
 *   <li><b>无行</b> → 空 List（Registry delegate yml 系统级 snapshot，正常 fallback，无 warn/counter）</li>
 *   <li><b>全 disabled</b> → 空 List + WARN + {@code llm.byok.fallback{reason=all_disabled}} 计数
 *       （用户明确禁用全部，区别于从未配置）</li>
 *   <li><b>有 enabled</b> → ResolvedCandidate 链（BYOK 链）</li>
 * </ul>
 * 返回空 = fallback yml；Registry 不为空链 delegate（避免为无 BYOK 用户缓存等价 yml 的 snapshot）。
 * <p>
 * <b>candidateId 命名空间</b>（design §5.2）：candidate.id 设为 {@code u:{userId}:{modelCode}}，
 * 隔离熔断器/snapshot key，避免污染系统级同名 candidate 或其他用户。
 *
 * @see LlmClientFactory#buildSnapshot(List)
 */
@Component
public class DbByokConfigSource implements ByokConfigSource {

    private static final Logger log = LoggerFactory.getLogger(DbByokConfigSource.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, String>> ENDPOINTS_TYPE = new TypeReference<>() {};

    private final LlmModelConfigService configService;
    @Nullable
    private final LlmMetrics metrics;

    public DbByokConfigSource(LlmModelConfigService configService, @Nullable LlmMetrics metrics) {
        this.configService = configService;
        this.metrics = metrics;
    }

    @Override
    public List<LlmClientFactory.ResolvedCandidate> userChain(Long userId, LlmCapability cap) {
        List<LlmModelConfig> rows = configService.selectAll(userId, cap);
        if (rows.isEmpty()) {
            return List.of(); // 从未配置 → 正常 fallback yml
        }
        List<LlmModelConfig> enabled = rows.stream()
            .filter(r -> Integer.valueOf(1).equals(r.getStatus()))
            .toList();
        if (enabled.isEmpty()) {
            log.warn("user {} all BYOK disabled for {}, fallback yml", userId, cap);
            if (metrics != null) metrics.recordByokFallback("all_disabled");
            return List.of();
        }
        return enabled.stream()
            .map(r -> toResolved(r, userId, cap))
            .toList();
    }

    private LlmClientFactory.ResolvedCandidate toResolved(LlmModelConfig r, Long userId, LlmCapability cap) {
        String apiKey = configService.decryptKey(r);
        Map<String, String> endpoints = parseEndpoints(r.getEndpoints());

        ChatCandidate candidate = new ChatCandidate();
        candidate.setId("u:" + userId + ":" + r.getModelName()); // 命名空间隔离（design §5.2）
        candidate.setProvider(r.getProviderCode());
        candidate.setModel(r.getModelName());
        candidate.setPriority(r.getPriority() != null ? r.getPriority() : 100);
        candidate.setCapability(cap);
        candidate.setSupportsStreaming(Boolean.TRUE.equals(r.getSupportsStreaming()));
        candidate.setSupportsThinking(Boolean.TRUE.equals(r.getSupportsThinking()));

        return new LlmClientFactory.ResolvedCandidate(candidate, r.getProviderCode(),
            r.getBaseUrl(), apiKey, endpoints);
    }

    /** 防御性解析 endpoints JSON（null/blank/"null"/失败 → 空 map，不拖垮整条链） */
    private static Map<String, String> parseEndpoints(String json) {
        if (json == null || json.isBlank() || "null".equals(json)) {
            return Map.of();
        }
        try {
            Map<String, String> parsed = OBJECT_MAPPER.readValue(json, ENDPOINTS_TYPE);
            return parsed != null ? parsed : Map.of();
        } catch (Exception e) {
            log.warn("parse BYOK endpoints failed ({}), using empty: {}", e.getMessage(), json);
            return Map.of();
        }
    }
}
