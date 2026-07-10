package com.smart.rag.mcp.config;

import com.smart.rag.mcp.mcpclient.McpServerToolCallbacksAdapter;
import org.junit.jupiter.api.Test;
import org.springframework.stereotype.Component;

import static org.assertj.core.api.Assertions.assertThat;

class McpCallbackAdapterBeanTest {

    @Test
    void callbackAdapterHasAConcreteSpringBean() throws Exception {
        Class<?> implementation = Class.forName(
                "com.smart.rag.mcp.mcpclient.DefaultMcpServerToolCallbacksAdapter");

        assertThat(McpServerToolCallbacksAdapter.class).isAssignableFrom(implementation);
        assertThat(implementation).hasAnnotation(Component.class);
    }
}
