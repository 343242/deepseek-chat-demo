package com.smart.rag.agent.advisor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smart.rag.agent.dto.SelfReflection;
import com.smart.rag.agent.event.AgentEventStore;
import com.smart.rag.agent.event.payload.IntermediateAnswerPayload;
import com.smart.rag.agent.event.payload.RetrievalStrategyPayload;
import com.smart.rag.agent.event.payload.SelfReflectionPayload;
import com.smart.rag.agent.workspace.ToolWorkspace;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * {@link ReflectionParsingAdvisor} 单元测试。
 * <p>
 * 验证：标记解析、workspace 写入、事件 emit、字段映射、容错、cleanText 写回。
 * <p>
 * 不验证 Spring AI Advisor 链装配——那是 PoC 验证过的；此处只测 after() 的解析逻辑。
 */
@ExtendWith(MockitoExtension.class)
class ReflectionParsingAdvisorTest {

    private static final String SESSION_ID = "sess-test-1";
    private static final Long USER_ID = 42L;

    @Mock
    private AgentEventStore eventStore;

    private ToolWorkspace workspace;
    private ReflectionParsingAdvisor advisor;

    @BeforeEach
    void setUp() {
        workspace = new ToolWorkspace(USER_ID, null, SESSION_ID);
        advisor = new ReflectionParsingAdvisor(workspace, eventStore, SESSION_ID, USER_ID, new ObjectMapper());
    }

    /** 构造一个含给定文本的 ChatClientResponse */
    private static ChatClientResponse responseWithText(String text) {
        ChatResponse chat = new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
        return ChatClientResponse.builder().chatResponse(chat).build();
    }

    /** 从 after() 返回的响应中取出文本 */
    private static String textOf(ChatClientResponse resp) {
        return resp.chatResponse().getResult().getOutput().getText();
    }

    // === 核心解析 ===

    @Test
    @DisplayName("完整三标记响应：全部解析写入 workspace + emit 事件 + round 推进")
    void after_fullMarkers_parsesAllAndEmits() {
        String text = """
            让我先做原子决策。
            <atomic_decision>
            {"decision": "retrieve", "reason": "需要知识库", "fromTool": "hybridSearch", "toTool": "vectorSearch"}
            </atomic_decision>
            检索后自省：
            <reflection>
            {"isRelevant": true, "isSufficient": false, "missingAspects": ["关键词匹配"], "nextAction": "switch_tool"}
            </reflection>
            记录中间答案：
            <intermediate_answer>
            {"subQuery": "原始问题", "answer": "阶段性结论", "source": "retrieval", "citedDocIds": ["chunk-1"]}
            </intermediate_answer>
            最终回答。""";

        ChatClientResponse result = advisor.after(responseWithText(text), null);

        // workspace 写入
        assertThat(workspace.getSelfReflections()).hasSize(1);
        SelfReflection r = workspace.getSelfReflections().get(0);
        assertThat(r.isRelevant()).isTrue();
        assertThat(r.isSufficient()).isFalse();
        assertThat(r.missingAspects()).containsExactly("关键词匹配");
        assertThat(r.nextAction()).isEqualTo("switch_tool");
        assertThat(workspace.getIntermediateAnswers()).hasSize(1);
        assertThat(workspace.getIntermediateAnswers().get(0).answer()).isEqualTo("阶段性结论");
        assertThat(workspace.getRetrievalRound()).isEqualTo(1); // incrementRound 激活

        // 事件 emit
        verify(eventStore).recordSelfReflection(eq(SESSION_ID), eq(USER_ID), any(SelfReflectionPayload.class));
        verify(eventStore).recordIntermediateAnswer(eq(SESSION_ID), eq(USER_ID), any(IntermediateAnswerPayload.class));
        // switch_tool + from/to → emit RETRIEVAL_STRATEGY
        verify(eventStore).recordRetrievalStrategy(eq(SESSION_ID), eq(USER_ID), any(RetrievalStrategyPayload.class));

        // 标记被剥离，cleanText 写回
        assertThat(textOf(result)).doesNotContain("<reflection>", "<atomic_decision>", "<intermediate_answer>");
        assertThat(textOf(result)).contains("最终回答。");
    }

