package com.smart.rag.mcp.admin;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class McpV18MigrationContractTest {

    @Test
    void migrationRepairsPendingStateAndConstrainsToolPolicy() throws Exception {
        Path migration = Path.of("src/main/resources/db/migration/V18__repair_mcp_admin_constraints.sql");

        assertThat(migration).exists();
        String sql = Files.readString(migration).toLowerCase();
        assertThat(sql).contains("drop constraint if exists mcp_server_config_state");
        assertThat(sql).contains("general_tool");
        assertThat(sql).contains("direct_answer");
        assertThat(sql).contains("deep_retrieval");
        assertThat(sql).contains("risk in ('low', 'high')");
        assertThat(sql).contains("server_id, tool_name");
        assertThat(sql).contains("conrelid = 'mcp_tool_config'::regclass");
        assertThat(sql).contains("row_number() over", "partition by server_id, tool_name");
        assertThat(sql.indexOf("row_number() over"))
                .isLessThan(sql.indexOf("create unique index"));
    }
}
