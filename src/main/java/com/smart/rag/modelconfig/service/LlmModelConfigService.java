package com.smart.rag.modelconfig.service;

import com.smart.rag.infrastructure.llm.LlmCapability;
import com.smart.rag.modelconfig.dto.UpsertLlmConfigRequest;
import com.smart.rag.modelconfig.entity.LlmModelConfig;

import java.util.List;

/**
 * BYOK 模型配置服务（design §4 / §6 / §12）。
 * <p>
 * <b>owner-only 写</b>：{@code userId} 由 controller 从 SecurityContext 取后显式传入（design §8 方案 A）；
 * 本服务不做越权判断的依赖注入，{@code delete} 校验 entity.userId == 入参 userId（defense in depth）。
 * <p>
 * <b>纯 DB 同步版（阶段 A）</b>：{@code upsert/delete} 仅落库 + 软删；
 * {@code registry.invalidateUser} 接入在 Step 11（per-user 快照 cache-aside）。
 */
public interface LlmModelConfigService {

    /**
     * 幂等 upsert：BaseUrlValidator → ApiKeyCipher.encrypt → DB upsert（ON CONFLICT）。
     * <p>
     * P1-8：仅 {@code CHAT}，EMBEDDING/RERANKING → {@code ClientException}（UNSUPPORTED_OPERATION）。
     *
     * @param userId owner 用户 ID（controller 从 SecurityContext 取）
     * @return 落库后的实体（含生成的 id）
     */
    LlmModelConfig upsert(Long userId, UpsertLlmConfigRequest request);

    /**
     * owner 删除单条配置（@TableLogic 软删 deleted=1，审计行保留）。
     * <p>
     * 校验 entity 存在且 entity.userId == userId，否则 FORBIDDEN。
     */
    void delete(Long userId, Long configId);

    /**
     * owner 查看单条配置（校验 entity 存在且 entity.userId == userId，否则 FORBIDDEN）。
     */
    LlmModelConfig getOwned(Long userId, Long configId);

    /**
     * 用户启用配置链（status=1, deleted=0，按 priority 升序）— resolveUserChain 主路径。
     */
    List<LlmModelConfig> resolveUserChain(Long userId, LlmCapability capability);

    /**
     * 用户全部配置（deleted=0，不论 status）— 区分"无行" vs "全 disabled"（R1 三态）。
     */
    List<LlmModelConfig> selectAll(Long userId, LlmCapability capability);

    /**
     * 瞬态解密 api_key（SPI 取用时调用，明文不持久化）。
     */
    String decryptKey(LlmModelConfig entity);

    /**
     * 脱敏回显 {@code <prefix>***<last4>}（如 {@code sk-***5678}）；解密失败回 {@code ****}（不暴露明文也不阻断 GET）。
     */
    String maskKey(LlmModelConfig entity);
}
