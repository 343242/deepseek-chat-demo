package com.smart.rag.chat.service.impl;

import com.smart.rag.chat.mapper.SystemPromptMapper;
import com.smart.rag.chat.dto.SystemPromptDTO;
import com.smart.rag.chat.entity.SystemPrompt;
import com.smart.rag.chat.context.CagProperties;
import com.smart.rag.chat.service.PromptLoaderService;
import com.smart.rag.chat.service.SystemPromptService;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * System Prompt 管理服务
 * <p>
 * 优先级：XML 模板文件（内存） → Caffeine 本地缓存 → Redis → PostgreSQL → null
 * <p>
 * Caffeine 作为热路径缓存（TTL 5 分钟），避免每次请求都查 Redis/PG。
 * XML 模板在启动时由 {@link PromptLoaderService} 加载，始终最高优先级。
 */
@Service
public class SystemPromptServiceImpl implements SystemPromptService {

    private static final Logger log = LoggerFactory.getLogger(SystemPromptServiceImpl.class);

    private final SystemPromptMapper mapper;
    private final TransactionTemplate transactionTemplate;
    private final PromptLoaderService promptLoaderService;
    private final Cache<String, String> promptCache;

    public SystemPromptServiceImpl(SystemPromptMapper mapper,
                                   TransactionTemplate transactionTemplate,
                                   PromptLoaderService promptLoaderService,
                                   CagProperties cagProperties) {
        this.mapper = mapper;
        this.transactionTemplate = transactionTemplate;
        this.promptLoaderService = promptLoaderService;
        this.promptCache = Caffeine.newBuilder()
                .expireAfterWrite(Duration.ofMinutes(cagProperties.getSystemPromptCacheTtlMinutes()))
                .maximumSize(cagProperties.getSystemPromptCacheMaxSize())
                .build();
    }

    @Override
    public String getPrompt(String modelId) {
        String xmlPrompt = promptLoaderService.getPrompt(modelId);
        if (xmlPrompt != null && !xmlPrompt.isBlank()) {
            return xmlPrompt;
        }

        return promptCache.get(modelId, key -> {
            String redisPrompt = promptLoaderService.getPromptFromRedis(key);
            if (redisPrompt != null && !redisPrompt.isBlank()) {
                log.debug("Using Redis cached prompt for model: {}", key);
                return redisPrompt;
            }

            SystemPrompt sp = mapper.selectByModelId(key);
            if (sp != null && sp.getPromptText() != null && !sp.getPromptText().isBlank()) {
                log.debug("Using DB prompt for model: {}", key);
                return sp.getPromptText();
            }

            return null;
        });
    }

    @Override
    public Optional<SystemPromptDTO> getPromptDTO(String modelId) {
        return Optional.ofNullable(mapper.selectByModelId(modelId)).map(this::toDTO);
    }

    @Override
    public List<SystemPromptDTO> listAll() {
        return mapper.selectAllOrdered().stream()
                .map(this::toDTO)
                .collect(Collectors.toList());
    }

    @Override
    public SystemPromptDTO saveOrUpdate(String modelId, String promptText) {
        SystemPromptDTO dto = transactionTemplate.execute(status -> {
            SystemPrompt entity = mapper.selectByModelId(modelId);
            if (entity != null) {
                entity.updatePrompt(promptText);
                mapper.updateById(entity);
            } else {
                entity = new SystemPrompt(modelId, promptText);
                mapper.insert(entity);
            }
            return toDTO(entity);
        });
        promptCache.invalidate(modelId);
        return dto;
    }

    @Override
    public boolean delete(String modelId) {
        boolean deleted = Boolean.TRUE.equals(transactionTemplate.execute(status ->
                mapper.deleteByModelId(modelId) > 0));
        if (deleted) {
            promptCache.invalidate(modelId);
        }
        return deleted;
    }

    private SystemPromptDTO toDTO(SystemPrompt entity) {
        return new SystemPromptDTO(
                entity.getModelId(),
                entity.getPromptText(),
                entity.getCreatedAt(),
                entity.getUpdatedAt()
        );
    }
}
