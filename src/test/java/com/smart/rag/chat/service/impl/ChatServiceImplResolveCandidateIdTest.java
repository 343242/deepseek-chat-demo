package com.smart.rag.chat.service.impl;

import com.smart.rag.chat.context.CagProperties;
import com.smart.rag.chat.context.RequestContextManager;
import com.smart.rag.mode.ChatRequest;
import com.smart.rag.infrastructure.fallback.FallbackEligibility;
import com.smart.rag.infrastructure.llm.CapabilityClient;
import com.smart.rag.infrastructure.llm.LlmCapability;
import com.smart.rag.infrastructure.llm.registry.LlmClientRegistry;
import com.smart.rag.chat.mode.ModeRouter;
import com.smart.rag.chat.service.ChatConversationHelper;
import com.smart.rag.chat.service.ChatMessagePublisher;
import com.smart.rag.infrastructure.llm.adapter.ChatModelAssembler;
import com.smart.rag.chat.service.SseStreamBridge;
import com.smart.rag.chat.service.UserContextProvider;
import com.smart.rag.team.service.TeamMembershipVerifier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * 单测 {@link ChatServiceImpl#resolveCandidateId(ChatRequest)}。
 * <p>
 * 覆盖：
 * <ul>
 *   <li>合法 registry 候选 ID（不带 /）→ 原样返回</li>
 *   <li>provider/model 复合格式 → fail-fast 抛 IllegalArgumentException</li>
 *   <li>null / 空白 → 返回 registry 默认候选 ID</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ChatServiceImpl.resolveCandidateId fail-fast 校验")
class ChatServiceImplResolveCandidateIdTest {

    @Mock private LlmClientRegistry llmRegistry;
    @Mock private FallbackEligibility fallbackEligibility;
    @Mock private ModeRouter modeRouter;
    @Mock private ChatModelAssembler chatModelAssembler;
    @Mock private ChatConversationHelper conversationHelper;
    @Mock private ChatMessagePublisher chatMessagePublisher;
    @Mock private SseStreamBridge sseStreamBridge;
    @Mock private RequestContextManager cagContextManager;
    @Mock private CagProperties cagProperties;
    @Mock private UserContextProvider userContextProvider;
    @Mock private TeamMembershipVerifier teamMembershipVerifier;
    @Mock private CapabilityClient defaultClient;

    private static final String VALID_CANDIDATE_ID = "deepseek-v4-flash";
    private static final String DEFAULT_CANDIDATE_ID = "qwen-plus";

    private ChatServiceImpl createService() {
        return new ChatServiceImpl(llmRegistry, fallbackEligibility, modeRouter, chatModelAssembler,
        conversationHelper, chatMessagePublisher, sseStreamBridge, cagContextManager,
        cagProperties, userContextProvider, teamMembershipVerifier, org.mockito.Mockito.mock(com.smart.rag.chat.service.ActiveStreamRegistry.class), org.mockito.Mockito.mock(com.smart.rag.infrastructure.llm.metrics.LlmMetrics.class));
    }

    private ChatRequest requestWithModel(String model) {
        return new ChatRequest(model, "hello", "conv-test", false, "SIMPLE", false, null);
    }

    @Test
    @DisplayName("resolveCandidateId_withValidId_returnsId")
    void resolveCandidateId_withValidId_returnsId() {
        ChatRequest request = requestWithModel(VALID_CANDIDATE_ID);

        String result = createService().resolveCandidateId(request);

        assertThat(result).isEqualTo(VALID_CANDIDATE_ID);
    }

    @Test
    @DisplayName("resolveCandidateId_withCompoundFormat_throwsIllegalArgument")
    void resolveCandidateId_withCompoundFormat_throwsIllegalArgument() {
        String compound = "deepseek/deepseek-v4-flash";
        ChatRequest request = requestWithModel(compound);

        assertThatThrownBy(() -> createService().resolveCandidateId(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid model format: '" + compound + "'")
                .hasMessageContaining("registry candidate ID");
    }

    @Test
    @DisplayName("resolveCandidateId_withNull_returnsDefault")
    void resolveCandidateId_withNull_returnsDefault() {
        ChatRequest request = new ChatRequest(null, "hello", "conv-test", false, "SIMPLE", false, null);
        when(userContextProvider.getCurrentUserId()).thenReturn(7L);
        when(llmRegistry.getUserDefault(LlmCapability.CHAT, 7L)).thenReturn(defaultClient);
        when(defaultClient.candidateId()).thenReturn(DEFAULT_CANDIDATE_ID);

        String result = createService().resolveCandidateId(request);

        assertThat(result).isEqualTo(DEFAULT_CANDIDATE_ID);
    }

    @Test
    @DisplayName("resolveCandidateId_withBlank_returnsDefault")
    void resolveCandidateId_withBlank_returnsDefault() {
        ChatRequest request = requestWithModel("   ");
        when(userContextProvider.getCurrentUserId()).thenReturn(7L);
        when(llmRegistry.getUserDefault(LlmCapability.CHAT, 7L)).thenReturn(defaultClient);
        when(defaultClient.candidateId()).thenReturn(DEFAULT_CANDIDATE_ID);

        String result = createService().resolveCandidateId(request);

        assertThat(result).isEqualTo(DEFAULT_CANDIDATE_ID);
    }
}
