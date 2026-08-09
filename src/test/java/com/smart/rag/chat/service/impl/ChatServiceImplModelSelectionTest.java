package com.smart.rag.chat.service.impl;

import com.smart.rag.chat.context.CagProperties;
import com.smart.rag.chat.context.RequestContextManager;
import com.smart.rag.mode.ChatRequest;
import com.smart.rag.chat.dto.ChatResponse;
import com.smart.rag.infrastructure.exception.ClientException;
import com.smart.rag.infrastructure.exception.errorcode.ClientErrorCode;
import com.smart.rag.infrastructure.fallback.FallbackEligibility;
import com.smart.rag.infrastructure.llm.CapabilityClient;
import com.smart.rag.infrastructure.llm.ChatCapable;
import com.smart.rag.infrastructure.llm.LlmCapability;
import com.smart.rag.infrastructure.llm.registry.LlmClientRegistry;
import com.smart.rag.chat.service.SseStreamBridge;
import com.smart.rag.chat.service.UserContextProvider;
import com.smart.rag.mode.ChatMode;
import com.smart.rag.mode.ChatModeStrategy;
import com.smart.rag.chat.mode.ModeRouter;
import com.smart.rag.chat.service.ChatConversationHelper;
import com.smart.rag.chat.service.ChatMessagePublisher;
import com.smart.rag.chat.service.ChatUsageTracker;
import com.smart.rag.mode.StrategyExecuteResult;
import com.smart.rag.mode.StrategyExecutionContext;
import com.smart.rag.common.util.ConversationIdUtil;
import com.smart.rag.team.service.TeamMembershipVerifier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Tests for BYOK model selection: user-requested model should be prioritized in fallback chain.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ChatServiceImpl — BYOK model selection")
class ChatServiceImplModelSelectionTest {

    @Mock private LlmClientRegistry llmRegistry;
    @Mock private FallbackEligibility fallbackEligibility;
    @Mock private ModeRouter modeRouter;
    @Mock private ChatUsageTracker usageTracker;
    @Mock private ChatConversationHelper conversationHelper;
    @Mock private ChatMessagePublisher chatMessagePublisher;
    @Mock private SseStreamBridge sseStreamBridge;
    @Mock private RequestContextManager cagContextManager;
    @Mock private CagProperties cagProperties;
    @Mock private UserContextProvider userContextProvider;
    @Mock private TeamMembershipVerifier teamMembershipVerifier;
    @Mock private ChatModeStrategy modeStrategy;

    private MockedStatic<ConversationIdUtil> conversationIdUtilMock;

    private static final Long USER_ID = 42L;
    private static final String RAW_CONV_ID = "conv-abc123";
    private static final String ISOLATED_CONV_ID = "u_42_conv-abc123";

    @BeforeEach
    void setUp() {
        conversationIdUtilMock = mockStatic(ConversationIdUtil.class);
        conversationIdUtilMock.when(() -> ConversationIdUtil.buildIsolatedId(USER_ID, RAW_CONV_ID))
                .thenReturn(ISOLATED_CONV_ID);
    }

    @AfterEach
    void tearDown() {
        if (conversationIdUtilMock != null) conversationIdUtilMock.close();
    }

    private ChatServiceImpl createService() {
        return new ChatServiceImpl(llmRegistry, fallbackEligibility, modeRouter, usageTracker,
        conversationHelper, chatMessagePublisher, sseStreamBridge, cagContextManager,
        cagProperties, userContextProvider, teamMembershipVerifier, org.mockito.Mockito.mock(com.smart.rag.chat.service.ActiveStreamRegistry.class), org.mockito.Mockito.mock(com.smart.rag.infrastructure.llm.metrics.LlmMetrics.class));
    }

    private void setupCommonMocks(String requestedModel) {
        when(userContextProvider.getCurrentUserId()).thenReturn(USER_ID);
        when(modeRouter.route(any())).thenReturn(modeStrategy);
        when(modeStrategy.getMode()).thenReturn(ChatMode.SIMPLE);
        doNothing().when(conversationHelper).ensureConversationExists(eq(USER_ID), eq(ISOLATED_CONV_ID), anyString());
        when(cagProperties.isEnabled()).thenReturn(false);
    }

