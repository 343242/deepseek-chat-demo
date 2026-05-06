package com.demo.deepseekchat.service;

import com.demo.deepseekchat.model.dto.SystemPromptDTO;
import com.demo.deepseekchat.model.entity.SystemPrompt;
import com.demo.deepseekchat.repository.SystemPromptRepository;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * System Prompt 管理服务
 * <p>
 * 支持为不同模型配置独立的系统提示词，可通过 API 动态修改。
 */
@Service
public class SystemPromptService {

    private final SystemPromptRepository repository;

    public SystemPromptService(SystemPromptRepository repository) {
        this.repository = repository;
    }

    /**
     * 获取指定模型的 system prompt
     *
     * @return promptText，未配置时返回 null
     */
    public String getPrompt(String modelId) {
        return repository.findByModelId(modelId)
                .map(SystemPrompt::getPromptText)
                .orElse(null);
    }

    /**
     * 获取指定模型的 SystemPromptDTO
     */
    public Optional<SystemPromptDTO> getPromptDTO(String modelId) {
        return repository.findByModelId(modelId)
                .map(this::toDTO);
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
     * 创建或更新 system prompt
     *
     * @return 更新后的 DTO
     */
    public SystemPromptDTO saveOrUpdate(String modelId, String promptText) {
        SystemPrompt entity = repository.findByModelId(modelId)
                .map(sp -> {
                    sp.updatePrompt(promptText);
                    return sp;
                })
                .orElseGet(() -> new SystemPrompt(modelId, promptText));
        return toDTO(repository.save(entity));
    }

    /**
     * 删除指定模型的 system prompt
     */
    public boolean delete(String modelId) {
        if (repository.existsByModelId(modelId)) {
            repository.deleteByModelId(modelId);
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
