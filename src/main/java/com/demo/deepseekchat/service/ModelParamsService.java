package com.demo.deepseekchat.service;

import com.demo.deepseekchat.model.dto.ModelParamsDTO;
import com.demo.deepseekchat.model.entity.ModelParams;
import com.demo.deepseekchat.repository.ModelParamsRepository;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 模型参数管理服务
 * <p>
 * 支持动态调整 temperature、maxTokens、topP 等参数，无需重启。
 * ChatService 调用时查询该服务获取运行时参数覆盖。
 */
@Service
public class ModelParamsService {

    private final ModelParamsRepository repository;

    public ModelParamsService(ModelParamsRepository repository) {
        this.repository = repository;
    }

    /**
     * 获取指定模型的运行时参数
     *
     * @return ModelParams 实体，未配置时返回 null
     */
    public ModelParams getParams(String modelId) {
        return repository.findByModelId(modelId).orElse(null);
    }

    /**
     * 获取指定模型的参数 DTO
     */
    public Optional<ModelParamsDTO> getParamsDTO(String modelId) {
        return repository.findByModelId(modelId).map(this::toDTO);
    }

    /**
     * 获取所有模型参数配置
     */
    public List<ModelParamsDTO> listAll() {
        return repository.findAll().stream()
                .map(this::toDTO)
                .collect(Collectors.toList());
    }

    /**
     * 创建或更新模型参数
     * <p>
     * 只更新非 null 的字段，null 字段保持原值。
     */
    public ModelParamsDTO saveOrUpdate(String modelId, ModelParamsDTO dto) {
        ModelParams entity = repository.findByModelId(modelId)
                .map(p -> {
                    p.applyUpdates(
                            dto.temperature(), dto.maxTokens(), dto.topP(),
                            dto.frequencyPenalty(), dto.presencePenalty());
                    return p;
                })
                .orElseGet(() -> {
                    ModelParams newParams = new ModelParams(modelId);
                    newParams.applyUpdates(
                            dto.temperature(), dto.maxTokens(), dto.topP(),
                            dto.frequencyPenalty(), dto.presencePenalty());
                    return newParams;
                });
        return toDTO(repository.save(entity));
    }

    /**
     * 删除指定模型的参数配置（恢复默认）
     */
    public boolean delete(String modelId) {
        if (repository.existsByModelId(modelId)) {
            repository.deleteByModelId(modelId);
            return true;
        }
        return false;
    }

    private ModelParamsDTO toDTO(ModelParams entity) {
        return new ModelParamsDTO(
                entity.getModelId(),
                entity.getTemperature(),
                entity.getMaxTokens(),
                entity.getTopP(),
                entity.getFrequencyPenalty(),
                entity.getPresencePenalty()
        );
    }
}
