package com.demo.deepseekchat.chat.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.demo.deepseekchat.chat.mapper.SystemPromptMapper;
import com.demo.deepseekchat.chat.dto.SystemPromptDTO;
import com.demo.deepseekchat.chat.entity.SystemPrompt;
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
public class SystemPromptService {

    private static final Logger log = LoggerFactory.getLogger(SystemPromptService.class);

    private final SystemPromptMapper mapper;
    private final TransactionTemplate transactionTemplate;
    private final PromptLoaderService promptLoaderService;
    private final Cache<String, String> promptCache;

    public SystemPromptService(SystemPromptMapper mapper,
                               TransactionTemplate transactionTemplate,
                               PromptLoaderService promptLoaderService) {
        this.mapper = mapper;
        this.transactionTemplate = transactionTemplate;
        this.promptLoaderService = promptLoaderService;
        this.promptCache = Caffeine.newBuilder()
                .expireAfterWrite(Duration.ofMinutes(5))
                .maximumSize(200)
                .build();
    }

    /**
     * 获取指定模型的 system prompt
     * <p>
     * 查找链：XML 模板 → Caffeine 缓存 → Redis → PostgreSQL → null
     */
    public String getPrompt(String modelId) {
        // 1. 最高优先级：XML 模板（内存中，启动时加载）
        String xmlPrompt = promptLoaderService.getPrompt(modelId);
        if (xmlPrompt != null && !xmlPrompt.isBlank()) {
            return xmlPrompt;
        }

        // 2. Caffeine 本地热缓存（TTL 5 分钟）
        return promptCache.get(modelId, key -> {
            // 3. Redis（XML 模板启动时写入，TTL 1 天）
            String redisPrompt = promptLoaderService.getPromptFromRedis(key);
            if (redisPrompt != null && !redisPrompt.isBlank()) {
                log.debug("Using Redis cached prompt for model: {}", key);
                return redisPrompt;
            }

            // 4. PostgreSQL 数据库
            SystemPrompt sp = mapper.selectOne(
                    new LambdaQueryWrapper<SystemPrompt>().eq(SystemPrompt::getModelId, key));
            if (sp != null && sp.getPromptText() != null && !sp.getPromptText().isBlank()) {
                log.debug("Using DB prompt for model: {}", key);
                return sp.getPromptText();
            }

            return null;
        });
    }

    public Optional<SystemPromptDTO> getPromptDTO(String modelId) {
        SystemPrompt sp = mapper.selectOne(
                new LambdaQueryWrapper<SystemPrompt>().eq(SystemPrompt::getModelId, modelId));
        return Optional.ofNullable(sp).map(this::toDTO);
    }

    public List<SystemPromptDTO> listAll() {
        return mapper.selectList(null).stream()
                .map(this::toDTO)
                .collect(Collectors.toList());
    }

    public SystemPromptDTO saveOrUpdate(String modelId, String promptText) {
        SystemPromptDTO dto = transactionTemplate.execute(status -> {
            SystemPrompt entity = mapper.selectOne(
                    new LambdaQueryWrapper<SystemPrompt>().eq(SystemPrompt::getModelId, modelId));
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

    public boolean delete(String modelId) {
        Boolean deleted = transactionTemplate.execute(status -> {
            SystemPrompt entity = mapper.selectOne(
                    new LambdaQueryWrapper<SystemPrompt>().eq(SystemPrompt::getModelId, modelId));
            if (entity != null) {
                mapper.deleteById(entity.getId());
                return true;
            }
            return false;
        });
        if (Boolean.TRUE.equals(deleted)) {
            promptCache.invalidate(modelId);
            return true;
        }
        return false;
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
