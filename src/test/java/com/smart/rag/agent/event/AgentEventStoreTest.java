package com.smart.rag.agent.event;

import com.smart.rag.agent.event.payload.GuardrailTriggeredPayload;
import com.smart.rag.agent.event.payload.IntentClassifiedPayload;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * AgentEventStore 单元测试。
 * <p>
 * 验证事件记录、快照构建、搜索委托、异常容错。
 */
@ExtendWith(MockitoExtension.class)
class AgentEventStoreTest {

    @Mock
    private AgentEventMapper mapper;

    @Mock
    private EventPayloadMapper payloadMapper;

    private AgentEventStore store;

    @BeforeEach
    void setUp() {
        store = new AgentEventStore(mapper, payloadMapper);
    }

    @Nested
    @DisplayName("recordIntentClassified")
    class RecordIntentClassified {

        @Test
        @DisplayName("调用 mapper.insert 且 eventType 正确")
        void record_insertsWithCorrectType() {
            when(payloadMapper.toJson(any(IntentClassifiedPayload.class))).thenReturn("{\"intent\":\"test\"}");

            IntentClassifiedPayload payload = new IntentClassifiedPayload("DIRECT_ANSWER", 0.9, "hash");
            store.recordIntentClassified("sess-1", 1L, payload);
            store.shutdown(); // 排空异步队列，确保 insert 已执行

            ArgumentCaptor<AgentSessionEvent> captor = ArgumentCaptor.forClass(AgentSessionEvent.class);
            verify(mapper).insert(captor.capture());

            AgentSessionEvent event = captor.getValue();
            assertThat(event.getEventType()).isEqualTo(AgentEventType.INTENT_CLASSIFIED);
            assertThat(event.getPriority()).isEqualTo(AgentEventPriority.CRITICAL);
            assertThat(event.getSessionId()).isEqualTo("sess-1");
            assertThat(event.getUserId()).isEqualTo(1L);
        }
    }

    @Nested
    @DisplayName("recordToolCall")
    class RecordToolCall {

        @Test
        @DisplayName("调用 mapper.insert 且 eventType=TOOL_CALLED, priority=NORMAL")
        void recordToolCall_insertsWithCorrectType() {
            store.recordToolCall("sess-1", 1L, "hybridSearch", true, "{\"result\":\"ok\"}", 150L);
            store.shutdown(); // 排空异步队列

            ArgumentCaptor<AgentSessionEvent> captor = ArgumentCaptor.forClass(AgentSessionEvent.class);
            verify(mapper).insert(captor.capture());

            AgentSessionEvent event = captor.getValue();
            assertThat(event.getEventType()).isEqualTo(AgentEventType.TOOL_CALLED);
            assertThat(event.getPriority()).isEqualTo(AgentEventPriority.NORMAL);
            assertThat(event.getToolName()).isEqualTo("hybridSearch");
            assertThat(event.getSuccess()).isTrue();
            assertThat(event.getDurationMs()).isEqualTo(150L);
        }
    }

    @Nested
    @DisplayName("buildResumeSnapshot")
    class BuildResumeSnapshot {

