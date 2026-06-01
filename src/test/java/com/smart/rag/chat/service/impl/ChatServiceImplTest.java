package com.smart.rag.chat.service.impl;

import com.smart.rag.infrastructure.client.ChatClientRegistry;
import com.smart.rag.chat.context.CagProperties;
import com.smart.rag.chat.context.RequestContextManager;
import com.smart.rag.chat.dto.ChatRequest;
import com.smart.rag.chat.dto.ChatResponse;
import com.smart.rag.infrastructure.fallback.ChatFallbackProperties;
import com.smart.rag.infrastructure.fallback.ModelCircuitBreakerRegistry;
import com.smart.rag.infrastructure.fallback.FallbackChainProvider;
import com.smart.rag.infrastructure.fallback.FallbackEligibility;
import com.smart.rag.infrastructure.fallback.StreamRetryHandler;
import com.smart.rag.chat.service.SseStreamBridge;
import com.smart.rag.chat.mode.ChatMode;
import com.smart.rag.chat.mode.ChatModeStrategy;
import com.smart.rag.chat.mode.ModeRouter;
import com.smart.rag.infrastructure.provider.ModelRouter;
import com.smart.rag.chat.service.ChatConversationHelper;
import com.smart.rag.chat.service.ChatUsageTracker;
import com.smart.rag.chat.service.StrategyExecuteResult;
import com.smart.rag.chat.service.StrategyExecutionContext;
import com.smart.rag.infrastructure.exception.errorcode.ErrorCode;
import com.smart.rag.common.util.ConversationIdUtil;
import com.smart.rag.infrastructure.exception.BusinessException;
import com.smart.rag.infrastructure.web.util.SecurityUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * ChatServiceImpl 单元测试
 * <p>
 * 测试阻塞式聊天的模式路由、降级链行为。
 * SecurityUtils.getCurrentUserId() 使用 MockedStatic 模拟。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ChatServiceImpl 单元测试")
class ChatServiceImplTest {

    @Mock private ChatClientRegistry registry;
    @Mock private ModelRouter modelRouter;
    @Mock private ModeRouter modeRouter;
    @Mock private ChatUsageTracker usageTracker;
    @Mock private ChatConversationHelper conversationHelper;
    @Mock private ChatFallbackProperties fallbackProperties;
    @Mock private FallbackChainProvider fallbackChainProvider;
    @Mock private FallbackEligibility fallbackEligibility;
    @Mock private StreamRetryHandler streamRetryHandler;
    @Mock private ModelCircuitBreakerRegistry circuitBreakers;
    @Mock private SseStreamBridge sseStreamBridge;
    @Mock private RequestContextManager cagContextManager;
    @Mock private CagProperties cagProperties;
    @Mock private ChatClient chatClient;
    @Mock private ChatModeStrategy modeStrategy;

    private MockedStatic<SecurityUtils> securityUtilsMock;
    private MockedStatic<ConversationIdUtil> conversationIdUtilMock;

    private static final Long USER_ID = 42L;
    private static final String RAW_CONV_ID = "conv-abc123";
    private static final String ISOLATED_CONV_ID = "u_42_conv-abc123";
    private static final String MODEL_ID = "deepseek/deepseek-chat";

    private ChatServiceImpl createService() {
        return new ChatServiceImpl(
                registry, modelRouter, modeRouter, usageTracker, conversationHelper,
                fallbackProperties, fallbackChainProvider, fallbackEligibility,
                streamRetryHandler, circuitBreakers, sseStreamBridge, cagContextManager, cagProperties);
    }

    private void setupCommonMocks(ChatRequest request) {
        securityUtilsMock.when(SecurityUtils::getCurrentUserId).thenReturn(USER_ID);
        conversationIdUtilMock.when(() -> ConversationIdUtil.buildIsolatedId(USER_ID, RAW_CONV_ID))
                .thenReturn(ISOLATED_CONV_ID);

        when(modeRouter.route(request.mode())).thenReturn(modeStrategy);
        when(modeStrategy.getMode()).thenReturn(ChatMode.SIMPLE);

        setupModelMocks(request.model(), "deepseek", "deepseek-chat");

        doNothing().when(conversationHelper).ensureConversationExists(eq(USER_ID), eq(ISOLATED_CONV_ID), anyString());
        when(cagProperties.isEnabled()).thenReturn(false);
    }

    private void setupRequestContextOnly(ChatRequest request) {
        securityUtilsMock.when(SecurityUtils::getCurrentUserId).thenReturn(USER_ID);
        conversationIdUtilMock.when(() -> ConversationIdUtil.buildIsolatedId(USER_ID, RAW_CONV_ID))
                .thenReturn(ISOLATED_CONV_ID);

        when(modeRouter.route(request.mode())).thenReturn(modeStrategy);
        when(modeStrategy.getMode()).thenReturn(ChatMode.SIMPLE);
        doNothing().when(conversationHelper).ensureConversationExists(eq(USER_ID), eq(ISOLATED_CONV_ID), anyString());
        when(cagProperties.isEnabled()).thenReturn(false);
    }

