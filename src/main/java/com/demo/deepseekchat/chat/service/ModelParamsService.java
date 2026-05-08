package com.demo.deepseekchat.chat.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.demo.deepseekchat.chat.mapper.ModelParamsMapper;
import com.demo.deepseekchat.chat.dto.ModelParamsDTO;
import com.demo.deepseekchat.chat.entity.ModelParams;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 模型参数管理服务
 * <p>
 * 支持动态调整 temperature、maxTokens、topP 等参数，无需重启。
 * 使用 Caffeine 本地缓存减少热路径 DB 查询（TTL 30s），写入时主动失效。
 */
@Service
public class ModelParamsService {

    private final ModelParamsMapper mapper;
    private final TransactionTemplate transactionTemplate;
    private final Cache<String, ModelParams> paramsCache;

    public ModelParamsService(ModelParamsMapper mapper,
                              TransactionTemplate transactionTemplate) {
        this.mapper = mapper;
        this.transactionTemplate = transactionTemplate;
        this.paramsCache = Caffeine.newBuilder()
                .expireAfterWrite(Duration.ofSeconds(30))
                .maximumSize(200)
                .build();
    }

    /**
     * 获取指定模型的运行时参数（带缓存）
     *
     * @return ModelParams 实体，未配置时返回 null
     */
    public ModelParams getParams(String modelId) {
        return paramsCache.get(modelId, key ->
                mapper.selectOne(new LambdaQueryWrapper<ModelParams>().eq(ModelParams::getModelId, key))
        );
    }

    /**
     * 获取指定模型的参数 DTO
     */
    public Optional<ModelParamsDTO> getParamsDTO(String modelId) {
        ModelParams entity = mapper.selectOne(
                new LambdaQueryWrapper<ModelParams>().eq(ModelParams::getModelId, modelId));
        return Optional.ofNullable(entity).map(this::toDTO);
    }

    /**
     * 获取所有模型参数配置
     */
    public List<ModelParamsDTO> listAll() {
        return mapper.selectList(null).stream()
                .map(this::toDTO)
                .collect(Collectors.toList());
    }

    /**
     * 创建或更新模型参数（编程式事务 + 缓存失效）
     */
    public ModelParamsDTO saveOrUpdate(String modelId, ModelParamsDTO dto) {
        ModelParamsDTO result = transactionTemplate.execute(status -> {
            ModelParams entity = mapper.selectOne(
                    new LambdaQueryWrapper<ModelParams>().eq(ModelParams::getModelId, modelId));
            if (entity != null) {
                entity.applyUpdates(dto.temperature(), dto.maxTokens(), dto.topP(),
                        dto.frequencyPenalty(), dto.presencePenalty());
                mapper.updateById(entity);
            } else {
                entity = new ModelParams(modelId);
                entity.applyUpdates(dto.temperature(), dto.maxTokens(), dto.topP(),
                        dto.frequencyPenalty(), dto.presencePenalty());
                mapper.insert(entity);
            }
            return toDTO(entity);
        });
        paramsCache.invalidate(modelId);
        return result;
    }

    /**
     * 删除指定模型的参数配置（编程式事务 + 缓存失效）
     */
    public boolean delete(String modelId) {
        Boolean deleted = transactionTemplate.execute(status -> {
            ModelParams entity = mapper.selectOne(
                    new LambdaQueryWrapper<ModelParams>().eq(ModelParams::getModelId, modelId));
            if (entity != null) {
                mapper.deleteById(entity.getId());
                return true;
            }
            return false;
        });
        if (Boolean.TRUE.equals(deleted)) {
            paramsCache.invalidate(modelId);
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