        @Test
        @DisplayName("空事件返回空字符串")
        void emptyEvents_returnsEmpty() {
            when(mapper.selectBySessionIdOrderByPriorityLimited(anyString(), anyLong(), anyInt()))
                    .thenReturn(Collections.emptyList());

            String result = store.buildResumeSnapshot("sess-1", 1L, 4096);

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("CRITICAL 事件排在前面")
        void criticalEvents_comeFirst() {
            AgentSessionEvent criticalEvent = new AgentSessionEvent(
                    "sess-1", 1L, AgentEventType.INTENT_CLASSIFIED, AgentEventPriority.CRITICAL,
                    "data", null, null, null, Instant.now());
            AgentSessionEvent normalEvent = new AgentSessionEvent(
                    "sess-1", 1L, AgentEventType.TOOL_CALLED, AgentEventPriority.NORMAL,
                    "data", "search", true, 100L, Instant.now());

            when(mapper.selectBySessionIdOrderByPriorityLimited("sess-1", 1L, 200))
                    .thenReturn(List.of(criticalEvent, normalEvent));
            when(payloadMapper.toIntentClassified("data")).thenReturn(
                    new IntentClassifiedPayload("DIRECT_ANSWER", 0.9, "hash"));

            String result = store.buildResumeSnapshot("sess-1", 1L, 4096);

            assertThat(result).contains("前序会话恢复");
            assertThat(result).contains("Intent: DIRECT_ANSWER");
            assertThat(result).contains("Tools used:");
        }

        @Test
        @DisplayName("包含分层结构：CRITICAL -> HIGH -> Tool count")
        void snapshot_hasLayeredStructure() {
            AgentSessionEvent criticalEvent = new AgentSessionEvent(
                    "sess-1", 1L, AgentEventType.GUARDRAIL_TRIGGERED, AgentEventPriority.CRITICAL,
                    "g-data", null, null, null, Instant.now());
            AgentSessionEvent highEvent = new AgentSessionEvent(
                    "sess-1", 1L, AgentEventType.SELF_REFLECTION, AgentEventPriority.HIGH,
                    "r-data", null, null, null, Instant.now());
            AgentSessionEvent toolEvent = new AgentSessionEvent(
                    "sess-1", 1L, AgentEventType.TOOL_CALLED, AgentEventPriority.NORMAL,
                    "t-data", "search", true, 50L, Instant.now());

            when(mapper.selectBySessionIdOrderByPriorityLimited("sess-1", 1L, 200))
                    .thenReturn(List.of(criticalEvent, highEvent, toolEvent));
            when(payloadMapper.toGuardrailTriggered("g-data")).thenReturn(
                    new GuardrailTriggeredPayload("budget", "exceeded", "stop"));
            when(payloadMapper.toSelfReflection("r-data")).thenReturn(
                    new com.smart.rag.agent.event.payload.SelfReflectionPayload(0.8, 0.6, "need_more"));

            String result = store.buildResumeSnapshot("sess-1", 1L, 4096);

            assertThat(result).contains("Guardrail: budget");
            assertThat(result).contains("Reflection:");
            assertThat(result).contains("Tools used: 1 calls total");
        }
    }

    @Nested
    @DisplayName("searchEvents")
    class SearchEvents {

        @Test
        @DisplayName("委托给 mapper.searchBySessionAndUserAndQuery")
        void delegatesToMapper() {
            List<AgentSessionEvent> expected = List.of(
                    new AgentSessionEvent("s1", 1L, AgentEventType.TOOL_CALLED,
                            AgentEventPriority.NORMAL, "data", "search", true, 100L, Instant.now())
            );
            when(mapper.searchBySessionAndUserAndQuery("sess-1", 1L, "query", 10))
                    .thenReturn(expected);

            List<AgentSessionEvent> result = store.searchEvents("sess-1", 1L, "query", 10);

            assertThat(result).hasSize(1);
            verify(mapper).searchBySessionAndUserAndQuery("sess-1", 1L, "query", 10);
        }
    }

    @Nested
    @DisplayName("record 异常容错")
    class RecordExceptionHandling {

        @Test
        @DisplayName("record() 捕获异常不抛出（事件写入失败不影响主流程）")
        void record_swallowsException() {
            doThrow(new RuntimeException("DB down")).when(mapper).insert(any(AgentSessionEvent.class));

            assertThatCode(() -> store.recordToolCall("sess-1", 1L, "search", true, "data", 100L))
                    .doesNotThrowAnyException();
            store.shutdown(); // 排空异步队列，让 worker 线程的 insert 异常被吞
        }

        @Test
        @DisplayName("recordIntentClassified 写入失败不抛出")
        void recordIntentClassified_swallowsException() {
            when(payloadMapper.toJson(any(IntentClassifiedPayload.class))).thenReturn("{\"intent\":\"test\"}");
            doThrow(new RuntimeException("DB down")).when(mapper).insert(any(AgentSessionEvent.class));

            assertThatCode(() -> store.recordIntentClassified("sess-1", 1L,
                            new IntentClassifiedPayload("DIRECT_ANSWER", 0.9, "hash")))
                    .doesNotThrowAnyException();
            store.shutdown(); // 排空异步队列，让 worker 执行 toJson + insert（异常被吞）
        }
    }
}