    private ChatCapable mockChatClient(String candidateId) {
        ChatCapable client = mock(ChatCapable.class, invocation -> {
            if ("candidateId".equals(invocation.getMethod().getName())) return candidateId;
            if ("providerId".equals(invocation.getMethod().getName())) return "test-provider";
            if ("modelName".equals(invocation.getMethod().getName())) return candidateId;
            if ("capability".equals(invocation.getMethod().getName())) return LlmCapability.CHAT;
            if ("isAvailable".equals(invocation.getMethod().getName())) return true;
            if ("supportsStreaming".equals(invocation.getMethod().getName())) return false;
            return null;
        });
        return client;
    }

    @Test
    @DisplayName("User-requested model is prioritized to chain head even if BYOK chain has different order")
    void requestedModel_prioritizedToChainHead() {
        String requestedModel = "deepseek-v4-flash";
        String byokDefault = "qwen-plus";

        ChatRequest request = new ChatRequest(requestedModel, "hello", RAW_CONV_ID, false, "SIMPLE", false, null);
        setupCommonMocks(requestedModel);

        // BYOK chain has qwen-plus first, deepseek-v4-flash second
        ChatCapable byokClient = mockChatClient(byokDefault);
        ChatCapable requestedClient = mockChatClient(requestedModel);
        when(llmRegistry.getUserChain(LlmCapability.CHAT, USER_ID))
                .thenReturn(List.of(byokClient, requestedClient));
        // find() returns the requested client (it exists in registry)
        when(llmRegistry.find(requestedModel)).thenReturn(requestedClient);

        when(modeStrategy.execute(any(StrategyExecutionContext.class)))
                .thenReturn(StrategyExecuteResult.standard(null, "Response from deepseek"));

        ChatServiceImpl service = createService();
        ChatResponse response = service.chat(request);

        // The requested model should be used (no fallback metadata)
        assertEquals("Response from deepseek", response.content());
        assertNull(response.fallback(), "Requested model should be used directly, no fallback");
        assertEquals(requestedModel, response.model());
    }

    @Test
    @DisplayName("Requested model not in BYOK chain but in registry is prepended")
    void requestedModel_notInChain_prepended() {
        String requestedModel = "deepseek-v4-flash";
        String byokDefault = "qwen-plus";

        ChatRequest request = new ChatRequest(requestedModel, "hello", RAW_CONV_ID, false, "SIMPLE", false, null);
        setupCommonMocks(requestedModel);

        // BYOK chain only has qwen-plus
        ChatCapable byokClient = mockChatClient(byokDefault);
        ChatCapable requestedClient = mockChatClient(requestedModel);
        when(llmRegistry.getUserChain(LlmCapability.CHAT, USER_ID))
                .thenReturn(List.of(byokClient));
        // find() returns the requested client (it exists in registry but not in BYOK chain)
        when(llmRegistry.find(requestedModel)).thenReturn(requestedClient);

        when(modeStrategy.execute(any(StrategyExecutionContext.class)))
                .thenReturn(StrategyExecuteResult.standard(null, "Response from deepseek"));

        ChatServiceImpl service = createService();
        ChatResponse response = service.chat(request);

        assertEquals("Response from deepseek", response.content());
        assertNull(response.fallback(), "Requested model should be prepended and used");
        assertEquals(requestedModel, response.model());
    }

    @Test
    @DisplayName("Invalid requested model falls back to BYOK chain")
    void requestedModel_invalid_fallsBackToByokChain() {
        String requestedModel = "nonexistent-model";
        String byokDefault = "qwen-plus";

        ChatRequest request = new ChatRequest(requestedModel, "hello", RAW_CONV_ID, false, "SIMPLE", false, null);
        setupCommonMocks(requestedModel);

        ChatCapable byokClient = mockChatClient(byokDefault);
        when(llmRegistry.getUserChain(LlmCapability.CHAT, USER_ID))
                .thenReturn(List.of(byokClient));
        // find() returns null (model doesn't exist in registry)
        when(llmRegistry.find(requestedModel)).thenReturn(null);

        when(modeStrategy.execute(any(StrategyExecutionContext.class)))
                .thenReturn(StrategyExecuteResult.standard(null, "Fallback response"));

        ChatServiceImpl service = createService();
        ChatResponse response = service.chat(request);

        assertEquals("Fallback response", response.content());
        // Should fallback since requested model doesn't exist
        assertNotNull(response.fallback());
        assertEquals(requestedModel, response.fallback().requestedModel());
        assertTrue(response.fallback().fallback());
    }

