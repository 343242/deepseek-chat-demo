package com.smart.rag.modelconfig.service.impl;

import com.smart.rag.common.snowflake.SnowflakeIdGenerator;
import com.smart.rag.infrastructure.exception.ClientException;
import com.smart.rag.infrastructure.exception.errorcode.ClientErrorCode;
import com.smart.rag.infrastructure.llm.LlmCapability;
import com.smart.rag.infrastructure.llm.crypto.ApiKeyCipher;
import com.smart.rag.infrastructure.security.HostSafetyValidator;
import com.smart.rag.modelconfig.dto.UpsertLlmConfigRequest;
import com.smart.rag.modelconfig.entity.LlmModelConfig;
import com.smart.rag.modelconfig.mapper.LlmModelConfigMapper;
import com.smart.rag.modelconfig.service.LlmModelConfigService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * BYOK 模型配置服务实现（design §4 / §6 / §12）。
 * <p>
 * <b>同步落库（阶段 A）</b>：upsert = HostSafetyValidator → ApiKeyCipher.encrypt → DB upsert（事务）；
 * 不调 registry.invalidateUser（Step 11 接入 per-user 快照 cache-aside）。
 * <p>
 * <b>is_default 互斥</b>：写前 {@code clearOtherDefaults} 清旧（快速路径），
 * 并发双写兜底由 DB 部分唯一索引 {@code uk_llm_config_default} 保证（P0-2）。
 */
@Service
public class LlmModelConfigServiceImpl implements LlmModelConfigService {

    private static final Logger log = LoggerFactory.getLogger(LlmModelConfigServiceImpl.class);

    private final LlmModelConfigMapper mapper;
    private final ApiKeyCipher apiKeyCipher;
    private final HostSafetyValidator baseUrlValidator;
    private final SnowflakeIdGenerator idGenerator;

    public LlmModelConfigServiceImpl(LlmModelConfigMapper mapper,
                                     ApiKeyCipher apiKeyCipher,
                                     HostSafetyValidator baseUrlValidator,
                                     SnowflakeIdGenerator idGenerator) {
        this.mapper = mapper;
        this.apiKeyCipher = apiKeyCipher;
        this.baseUrlValidator = baseUrlValidator;
        this.idGenerator = idGenerator;
    }

    @Override
    @Transactional
    public LlmModelConfig upsert(Long userId, UpsertLlmConfigRequest req) {
        requireNonBlank(req.getCapabilityType(), "capability_type");
        // P1-8：本期仅 CHAT BYOK；EMBEDDING/RERANKING API 层拒绝（存而不读即 silent failure，比报错更危险）
        if (!LlmCapability.CHAT.name().equals(req.getCapabilityType())) {
            throw new ClientException(ClientErrorCode.UNSUPPORTED_OPERATION,
                "本期 BYOK 仅支持 CHAT，不支持 " + req.getCapabilityType());
        }
        requireNonBlank(req.getProviderCode(), "provider_code");
        requireNonBlank(req.getBaseUrl(), "base_url");
        requireNonBlank(req.getApiKey(), "api_key");
        requireNonBlank(req.getModelName(), "model_name");

        baseUrlValidator.validate(req.getBaseUrl());

        ApiKeyCipher.CipherText ct = apiKeyCipher.encrypt(req.getApiKey());
        boolean isDefault = Boolean.TRUE.equals(req.getIsDefault());

        LlmModelConfig entity = new LlmModelConfig();
        entity.setId(idGenerator.nextId());
        entity.setUserId(userId);
        entity.setCapabilityType(req.getCapabilityType());
        entity.setProviderCode(req.getProviderCode());
        entity.setBaseUrl(req.getBaseUrl());
        entity.setApiKeyCipher(ct.cipher());
        entity.setApiKeyIv(ct.iv());
        entity.setModelName(req.getModelName());
        entity.setDisplayName(req.getDisplayName());
        entity.setEndpoints(req.getEndpoints());
        entity.setDimension(req.getDimension());
        entity.setSupportsStreaming(Boolean.TRUE.equals(req.getSupportsStreaming()));
        entity.setSupportsThinking(Boolean.TRUE.equals(req.getSupportsThinking()));
        entity.setPriority(req.getPriority() != null ? req.getPriority() : 100);
        entity.setIsDefault(isDefault);
        entity.setStatus(req.getStatus() != null ? req.getStatus() : 1);
        entity.setCreatedBy(String.valueOf(userId));
        entity.setUpdatedBy(String.valueOf(userId));

        // is_default 互斥快速路径（并发兜底由 DB 部分唯一索引 uk_llm_config_default 保证 P0-2）
        if (isDefault) {
            mapper.clearOtherDefaults(userId, entity.getCapabilityType(), null);
        }
        mapper.upsert(entity);
        log.info("BYOK config upserted: user={} cap={} provider={} model={} default={}",
            userId, entity.getCapabilityType(), entity.getProviderCode(), entity.getModelName(), isDefault);
        return entity;
    }

    @Override
    @Transactional
    public void delete(Long userId, Long configId) {
        requireOwned(userId, configId); // owner-only（defense in depth；controller 已保证 userId 来自 SecurityContext）
        // @TableLogic soft delete（deleted=1，审计行保留，可重建 R4）
        mapper.deleteById(configId);
        log.info("BYOK config deleted: id={} user={}", configId, userId);
    }

    @Override
    public LlmModelConfig getOwned(Long userId, Long configId) {
        return requireOwned(userId, configId);
    }

    /** 校验配置存在且属于 userId（owner-only 读/写共用，defense in depth；越权 → FORBIDDEN） */
    private LlmModelConfig requireOwned(Long userId, Long configId) {
        LlmModelConfig entity = mapper.selectById(configId);
        if (entity == null) {
            throw new ClientException(ClientErrorCode.BAD_REQUEST, "BYOK 配置不存在: " + configId);
        }
        if (!userId.equals(entity.getUserId())) {
            throw new ClientException(ClientErrorCode.FORBIDDEN, "仅 owner 可操作自己的 BYOK 配置");
        }
        return entity;
    }

    @Override
    public List<LlmModelConfig> resolveUserChain(Long userId, LlmCapability capability) {
        return mapper.selectEnabled(userId, capability.name());
    }

    @Override
    public List<LlmModelConfig> selectAll(Long userId, LlmCapability capability) {
        return mapper.selectAll(userId, capability.name());
    }

    @Override
    public String decryptKey(LlmModelConfig entity) {
        return apiKeyCipher.decrypt(entity.getApiKeyCipher(), entity.getApiKeyIv());
    }

    @Override
    public String maskKey(LlmModelConfig entity) {
        try {
            String plain = decryptKey(entity);
            if (plain == null || plain.length() < 8) {
                return "****";
            }
            return plain.substring(0, 3) + "***" + plain.substring(plain.length() - 4);
        } catch (Exception e) {
            // master-key 误改 / 密文损坏 → 不暴露明文，不阻断 GET（canary 自检另在 Step 3 runner）
            log.warn("maskKey decrypt failed for config {}: {}", entity.getId(), e.getMessage());
            return "****";
        }
    }

    private static void requireNonBlank(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new ClientException(ClientErrorCode.VALIDATION_ERROR, name + " 不能为空");
        }
    }
}
