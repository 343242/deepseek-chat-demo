package com.smart.rag.config.time;

import com.smart.rag.chat.entity.ModelParams;
import com.smart.rag.chat.entity.SystemPrompt;
import com.smart.rag.chat.entity.TokenUsage;
import com.smart.rag.infrastructure.audit.entity.AdminAuditLog;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 实体时间字段契约测试（设计文档 §12 完成标准 1）。
 * <p>
 * 验证 V25 迁移收口的 4 个实体（原 {@code LocalDateTime} 残留）的时间字段
 * 已全部统一为 {@link OffsetDateTime}，与 DB {@code TIMESTAMPTZ} 列类型匹配。
 * <p>
 * 对标 {@code McpEntityTimeContractTest} 的反射契约模式。
 */
@DisplayName("实体时间字段契约：LocalDateTime 已彻底清除")
class EntityTimeContractTest {

    @Test
    @DisplayName("ModelParams 时间字段为 OffsetDateTime")
    void modelParamsUsesOffsetDateTime() throws Exception {
        assertThat(ModelParams.class.getDeclaredField("createdAt").getType())
                .isEqualTo(OffsetDateTime.class);
        assertThat(ModelParams.class.getDeclaredField("updatedAt").getType())
                .isEqualTo(OffsetDateTime.class);
    }

    @Test
    @DisplayName("SystemPrompt 时间字段为 OffsetDateTime")
    void systemPromptUsesOffsetDateTime() throws Exception {
        assertThat(SystemPrompt.class.getDeclaredField("createdAt").getType())
                .isEqualTo(OffsetDateTime.class);
        assertThat(SystemPrompt.class.getDeclaredField("updatedAt").getType())
                .isEqualTo(OffsetDateTime.class);
    }

    @Test
    @DisplayName("TokenUsage 时间字段为 OffsetDateTime")
    void tokenUsageUsesOffsetDateTime() throws Exception {
        assertThat(TokenUsage.class.getDeclaredField("createdAt").getType())
                .isEqualTo(OffsetDateTime.class);
    }

    @Test
    @DisplayName("AdminAuditLog 时间字段为 OffsetDateTime（修正 TIMESTAMPTZ 类型不匹配）")
    void adminAuditLogUsesOffsetDateTime() throws Exception {
        assertThat(AdminAuditLog.class.getDeclaredField("createdAt").getType())
                .isEqualTo(OffsetDateTime.class);
    }
}