    @Test
    @DisplayName("部分标记（仅 reflection）：只解析存在的标记")
    void after_partialMarker_onlyParsesPresent() {
        String text = """
            检索完成。
            <reflection>
            {"isRelevant": true, "isSufficient": true, "nextAction": "proceed"}
            </reflection>""";

        ChatClientResponse result = advisor.after(responseWithText(text), null);

        assertThat(workspace.getSelfReflections()).hasSize(1);
        assertThat(workspace.getIntermediateAnswers()).isEmpty();
        verify(eventStore).recordSelfReflection(eq(SESSION_ID), eq(USER_ID), any(SelfReflectionPayload.class));
        verify(eventStore, never()).recordIntermediateAnswer(any(), any(), any());
        // proceed 不触发 RETRIEVAL_STRATEGY
        verify(eventStore, never()).recordRetrievalStrategy(any(), any(), any());
        assertThat(textOf(result)).doesNotContain("<reflection>");
    }

    @Test
    @DisplayName("无标记纯文本响应：不报错，不写 workspace，不 emit，原样返回")
    void after_noMarkers_noOp() {
        String text = "这是普通的回答，没有任何标记。";

        ChatClientResponse result = advisor.after(responseWithText(text), null);

        assertThat(workspace.getSelfReflections()).isEmpty();
        assertThat(workspace.getIntermediateAnswers()).isEmpty();
        verify(eventStore, never()).recordSelfReflection(any(), any(), any());
        verify(eventStore, never()).recordIntermediateAnswer(any(), any(), any());
        // 文本原样（无标记可剥离）
        assertThat(textOf(result)).isEqualTo(text);
    }

    // === 字段映射 ===

    @Test
    @DisplayName("reflection boolean → payload score 映射（true→1.0, false→0.0）")
    void after_reflection_fieldMapping() {
        String text = "<reflection>{\"isRelevant\": true, \"isSufficient\": false, \"nextAction\": \"rewrite_and_search\"}</reflection>";

        advisor.after(responseWithText(text), null);

        ArgumentCaptor<SelfReflectionPayload> captor = ArgumentCaptor.forClass(SelfReflectionPayload.class);
        verify(eventStore).recordSelfReflection(eq(SESSION_ID), eq(USER_ID), captor.capture());
        SelfReflectionPayload p = captor.getValue();
        assertThat(p.relevanceScore()).isEqualTo(1.0);   // isRelevant=true
        assertThat(p.completenessScore()).isEqualTo(0.0); // isSufficient=false
        assertThat(p.suggestion()).isEqualTo("rewrite_and_search");
    }

    @Test
    @DisplayName("intermediate_answer.answer → payload.answerHash 脱敏（SHA-256 前16位 hex）")
    void after_intermediateAnswer_answerHashed() {
        String text = """
            <intermediate_answer>
            {"subQuery": "q", "answer": "secret answer", "source": "parametric"}
            </intermediate_answer>""";

        advisor.after(responseWithText(text), null);

        ArgumentCaptor<IntermediateAnswerPayload> captor = ArgumentCaptor.forClass(IntermediateAnswerPayload.class);
        verify(eventStore).recordIntermediateAnswer(eq(SESSION_ID), eq(USER_ID), captor.capture());
        IntermediateAnswerPayload p = captor.getValue();
        assertThat(p.answerHash()).hasSize(16).matches("[0-9a-f]{16}");
        assertThat(p.source()).isEqualTo("parametric");
        assertThat(p.subQuery()).isEqualTo("q");
        assertThat(p.citedDocIds()).isEmpty(); // parametric 时为空
    }

    // === nextAction 与 RETRIEVAL_STRATEGY ===

    @Test
    @DisplayName("nextAction=switch_tool 但缺 from/to → 不 emit RETRIEVAL_STRATEGY")
    void after_switchToolWithoutFromTo_noStrategyEvent() {
        String text = "<reflection>{\"isRelevant\": true, \"isSufficient\": false, \"nextAction\": \"switch_tool\"}</reflection>";

        advisor.after(responseWithText(text), null);

        verify(eventStore, never()).recordRetrievalStrategy(any(), any(), any());
    }

