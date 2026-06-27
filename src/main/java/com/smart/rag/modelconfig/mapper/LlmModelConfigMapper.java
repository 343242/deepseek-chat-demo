package com.smart.rag.modelconfig.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.smart.rag.modelconfig.entity.LlmModelConfig;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * BYOK 模型配置 Mapper — 自定义 SQL 全部走 XML。
 * <p>
 * <b>禁用 MyBatis-Plus 默认 insertOrUpdate</b>：部分索引 {@code ON CONFLICT ... WHERE deleted=0}
 * 谓词必须显式写（对抗审查 R4），默认 insertOrUpdate 不支持部分索引。
 * <p>
 * SQL 正确性（ON CONFLICT 谓词匹配、软删重建、并发唯一索引）由真实 PG 冒烟覆盖，
 * 遵循 {@code VectorStoreMapperTest} 惯例不在单测验证。
 *
 * @see com.smart.rag.modelconfig.entity.LlmModelConfig
 */
@Mapper
public interface LlmModelConfigMapper extends BaseMapper<LlmModelConfig> {

    /**
     * 查用户启用配置（status=1, deleted=0），按 priority 升序 — resolveUserChain 主路径。
     */
    List<LlmModelConfig> selectEnabled(@Param("userId") Long userId, @Param("capabilityType") String capabilityType);

    /**
     * 查用户全部配置（deleted=0，不论 status）— 区分"无行" vs "全 disabled"（R1 三态）。
     */
    List<LlmModelConfig> selectAll(@Param("userId") Long userId, @Param("capabilityType") String capabilityType);

    /**
     * 幂等 upsert — {@code ON CONFLICT (user_id, capability_type, provider_code, model_name) WHERE deleted = 0}。
     * <p>
     * 谓词必须匹配部分索引 {@code uk_llm_config_user_model}（R4）；软删行（deleted=1）不冲突，可重建。
     *
     * @return 1=INSERT，2=UPDATE（PG ON CONFLICT DO UPDATE 受影响行数）
     */
    int upsert(LlmModelConfig entity);

    /**
     * 清除同 (userId, capabilityType) 其他行的 is_default（写前清旧快速路径）。
     * 并发兜底由 DB 部分唯一索引 {@code uk_llm_config_default} 保证（P0-2）。
     */
    int clearOtherDefaults(@Param("userId") Long userId,
                           @Param("capabilityType") String capabilityType,
                           @Param("excludeId") Long excludeId);

    /**
     * 用户删除时批量逻辑删除（deleted=1，审计行保留，design §14.1 R2）。
     */
    int markDeletedByUser(@Param("userId") Long userId);

    /**
     * 取任一存量行（deleted=0）— 仅供启动 canary 自检用（P2-10），不限 user/cap。
     */
    LlmModelConfig selectOneAny();
}