    private void setupModelMocks(String requestedModel, String providerId, String modelId) {
        ModelRouter.Route route = new ModelRouter.Route(providerId, modelId);
        String compositeId = providerId + "/" + modelId;
        when(modelRouter.resolve(requestedModel)).thenReturn(route);
        when(registry.get(compositeId)).thenReturn(chatClient);
    }

    private StrategyExecuteResult buildStandardResult(String content) {
        return StrategyExecuteResult.standard(null, content);
    }

    @AfterEach
    void tearDown() {
        if (securityUtilsMock != null) securityUtilsMock.close();
        if (conversationIdUtilMock != null) conversationIdUtilMock.close();
    }

    @Nested
    @DisplayName("chat (fallback disabled)")
    class ChatFallbackDisabled {

        @Test
        @DisplayName("chat_fallbackDisabled_delegatesToStrategy")
        void chat_fallbackDisabled_delegatesToStrategy() {
            securityUtilsMock = mockStatic(SecurityUtils.class);
            conversationIdUtilMock = mockStatic(ConversationIdUtil.class);

            ChatRequest request = new ChatRequest(MODEL_ID, "hello", RAW_CONV_ID, false, "SIMPLE", false, null);
            setupCommonMocks(request);

            String reply = "Hi there!";
            when(modeStrategy.execute(any(StrategyExecutionContext.class)))
                    .thenReturn(buildStandardResult(reply));

            when(fallbackProperties.enabled()).thenReturn(false);

            ChatServiceImpl service = createService();
            ChatResponse response = service.chat(request);

            assertEquals(reply, response.content());
            assertEquals(MODEL_ID, response.model());
            assertEquals(RAW_CONV_ID, response.conversationId());
            assertNull(response.fallback());

            verify(usageTracker).recordUsage(eq(ISOLATED_CONV_ID), eq(MODEL_ID),
                    anyLong());
            verify(conversationHelper).saveMessagesAndNotify(eq(ISOLATED_CONV_ID),
                    eq("hello"), eq(reply), eq(MODEL_ID), isNull(), anyLong());
        }
    }

    @Nested
    @DisplayName("chat (fallback enabled)")
    class ChatFallbackEnabled {

        @Test
        @DisplayName("chat_fallbackEnabled_successOnFirstModel")
        void chat_fallbackEnabled_successOnFirstModel() {
            securityUtilsMock = mockStatic(SecurityUtils.class);
            conversationIdUtilMock = mockStatic(ConversationIdUtil.class);

            ChatRequest request = new ChatRequest(MODEL_ID, "hello", RAW_CONV_ID, false, "SIMPLE", false, null);
            setupCommonMocks(request);

            when(modeStrategy.execute(any(StrategyExecutionContext.class)))
                    .thenReturn(buildStandardResult("OK"));
            when(fallbackProperties.enabled()).thenReturn(true);
            when(fallbackChainProvider.resolve(MODEL_ID)).thenReturn(List.of(MODEL_ID));
            when(circuitBreakers.isCallAllowed(MODEL_ID)).thenReturn(true);

            ChatServiceImpl service = createService();
            ChatResponse response = service.chat(request);

            assertEquals("OK", response.content());
            assertNull(response.fallback());
            verify(circuitBreakers).recordSuccess(MODEL_ID);
        }

        @Test
        @DisplayName("chat_fallbackEnabled_firstFails_secondSucceeds")
        void chat_fallbackEnabled_firstFails_secondSucceeds() {
            securityUtilsMock = mockStatic(SecurityUtils.class);
            conversationIdUtilMock = mockStatic(ConversationIdUtil.class);

            ChatRequest request = new ChatRequest(MODEL_ID, "hello", RAW_CONV_ID, false, "SIMPLE", false, null);
            setupCommonMocks(request);

            String fallbackModel = "zhipu/glm-4-flash";
            ModelRouter.Route fallbackRoute = new ModelRouter.Route("zhipu", "glm-4-flash");
            when(modelRouter.resolve(fallbackModel)).thenReturn(fallbackRoute);
            when(registry.get("zhipu/glm-4-flash")).thenReturn(chatClient);

            when(modeStrategy.execute(any(StrategyExecutionContext.class)))
                    .thenThrow(new RuntimeException("provider timeout"))
                    .thenReturn(buildStandardResult("Fallback OK"));

            when(fallbackProperties.enabled()).thenReturn(true);
            when(fallbackChainProvider.resolve(MODEL_ID))
                    .thenReturn(List.of(MODEL_ID, fallbackModel));
            when(fallbackEligibility.isEligible(any(RuntimeException.class))).thenReturn(true);
            when(circuitBreakers.isCallAllowed(MODEL_ID)).thenReturn(true);
            when(circuitBreakers.isCallAllowed(fallbackModel)).thenReturn(true);

            ChatServiceImpl service = createService();
            ChatResponse response = service.chat(request);

            assertEquals("Fallback OK", response.content());
            assertNotNull(response.fallback());
            assertEquals(MODEL_ID, response.fallback().requestedModel());
            assertTrue(response.fallback().fallback());
            verify(circuitBreakers).recordFailure(MODEL_ID);
            verify(circuitBreakers).recordSuccess(fallbackModel);
        }

