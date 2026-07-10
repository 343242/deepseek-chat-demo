package com.smart.rag.mcp.admin.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smart.rag.mcp.admin.entity.McpSecurityConfig;
import com.smart.rag.mcp.admin.entity.McpSecurityConfigView;
import com.smart.rag.mcp.admin.mapper.McpSecurityConfigMapper;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class McpSecurityConfigAccessorTest {

    @Test
    void invalidPersistedCapsFallBackToSafeDefaults() {
        McpSecurityConfigMapper mapper = mock(McpSecurityConfigMapper.class);
        when(mapper.selectSingleton()).thenReturn(row("""
                {"sensitiveArgPatterns":[],"defaultOutputCapChars":-1,
                 "highRiskOutputCapChars":-2,"toolDescCharLimit":0}
                """));
        McpSecurityConfigAccessor accessor = new McpSecurityConfigAccessor(
                mapper, new ObjectMapper(), new McpSecurityConfigValidator());

        assertThat(accessor.get()).isEqualTo(McpSecurityConfigView.defaults());
    }

    @Test
    void invalidPersistedRegexDoesNotBreakHotPath() {
        McpSecurityConfigMapper mapper = mock(McpSecurityConfigMapper.class);
        when(mapper.selectSingleton()).thenReturn(row("""
                {"sensitiveArgPatterns":["[broken"],"defaultOutputCapChars":4000,
                 "highRiskOutputCapChars":1000,"toolDescCharLimit":500}
                """));
        McpSecurityConfigAccessor accessor = new McpSecurityConfigAccessor(
                mapper, new ObjectMapper(), new McpSecurityConfigValidator());

        assertThatCode(accessor::patterns).doesNotThrowAnyException();
        assertThat(accessor.patterns()).isEmpty();
    }

    @Test
    void invalidationCannotBeOverwrittenByAnInFlightPatternCompilation() throws Exception {
        McpSecurityConfigMapper mapper = mock(McpSecurityConfigMapper.class);
        when(mapper.selectSingleton()).thenReturn(
                row("""
                        {"sensitiveArgPatterns":["old"],"defaultOutputCapChars":4000,
                         "highRiskOutputCapChars":1000,"toolDescCharLimit":500}
                        """),
                row("""
                        {"sensitiveArgPatterns":["new"],"defaultOutputCapChars":4000,
                         "highRiskOutputCapChars":1000,"toolDescCharLimit":500}
                        """));
        CountDownLatch validationStarted = new CountDownLatch(1);
        CountDownLatch continueValidation = new CountDownLatch(1);
        McpSecurityConfigValidator validator = mock(McpSecurityConfigValidator.class);
        when(validator.validate(any())).thenAnswer(invocation -> {
            McpSecurityConfigView view = invocation.getArgument(0);
            if (view.sensitiveArgPatterns().contains("old")) {
                validationStarted.countDown();
                continueValidation.await();
            }
            return view;
        });
        McpSecurityConfigAccessor accessor = new McpSecurityConfigAccessor(
                mapper, new ObjectMapper(), validator);

        Thread loader = Thread.ofPlatform().unstarted(accessor::patterns);
        loader.start();
        assertThat(validationStarted.await(2, TimeUnit.SECONDS)).isTrue();
        Thread invalidator = Thread.ofPlatform().unstarted(accessor::invalidate);
        invalidator.start();
        awaitBlockedOrTerminated(invalidator);
        continueValidation.countDown();
        loader.join();
        invalidator.join();

        assertThat(accessor.patterns()).singleElement()
                .satisfies(pattern -> assertThat(pattern.pattern()).isEqualTo("new"));
    }

    private static void awaitBlockedOrTerminated(Thread thread) {
        long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
        while (thread.getState() != Thread.State.BLOCKED
                && thread.getState() != Thread.State.TERMINATED
                && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        assertThat(thread.getState()).isIn(Thread.State.BLOCKED, Thread.State.TERMINATED);
    }

    private static McpSecurityConfig row(String json) {
        McpSecurityConfig row = new McpSecurityConfig();
        row.setId(1L);
        row.setConfigJson(json);
        return row;
    }
}
