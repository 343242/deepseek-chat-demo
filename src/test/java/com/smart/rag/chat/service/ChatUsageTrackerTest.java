package com.smart.rag.chat.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * ChatUsageTracker 测试
 */
@ExtendWith(MockitoExtension.class)
class ChatUsageTrackerTest {

    @Mock
    private UsageService usageService;

    @Mock
    private ChatResponse aiResponse;

    @Mock
    private ChatResponseMetadata metadata;

    @Mock
    private Usage usage;

    @Nested
    @DisplayName("recordUsage(aiResponse)")
    class RecordUsageWithResponse {

        @Test
        @DisplayName("usage 非空时委托给 UsageService")
        void test_recordUsage_withResponse_delegatesToUsageService() {
            when(aiResponse.getMetadata()).thenReturn(metadata);
            when(metadata.getUsage()).thenReturn(usage);
            when(usage.getPromptTokens()).thenReturn(100);
            when(usage.getCompletionTokens()).thenReturn(50);
            when(usage.getTotalTokens()).thenReturn(150);

            ChatUsageTracker tracker = new ChatUsageTracker(usageService);
            tracker.recordUsage("conv-1", "model-a", aiResponse, 200L);

            verify(usageService).recordUsage("conv-1", "model-a", 100L, 50L, 150L, 200L);
        }

        @Test
        @DisplayName("usage 为 null 时不调用 UsageService")
        void test_recordUsage_withResponse_noCallWhenUsageNull() {
            when(aiResponse.getMetadata()).thenReturn(metadata);
            when(metadata.getUsage()).thenReturn(null);

            ChatUsageTracker tracker = new ChatUsageTracker(usageService);
            tracker.recordUsage("conv-1", "model-a", aiResponse, 200L);

            verify(usageService, never()).recordUsage(anyString(), anyString(), anyLong(), anyLong(), anyLong(), anyLong());
        }

        @Test
        @DisplayName("UsageService 抛异常时不向外传播")
        void test_recordUsage_withResponse_swallowsException() {
            when(aiResponse.getMetadata()).thenReturn(metadata);
            when(metadata.getUsage()).thenReturn(usage);
            when(usage.getPromptTokens()).thenReturn(100);
            when(usage.getCompletionTokens()).thenReturn(50);
            when(usage.getTotalTokens()).thenReturn(150);
            doThrow(new RuntimeException("DB error")).when(usageService)
                    .recordUsage(anyString(), anyString(), anyLong(), anyLong(), anyLong(), anyLong());

            ChatUsageTracker tracker = new ChatUsageTracker(usageService);
            // Should not throw
            tracker.recordUsage("conv-1", "model-a", aiResponse, 200L);
        }
    }

    @Nested
    @DisplayName("recordUsage(durationOnly)")
    class RecordUsageDurationOnly {

        @Test
        @DisplayName("无 AI 响应时使用默认值 -1")
        void test_recordUsage_durationOnly_usesDefaults() {
            ChatUsageTracker tracker = new ChatUsageTracker(usageService);
            tracker.recordUsage("conv-1", "model-a", 200L);

            verify(usageService).recordUsage("conv-1", "model-a", -1L, -1L, -1L, 200L);
        }

        @Test
        @DisplayName("UsageService 抛异常时不向外传播")
        void test_recordUsage_durationOnly_swallowsException() {
            doThrow(new RuntimeException("DB error")).when(usageService)
                    .recordUsage(anyString(), anyString(), anyLong(), anyLong(), anyLong(), anyLong());

            ChatUsageTracker tracker = new ChatUsageTracker(usageService);
            tracker.recordUsage("conv-1", "model-a", 200L);
        }
    }
}
