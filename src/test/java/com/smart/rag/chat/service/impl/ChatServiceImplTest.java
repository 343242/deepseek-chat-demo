package com.smart.rag.chat.service.impl;

import com.smart.rag.chat.context.CagProperties;
import com.smart.rag.chat.context.RequestContextManager;
import com.smart.rag.mode.ChatRequest;
import com.smart.rag.chat.dto.ChatResponse;
import com.smart.rag.infrastructure.exception.ProviderNotFoundException;
import com.smart.rag.infrastructure.exception.ContentFilteredException;
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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("ChatServiceImpl 单元测试")
class ChatServiceImplTest {

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
    @Mock private ChatModel chatModel;

    private MockedStatic<ConversationIdUtil> conversationIdUtilMock;

    private static final Long USER_ID = 42L;
    private static final String RAW_CONV_ID = "conv-abc123";
    private static final String ISOLATED_CONV_ID = "u_42_conv-abc123";
    private static final String CANDIDATE_ID = "qwen-plus";

    private ChatServiceImpl createService() {
        return new ChatServiceImpl(
                llmRegistry, fallbackEligibility, modeRouter, usageTracker,
                conversationHelper, chatMessagePublisher, sseStreamBridge, cagContextManager,
                cagProperties, userContextProvider, teamMembershipVerifier);
    }

    private ChatRequest buildRequest() {
        return new ChatRequest(CANDIDATE_ID, "hello", RAW_CONV_ID, false, "SIMPLE", false, null);
    }

    private void setupCommonMocks(ChatRequest request) {
        when(userContextProvider.getCurrentUserId()).thenReturn(USER_ID);
        conversationIdUtilMock.when(() -> ConversationIdUtil.buildIsolatedId(USER_ID, RAW_CONV_ID))
                .thenReturn(ISOLATED_CONV_ID);
        when(modeRouter.route(request.mode())).thenReturn(modeStrategy);
        when(modeStrategy.getMode()).thenReturn(ChatMode.SIMPLE);
        doNothing().when(conversationHelper).ensureConversationExists(eq(USER_ID), eq(ISOLATED_CONV_ID), anyString());
        when(cagProperties.isEnabled()).thenReturn(false);
    }

    private ChatCapable mockChatClient(String candidateId) {
        ChatCapable client = mock(ChatCapable.class, invocation -> {
            if ("candidateId".equals(invocation.getMethod().getName())) return candidateId;
            if ("providerId".equals(invocation.getMethod().getName())) return "test-provider";
            if ("modelName".equals(invocation.getMethod().getName())) return candidateId;
            if ("isAvailable".equals(invocation.getMethod().getName())) return true;
            if ("supportsStreaming".equals(invocation.getMethod().getName())) return false;
            return null;
        });
        return client;
    }

    @AfterEach
    void tearDown() {
        if (conversationIdUtilMock != null) conversationIdUtilMock.close();
    }

    @Nested
    @DisplayName("chat (single model success)")
    class ChatSingleModel {

        @Test
        @DisplayName("chat_singleModel_delegatesToStrategy")
        void chat_singleModel_delegatesToStrategy() {
            conversationIdUtilMock = mockStatic(ConversationIdUtil.class);
            ChatRequest request = buildRequest();
            setupCommonMocks(request);

            ChatCapable client = mockChatClient(CANDIDATE_ID);
            when(llmRegistry.getUserChain(LlmCapability.CHAT, USER_ID)).thenReturn(List.of(client));

            when(modeStrategy.execute(any(StrategyExecutionContext.class)))
                    .thenReturn(StrategyExecuteResult.standard(null, "Hi there!"));

            ChatServiceImpl service = createService();
            var response = service.chat(request);

            assertEquals("Hi there!", response.content());
            assertEquals(CANDIDATE_ID, response.model());
            assertEquals(RAW_CONV_ID, response.conversationId());
            assertNull(response.fallback());

            verify(usageTracker).recordUsage(eq(ISOLATED_CONV_ID), eq(CANDIDATE_ID), anyLong());
            verify(chatMessagePublisher).publishMessageSave(eq(ISOLATED_CONV_ID),
                    eq("hello"), eq("Hi there!"), eq(CANDIDATE_ID), isNull(), anyLong());
        }
    }

    @Nested
    @DisplayName("chat (fallback)")
    class ChatFallback {

        @Test
        @DisplayName("chat_fallback_firstFails_secondSucceeds")
        void chat_fallback_firstFails_secondSucceeds() {
            conversationIdUtilMock = mockStatic(ConversationIdUtil.class);
            ChatRequest request = buildRequest();
            setupCommonMocks(request);

            String fallbackCandidate = "deepseek-v4-flash";
            ChatCapable primaryClient = mockChatClient(CANDIDATE_ID);
            ChatCapable fallbackClient = mockChatClient(fallbackCandidate);

            when(llmRegistry.getUserChain(LlmCapability.CHAT, USER_ID))
                    .thenReturn(List.of(primaryClient, fallbackClient));
            when(fallbackEligibility.isEligible(any(RuntimeException.class))).thenReturn(true);

            when(modeStrategy.execute(any(StrategyExecutionContext.class)))
                    .thenThrow(new RuntimeException("provider timeout"))
                    .thenReturn(StrategyExecuteResult.standard(null, "Fallback OK"));

            ChatServiceImpl service = createService();
            var response = service.chat(request);

            assertEquals("Fallback OK", response.content());
            assertNotNull(response.fallback());
            assertEquals(CANDIDATE_ID, response.fallback().requestedModel());
            assertTrue(response.fallback().fallback());
        }

        @Test
        @DisplayName("chat_fallback_ineligibleException_propagatesImmediately")
        void chat_fallback_ineligibleException_propagatesImmediately() {
            conversationIdUtilMock = mockStatic(ConversationIdUtil.class);
            ChatRequest request = buildRequest();
            setupCommonMocks(request);

            ChatCapable client = mockChatClient(CANDIDATE_ID);
            when(llmRegistry.getUserChain(LlmCapability.CHAT, USER_ID)).thenReturn(List.of(client));
            when(fallbackEligibility.isEligible(any(ContentFilteredException.class))).thenReturn(false);

            when(modeStrategy.execute(any(StrategyExecutionContext.class)))
                    .thenThrow(new ContentFilteredException("content filtered"));

            ChatServiceImpl service = createService();
            assertThrows(ContentFilteredException.class, () -> service.chat(request));
        }

        @Test
        @DisplayName("chat_fallback_allExhausted_throwsProviderNotFoundException")
        void chat_fallback_allExhausted_throwsProviderNotFoundException() {
            conversationIdUtilMock = mockStatic(ConversationIdUtil.class);
            ChatRequest request = buildRequest();
            setupCommonMocks(request);

            ChatCapable client1 = mockChatClient(CANDIDATE_ID);
            ChatCapable client2 = mockChatClient("deepseek-v4-flash");
            when(llmRegistry.getUserChain(LlmCapability.CHAT, USER_ID)).thenReturn(List.of(client1, client2));
            when(fallbackEligibility.isEligible(any(RuntimeException.class))).thenReturn(true);

            when(modeStrategy.execute(any(StrategyExecutionContext.class)))
                    .thenThrow(new RuntimeException("timeout"));

            ChatServiceImpl service = createService();
            assertThrows(RuntimeException.class, () -> service.chat(request));
        }
    }
}