    @Test
    @DisplayName("nextAction=switch_tool 且 atomic_decision 提供 from/to → emit RETRIEVAL_STRATEGY")
    void after_switchToolWithFromTo_emitsStrategy() {
        String text = """
            <atomic_decision>
            {"decision": "retrieve", "fromTool": "hybridSearch", "toTool": "bm25Search"}
            </atomic_decision>
            <reflection>
            {"isRelevant": true, "isSufficient": false, "missingAspects": ["关键词匹配"], "nextAction": "switch_tool"}
            </reflection>""";

        advisor.after(responseWithText(text), null);

        ArgumentCaptor<RetrievalStrategyPayload> captor = ArgumentCaptor.forClass(RetrievalStrategyPayload.class);
        verify(eventStore).recordRetrievalStrategy(eq(SESSION_ID), eq(USER_ID), captor.capture());
        RetrievalStrategyPayload p = captor.getValue();
        assertThat(p.from()).isEqualTo("hybridSearch");
        assertThat(p.to()).isEqualTo("bm25Search");
        assertThat(p.reason()).isEqualTo("关键词匹配");
    }

    // === 容错 ===

    @Nested
    @DisplayName("容错：闭源 LLM 输出不可靠")
    class FaultTolerance {

        @Test
        @DisplayName("标记内 JSON 格式错误：跳过该标记，不影响其他")
        void after_malformedJson_skipsMarker() {
            String text = """
                <reflection>{这不是合法 JSON}</reflection>
                <intermediate_answer>
                {"subQuery": "q", "answer": "ok", "source": "parametric"}
                </intermediate_answer>""";

            ChatClientResponse result = advisor.after(responseWithText(text), null);

            // reflection 被跳过
            assertThat(workspace.getSelfReflections()).isEmpty();
            verify(eventStore, never()).recordSelfReflection(any(), any(), any());
            // intermediate_answer 仍正常解析
            assertThat(workspace.getIntermediateAnswers()).hasSize(1);
            verify(eventStore).recordIntermediateAnswer(eq(SESSION_ID), eq(USER_ID), any());
            // 不抛异常，返回响应
            assertThat(textOf(result)).isNotNull();
        }

        @Test
        @DisplayName("reflection 缺 isRelevant 字段：跳过该标记")
        void after_missingRequiredField_skips() {
            String text = "<reflection>{\"isSufficient\": true, \"nextAction\": \"proceed\"}</reflection>";

            advisor.after(responseWithText(text), null);

            assertThat(workspace.getSelfReflections()).isEmpty();
            verify(eventStore, never()).recordSelfReflection(any(), any(), any());
        }

        @Test
        @DisplayName("nextAction 非法值：默认为 proceed")
        void after_invalidNextAction_defaultsToProceed() {
            String text = "<reflection>{\"isRelevant\": true, \"isSufficient\": true, \"nextAction\": \"unknown_action\"}</reflection>";

            advisor.after(responseWithText(text), null);

            assertThat(workspace.getSelfReflections().get(0).nextAction()).isEqualTo("proceed");
            verify(eventStore, never()).recordRetrievalStrategy(any(), any(), any());
        }

        @Test
        @DisplayName("intermediate_answer source 非法：跳过")
        void after_invalidSource_skips() {
            String text = "<intermediate_answer>{\"answer\": \"x\", \"source\": \"unknown\"}</intermediate_answer>";

            advisor.after(responseWithText(text), null);

            assertThat(workspace.getIntermediateAnswers()).isEmpty();
            verify(eventStore, never()).recordIntermediateAnswer(any(), any(), any());
        }

        @Test
        @DisplayName("空响应文本：直接返回不报错")
        void after_blankText_noOp() {
            ChatClientResponse resp = responseWithText("");
            ChatClientResponse result = advisor.after(resp, null);
            assertThat(result).isSameAs(resp);
        }

        @Test
        @DisplayName("多次 after() 调用：每次解析到 reflection 推进 round（单元层面验证死字段激活）")
        void after_multipleCalls_incrementRoundEachTime() {
            // 注意：PoC9 VERDICT 证实 BaseAdvisor.after() 在多轮 ReAct 中只触发一次，
            // 实际生产里单请求 after() 通常只调一次。此测试验证的是"解析到 reflection 时 round +1"的
            // 单元逻辑（死字段激活），不模拟真实 ReAct 多轮。
            String text = "<reflection>{\"isRelevant\": true, \"isSufficient\": true, \"nextAction\": \"proceed\"}</reflection>";

            advisor.after(responseWithText(text), null);
            advisor.after(responseWithText(text), null);
            advisor.after(responseWithText(text), null);

            assertThat(workspace.getRetrievalRound()).isEqualTo(3);
            verify(eventStore, times(3)).recordSelfReflection(eq(SESSION_ID), eq(USER_ID), any());
        }
    }
}