        @Test
        @DisplayName("chat_fallbackEnabled_openBreaker_skipsModelAndUsesNextCandidate")
        void chat_fallbackEnabled_openBreaker_skipsModelAndUsesNextCandidate() {
            securityUtilsMock = mockStatic(SecurityUtils.class);
            conversationIdUtilMock = mockStatic(ConversationIdUtil.class);

            ChatRequest request = new ChatRequest(MODEL_ID, "hello", RAW_CONV_ID, false, "SIMPLE", false, null);
            String fallbackModel = "zhipu/glm-4-flash";
            setupRequestContextOnly(request);
            setupModelMocks(fallbackModel, "zhipu", "glm-4-flash");
            when(modeStrategy.execute(any(StrategyExecutionContext.class)))
                    .thenReturn(buildStandardResult("Fallback OK"));
            when(fallbackProperties.enabled()).thenReturn(true);
            when(fallbackChainProvider.resolve(MODEL_ID))
                    .thenReturn(List.of(MODEL_ID, fallbackModel));
            when(circuitBreakers.isCallAllowed(MODEL_ID)).thenReturn(false);
            when(circuitBreakers.isCallAllowed(fallbackModel)).thenReturn(true);

            ChatServiceImpl service = createService();
            ChatResponse response = service.chat(request);

            assertEquals("Fallback OK", response.content());
            assertNotNull(response.fallback());
            assertEquals(MODEL_ID, response.fallback().requestedModel());
            verify(modelRouter, never()).resolve(MODEL_ID);
            verify(circuitBreakers, never()).recordFailure(MODEL_ID);
            verify(circuitBreakers).recordSuccess(fallbackModel);
        }

        @Test
        @DisplayName("chat_fallbackEnabled_ineligibleException_propagatesImmediately")
        void chat_fallbackEnabled_ineligibleException_propagatesImmediately() {
            securityUtilsMock = mockStatic(SecurityUtils.class);
            conversationIdUtilMock = mockStatic(ConversationIdUtil.class);

            ChatRequest request = new ChatRequest(MODEL_ID, "hello", RAW_CONV_ID, false, "SIMPLE", false, null);
            setupCommonMocks(request);

            when(modeStrategy.execute(any(StrategyExecutionContext.class)))
                    .thenThrow(new BusinessException(ErrorCode.CONTENT_FILTERED));

            when(fallbackProperties.enabled()).thenReturn(true);
            when(fallbackChainProvider.resolve(MODEL_ID))
                    .thenReturn(List.of(MODEL_ID, "zhipu/glm-4-flash"));
            when(fallbackEligibility.isEligible(any(BusinessException.class))).thenReturn(false);
            when(circuitBreakers.isCallAllowed(MODEL_ID)).thenReturn(true);

            ChatServiceImpl service = createService();
            assertThrows(BusinessException.class, () -> service.chat(request));
            verify(circuitBreakers, never()).recordFailure(MODEL_ID);
        }

        @Test
        @DisplayName("chat_fallbackEnabled_allExhausted_throwsBusinessException")
        void chat_fallbackEnabled_allExhausted_throwsBusinessException() {
            securityUtilsMock = mockStatic(SecurityUtils.class);
            conversationIdUtilMock = mockStatic(ConversationIdUtil.class);

            ChatRequest request = new ChatRequest(MODEL_ID, "hello", RAW_CONV_ID, false, "SIMPLE", false, null);
            setupCommonMocks(request);
            setupModelMocks("zhipu/glm-4-flash", "zhipu", "glm-4-flash");

            when(modeStrategy.execute(any(StrategyExecutionContext.class)))
                    .thenThrow(new RuntimeException("timeout"));

            when(fallbackProperties.enabled()).thenReturn(true);
            when(fallbackChainProvider.resolve(MODEL_ID))
                    .thenReturn(List.of(MODEL_ID, "zhipu/glm-4-flash"));
            when(fallbackEligibility.isEligible(any(RuntimeException.class))).thenReturn(true);
            when(circuitBreakers.isCallAllowed(anyString())).thenReturn(true);

            ChatServiceImpl service = createService();
            BusinessException ex = assertThrows(BusinessException.class, () -> service.chat(request));
            assertEquals(ErrorCode.PROVIDER_NOT_FOUND, ex.getErrorCode());
            verify(circuitBreakers).recordFailure(MODEL_ID);
            verify(circuitBreakers).recordFailure("zhipu/glm-4-flash");
        }
    }
}
