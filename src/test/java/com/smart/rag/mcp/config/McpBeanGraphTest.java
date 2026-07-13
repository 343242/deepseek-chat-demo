package com.smart.rag.mcp.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smart.rag.infrastructure.security.HostSafetyValidator;
import com.smart.rag.mcp.admin.mapper.McpSecurityConfigMapper;
import com.smart.rag.mcp.admin.mapper.McpServerConfigMapper;
import com.smart.rag.mcp.admin.mapper.McpToolConfigMapper;
import com.smart.rag.mcp.admin.service.McpAdminService;
import com.smart.rag.mcp.admin.service.McpBootstrapRunner;
import com.smart.rag.mcp.admin.service.McpSecurityAdminService;
import com.smart.rag.mcp.admin.service.McpSecurityConfigAccessor;
import com.smart.rag.mcp.admin.service.McpSecurityConfigValidator;
import com.smart.rag.mcp.admin.service.McpServerAdminService;
import com.smart.rag.mcp.admin.service.McpServerRuntime;
import com.smart.rag.mcp.admin.service.McpToolAdminService;
import com.smart.rag.mcp.admin.service.McpToolConfigAccessor;
import com.smart.rag.mcp.core.McpServerRegistry;
import com.smart.rag.mcp.mcpclient.DefaultMcpServerToolCallbacksAdapter;
import com.smart.rag.mcp.mcpclient.SyncMcpToolCallbackProvider;
import com.smart.rag.mcp.runtime.McpBearerTokenCodec;
import com.smart.rag.mcp.runtime.McpDesiredStateHasher;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class McpBeanGraphTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withPropertyValues("spring.ai.mcp.client.enabled=true")
            .withUserConfiguration(McpClientConfiguration.class, McpClientTransportConfiguration.class)
            .withBean(ObjectMapper.class, ObjectMapper::new)
            .withBean(McpServerConfigMapper.class, () -> mock(McpServerConfigMapper.class))
            .withBean(McpToolConfigMapper.class, () -> mock(McpToolConfigMapper.class))
            .withBean(McpSecurityConfigMapper.class, () -> mock(McpSecurityConfigMapper.class))
            .withBean(TransactionTemplate.class, () -> mock(TransactionTemplate.class))
            .withBean(HostSafetyValidator.class, () -> mock(HostSafetyValidator.class))
            .withBean(McpBearerTokenCodec.class, () -> mock(McpBearerTokenCodec.class))
            .withBean(McpDesiredStateHasher.class, () -> mock(McpDesiredStateHasher.class))
            .withBean(McpServerRuntime.class, () -> mock(McpServerRuntime.class))
            .withBean(McpServerRegistry.class, () -> mock(McpServerRegistry.class))
            .withBean(McpToolConfigAccessor.class)
            .withBean(McpSecurityConfigValidator.class)
            .withBean(McpSecurityConfigAccessor.class)
            .withBean(DatabaseToolFilter.class)
            .withBean(DefaultMcpServerToolCallbacksAdapter.class)
            .withBean(McpToolAdminService.class)
            .withBean(McpServerAdminService.class)
            .withBean(McpSecurityAdminService.class)
            .withBean(McpAdminService.class)
            .withBean(McpBootstrapRunner.class);

    @Test
    void adminProviderFilterGraphStartsWithoutConstructorCycle() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(McpAdminService.class);
            assertThat(context).hasSingleBean(SyncMcpToolCallbackProvider.class);
            assertThat(context).hasSingleBean(DatabaseToolFilter.class);
        });
    }
}