    @Test
    @DisplayName("Requested model already at chain head - no reordering")
    void requestedModel_alreadyAtHead_noReorder() {
        String requestedModel = "deepseek-v4-flash";

        ChatRequest request = new ChatRequest(requestedModel, "hello", RAW_CONV_ID, false, "SIMPLE", false, null);
        setupCommonMocks(requestedModel);

        // Chain already has requested model first
        ChatCapable requestedClient = mockChatClient(requestedModel);
        ChatCapable otherClient = mockChatClient("qwen-plus");
        when(llmRegistry.getUserChain(LlmCapability.CHAT, USER_ID))
                .thenReturn(List.of(requestedClient, otherClient));
        // find() should NOT be called since model is already at head

        when(modeStrategy.execute(any(StrategyExecutionContext.class)))
                .thenReturn(StrategyExecuteResult.standard(null, "Direct response"));

        ChatServiceImpl service = createService();
        ChatResponse response = service.chat(request);

        assertEquals("Direct response", response.content());
        assertNull(response.fallback());
        // Verify find() was not called (optimization)
        verify(llmRegistry, never()).find(anyString());
    }

    /**
     * 构造一个指定 capability 的 CapabilityClient mock（非 ChatCapable）。
     * 用于测试用户传 embedding/reranking candidateId 被拒绝。
     */
    private CapabilityClient mockClientWithCapability(String candidateId, LlmCapability capability) {
        CapabilityClient client = mock(CapabilityClient.class, invocation -> {
            switch (invocation.getMethod().getName()) {
                case "candidateId": return candidateId;
                case "providerId": return "test-provider";
                case "modelName": return candidateId;
                case "capability": return capability;
                case "isAvailable": return true;
                default: return null;
            }
        });
        return client;
    }

    @Test
    @DisplayName("Requested embedding model is rejected with MODEL_CAPABILITY_NOT_CHAT")
    void requestedModel_embeddingCapability_rejected() {
        String embeddingModel = "bailian-embed";
        String byokDefault = "qwen-plus";

        ChatRequest request = new ChatRequest(embeddingModel, "hello", RAW_CONV_ID, false, "SIMPLE", false, null);
        setupCommonMocks(embeddingModel);

        ChatCapable byokClient = mockChatClient(byokDefault);
        when(llmRegistry.getUserChain(LlmCapability.CHAT, USER_ID))
                .thenReturn(List.of(byokClient));
        // find() 返回一个 EMBEDDING 能力的 client（前端被绕过后直传 embedding id）
        when(llmRegistry.find(embeddingModel))
                .thenReturn(mockClientWithCapability(embeddingModel, LlmCapability.EMBEDDING));

        ChatServiceImpl service = createService();

        ClientException ex = assertThrows(ClientException.class, () -> service.chat(request));
        assertEquals(ClientErrorCode.MODEL_CAPABILITY_NOT_CHAT.getCode(), ex.getErrorCode().getCode());
        // 不应进入 strategy 执行
        verify(modeStrategy, never()).execute(any(StrategyExecutionContext.class));
    }

    @Test
    @DisplayName("Requested reranking model is rejected with MODEL_CAPABILITY_NOT_CHAT")
    void requestedModel_rerankingCapability_rejected() {
        String rerankingModel = "bge-reranker";
        String byokDefault = "qwen-plus";

        ChatRequest request = new ChatRequest(rerankingModel, "hello", RAW_CONV_ID, false, "SIMPLE", false, null);
        setupCommonMocks(rerankingModel);

        ChatCapable byokClient = mockChatClient(byokDefault);
        when(llmRegistry.getUserChain(LlmCapability.CHAT, USER_ID))
                .thenReturn(List.of(byokClient));
        when(llmRegistry.find(rerankingModel))
                .thenReturn(mockClientWithCapability(rerankingModel, LlmCapability.RERANKING));

        ChatServiceImpl service = createService();

        ClientException ex = assertThrows(ClientException.class, () -> service.chat(request));
        assertEquals(ClientErrorCode.MODEL_CAPABILITY_NOT_CHAT.getCode(), ex.getErrorCode().getCode());
        verify(modeStrategy, never()).execute(any(StrategyExecutionContext.class));
    }
}
