package com.smart.rag.mcp.admin;

import com.smart.rag.mcp.admin.entity.McpSecurityConfig;
import com.smart.rag.mcp.admin.entity.McpServerConfig;
import com.smart.rag.mcp.admin.entity.McpToolConfig;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class McpEntityTimeContractTest {

    @Test
    void timestamptzFieldsUseOffsetDateTime() throws Exception {
        assertThat(McpServerConfig.class.getDeclaredField("lastConnectedAt").getType())
                .isEqualTo(OffsetDateTime.class);
        assertThat(McpServerConfig.class.getDeclaredField("createdAt").getType())
                .isEqualTo(OffsetDateTime.class);
        assertThat(McpServerConfig.class.getDeclaredField("updatedAt").getType())
                .isEqualTo(OffsetDateTime.class);
        assertThat(McpToolConfig.class.getDeclaredField("createdAt").getType())
                .isEqualTo(OffsetDateTime.class);
        assertThat(McpToolConfig.class.getDeclaredField("updatedAt").getType())
                .isEqualTo(OffsetDateTime.class);
        assertThat(McpSecurityConfig.class.getDeclaredField("updatedAt").getType())
                .isEqualTo(OffsetDateTime.class);
    }
}
