package com.smart.rag.chat.service.impl;

import com.smart.rag.chat.mapper.ModelParamsMapper;
import com.smart.rag.chat.dto.ModelParamsDTO;
import com.smart.rag.chat.entity.ModelParams;
import com.smart.rag.chat.service.ModelParamsService;
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
public class ModelParamsServiceImpl implements ModelParamsService {

    private final ModelParamsMapper mapper;
    private final TransactionTemplate transactionTemplate;
    private final Cache<String, ModelParams> paramsCache;

    public ModelParamsServiceImpl(ModelParamsMapper mapper,
                                  TransactionTemplate transactionTemplate) {
        this.mapper = mapper;
        this.transactionTemplate = transactionTemplate;
        this.paramsCache = Caffeine.newBuilder()
                .expireAfterWrite(Duration.ofSeconds(30))
                .maximumSize(200)
                .build();
    }

    @Override
    public ModelParams getParams(String modelId) {
        return paramsCache.get(modelId, mapper::selectByModelId);
    }

    @Override
    public Optional<ModelParamsDTO> getParamsDTO(String modelId) {
        return Optional.ofNullable(mapper.selectByModelId(modelId)).map(this::toDTO);
    }

    @Override
    public List<ModelParamsDTO> listAll() {
        return mapper.selectAllOrdered().stream()
                .map(this::toDTO)
                .collect(Collectors.toList());
    }

    @Override
    public ModelParamsDTO saveOrUpdate(String modelId, ModelParamsDTO dto) {
        ModelParamsDTO result = transactionTemplate.execute(status -> {
            ModelParams entity = mapper.selectByModelId(modelId);
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

    @Override
    public boolean delete(String modelId) {
        boolean deleted = Boolean.TRUE.equals(transactionTemplate.execute(status ->
                mapper.deleteByModelId(modelId) > 0));
        if (deleted) {
            paramsCache.invalidate(modelId);
        }
        return deleted;
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
