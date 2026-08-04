package com.smart.rag.admin.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smart.rag.admin.dto.AgentEventVO;
import com.smart.rag.admin.dto.TraceEventVO;
import com.smart.rag.agent.event.AgentEventMapper;
import com.smart.rag.agent.event.AgentEventPriority;
import com.smart.rag.agent.event.AgentEventType;
import com.smart.rag.agent.event.AgentSessionEvent;
import com.smart.rag.infrastructure.response.PagedResult;
import com.smart.rag.infrastructure.trace.TraceEvent;
import com.smart.rag.infrastructure.trace.TraceMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * AdminTraceService 单元测试 — 聚焦脱敏契约（documents content 剥离）和分页转换。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("AdminTraceService（管理员追踪查询）")
class AdminTraceServiceTest {

    @Mock
    private TraceMapper traceMapper;
    @Mock
    private AgentEventMapper agentEventMapper;

    private AdminTraceService service;

    @BeforeEach
    void setUp() {
        service = new AdminTraceService(traceMapper, agentEventMapper, new ObjectMapper());
    }

    @Nested
    @DisplayName("sanitizeDocuments 脱敏")
    class SanitizeDocuments {

        @Test
        @DisplayName("剥离 content 字段，保留 chunkId/score 等元数据")
        void stripsContentKeepsMetadata() {
            String json = """
                [{"chunkId":"abc-123","documentId":88,"fileName":"auth.md","content":"JWT密钥配置详情","score":0.91,"page":3}]""";

            List<Map<String, Object>> result = service.sanitizeDocuments(json);

            assertThat(result).hasSize(1);
            Map<String, Object> doc = result.get(0);
            assertThat(doc).containsKeys("chunkId", "documentId", "fileName", "score", "page");
            assertThat(doc).doesNotContainKey("content");
        }

        @Test
        @DisplayName("多个文档全部剥离 content")
        void stripsContentFromMultipleDocs() {
            String json = """
                [{"chunkId":"a","content":"text1"},{"chunkId":"b","content":"text2"}]""";

            List<Map<String, Object>> result = service.sanitizeDocuments(json);

            assertThat(result).hasSize(2);
            assertThat(result.get(0)).doesNotContainKey("content");
            assertThat(result.get(1)).doesNotContainKey("content");
        }

        @Test
        @DisplayName("null / 空字符串 → 返回 null")
        void nullOrBlank() {
            assertThat(service.sanitizeDocuments(null)).isNull();
            assertThat(service.sanitizeDocuments("")).isNull();
            assertThat(service.sanitizeDocuments("   ")).isNull();
        }

        @Test
        @DisplayName("无效 JSON → 返回 null 不抛异常")
        void invalidJson() {
            assertThat(service.sanitizeDocuments("{broken")).isNull();
        }
    }

    @Nested
    @DisplayName("listTraces 端到端脱敏")
    class ListTraces {

        @Test
        @DisplayName("返回的 VO documents 不含 content")
        @SuppressWarnings("unchecked")
        void voDocumentsRedacted() {
            TraceEvent event = new TraceEvent();
            event.setId(1L);
            event.setSessionId("sess");
            event.setUserId(1L);
            event.setStepType("VECTOR_SEARCH");
            event.setSuccess(true);
            event.setDocuments("""
                [{"chunkId":"c1","content":"敏感正文","score":0.8}]""");

            Page<TraceEvent> page = new Page<>(1, 20);
            page.setRecords(List.of(event));
            page.setTotal(1);
            when(traceMapper.selectPage(any(), any(Wrapper.class))).thenReturn(page);

            PagedResult<TraceEventVO> result = service.listTraces(1, 20, null, null, null, null);

            assertThat(result.content()).hasSize(1);
            TraceEventVO vo = result.content().get(0);
            assertThat(vo.documents()).hasSize(1);
            assertThat(vo.documents().get(0)).containsKey("chunkId");
            assertThat(vo.documents().get(0)).doesNotContainKey("content");
        }

        @Test
        @DisplayName("documents 为 null 的记录正常返回")
        @SuppressWarnings("unchecked")
        void nullDocumentsPassThrough() {
            TraceEvent event = new TraceEvent();
            event.setId(2L);
            event.setSessionId("sess");
            event.setUserId(1L);
            event.setStepType("QUERY_REWRITE");
            event.setSuccess(true);

            Page<TraceEvent> page = new Page<>(1, 20);
            page.setRecords(List.of(event));
            page.setTotal(1);
            when(traceMapper.selectPage(any(), any(Wrapper.class))).thenReturn(page);

            PagedResult<TraceEventVO> result = service.listTraces(1, 20, null, null, null, null);

            assertThat(result.content()).hasSize(1);
            assertThat(result.content().get(0).documents()).isNull();
        }
    }

    @Nested
    @DisplayName("listAgentEvents 转换")
    class ListAgentEvents {

        @Test
        @DisplayName("枚举字段正确映射为字符串/数值")
        @SuppressWarnings("unchecked")
        void enumMapping() {
            AgentSessionEvent event = new AgentSessionEvent();
            event.setId(10L);
            event.setSessionId("sess");
            event.setUserId(1L);
            event.setEventType(AgentEventType.TOOL_CALLED);
            event.setPriority(AgentEventPriority.NORMAL);
            event.setData("{\"toolName\":\"vectorSearch\"}");
            event.setToolName("vectorSearch");
            event.setSuccess(true);
            event.setDurationMs(42L);

            Page<AgentSessionEvent> page = new Page<>(1, 20);
            page.setRecords(List.of(event));
            page.setTotal(1);
            when(agentEventMapper.selectPage(any(), any(Wrapper.class))).thenReturn(page);

            PagedResult<AgentEventVO> result = service.listAgentEvents(1, 20, null, null, null);

            assertThat(result.content()).hasSize(1);
            AgentEventVO vo = result.content().get(0);
            assertThat(vo.eventType()).isEqualTo("TOOL_CALLED");
            assertThat(vo.priority()).isEqualTo(3);
            assertThat(vo.toolName()).isEqualTo("vectorSearch");
            assertThat(vo.success()).isTrue();
        }
    }
}
