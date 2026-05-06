package com.demo.deepseekchat.service;

import com.demo.deepseekchat.model.dto.SystemPromptDTO;
import com.demo.deepseekchat.model.entity.SystemPrompt;
import com.demo.deepseekchat.repository.SystemPromptRepository;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * System Prompt 管理服务
 * <p>
 * 支持为不同模型配置独立的系统提示词，可通过 API 动态修改。
 * 使用 Caffeine 本地缓存减少热路径 DB 查询（TTL 30s），写入时主动失效。
 */
@Service
public class SystemPromptService {

    private final SystemPromptRepository repository;
    private final TransactionTemplate transactionTemplate;
    private final Cache<String, String> promptCache;

    public SystemPromptService(SystemPromptRepository repository,
                               PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.transactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
        this.promptCache = Caffeine.newBuilder()
                .expireAfterWrite(Duration.ofSeconds(30))
                .maximumSize(200)
                .build();
    }

    /**
     * 获取指定模型的 system prompt（带缓存）
     *
     * @return promptText，未配置时返回 null
     */
    public String getPrompt(String modelId) {
        return promptCache.get(modelId, key ->
                repository.findByModelId(key)
                        .map(SystemPrompt::getPromptText)
                        .orElse(null)
        );
    }

    /**
     * 获取指定模型的 SystemPromptDTO
     */
    public Optional<SystemPromptDTO> getPromptDTO(String modelId) {
        return repository.findByModelId(modelId).map(this::toDTO);
    }

    /**
     * 获取所有 system prompt 配置
     */
    public List<SystemPromptDTO> listAll() {
        return repository.findAll().stream()
                .map(this::toDTO)
                .collect(Collectors.toList());
    }

    /**
     * 创建或更新 system prompt（编程式事务 + 缓存失效）
     */
    public SystemPromptDTO saveOrUpdate(String modelId, String promptText) {
        SystemPromptDTO dto = transactionTemplate.execute(status -> {
            SystemPrompt entity = repository.findByModelId(modelId)
                    .map(sp -> { sp.updatePrompt(promptText); return sp; })
                    .orElseGet(() -> new SystemPrompt(modelId, promptText));
            return toDTO(repository.save(entity));
        });
        promptCache.invalidate(modelId);
        return dto;
    }

    /**
     * 删除指定模型的 system prompt（编程式事务 + 缓存失效）
     */
    public boolean delete(String modelId) {
        Boolean deleted = transactionTemplate.execute(status -> {
            if (repository.existsByModelId(modelId)) {
                // Spring Data derived delete: 先查后删，需在同一事务
                SystemPrompt entity = repository.findByModelId(modelId).orElseThrow();
                repository.delete(entity);
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
