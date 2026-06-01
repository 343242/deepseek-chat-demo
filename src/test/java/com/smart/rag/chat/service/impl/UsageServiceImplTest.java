package com.smart.rag.chat.service.impl;

import com.smart.rag.chat.dto.TokenUsageDTO;
import com.smart.rag.chat.dto.UsageStats;
import com.smart.rag.chat.entity.TokenUsage;
import com.smart.rag.chat.mapper.TokenUsageMapper;
import com.smart.rag.exception.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionTemplate;
import com.smart.rag.common.conversation.ConversationIdUtil;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * UsageServiceImpl 单元测试
 * <p>
 * 测试 getRecords 参数路由、statsByModel/statsByConversation 委托逻辑。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("UsageServiceImpl 单元测试")
class UsageServiceImplTest {

    @Mock
    private TokenUsageMapper mapper;

    @Mock
    private TransactionTemplate transactionTemplate;

    private static final Long USER_ID = 42L;
    private static final String CONVERSATION = "conv-abc123";
    private static final String ISOLATED_CONV_ID = "u_42_conv-abc123";
    private static final String LIKE_PREFIX = "u_42_%";
    private static final String MODEL_ID = "deepseek-chat";

    private UsageServiceImpl createService() {
        return new UsageServiceImpl(mapper, transactionTemplate);
    }

    private TokenUsage buildEntity(String convId, String modelId) {
        TokenUsage entity = new TokenUsage(convId, modelId, 100L, 50L, 150L, 200L);
        return entity;
    }

    @Nested
    @DisplayName("getRecords")
    class GetRecords {

        @Test
        @DisplayName("getRecords_withConversation_buildsIsolatedId")
        void getRecords_withConversation_buildsIsolatedId() {
            try (MockedStatic<ConversationIdUtil> mock = mockStatic(ConversationIdUtil.class)) {
                mock.when(() -> ConversationIdUtil.buildIsolatedId(USER_ID, CONVERSATION))
                        .thenReturn(ISOLATED_CONV_ID);

                TokenUsage entity = buildEntity(ISOLATED_CONV_ID, MODEL_ID);
                when(mapper.selectByConversationId(ISOLATED_CONV_ID))
                        .thenReturn(List.of(entity));

                UsageServiceImpl svc = createService();
                List<TokenUsageDTO> result = svc.getRecords(USER_ID, CONVERSATION, null);

                assertEquals(1, result.size());
                assertEquals(ISOLATED_CONV_ID, result.get(0).conversationId());
                mock.verify(() -> ConversationIdUtil.buildIsolatedId(USER_ID, CONVERSATION));
            }
        }

        @Test
        @DisplayName("getRecords_withModel_buildsLikePrefix")
        void getRecords_withModel_buildsLikePrefix() {
            try (MockedStatic<ConversationIdUtil> mock = mockStatic(ConversationIdUtil.class)) {
                mock.when(() -> ConversationIdUtil.buildLikePrefix(USER_ID))
                        .thenReturn(LIKE_PREFIX);

                TokenUsage entity = buildEntity("u_42_some-conv", MODEL_ID);
                when(mapper.selectByModelAndUserPrefix(MODEL_ID, LIKE_PREFIX))
                        .thenReturn(List.of(entity));

                UsageServiceImpl svc = createService();
                List<TokenUsageDTO> result = svc.getRecords(USER_ID, null, MODEL_ID);

                assertEquals(1, result.size());
                mock.verify(() -> ConversationIdUtil.buildLikePrefix(USER_ID));
            }
        }

        @Test
        @DisplayName("getRecords_withoutConversationOrModel_throwsUsageParamMissing")
        void getRecords_withoutConversationOrModel_throwsUsageParamMissing() {
            UsageServiceImpl svc = createService();

            BusinessException ex = assertThrows(BusinessException.class,
                    () -> svc.getRecords(USER_ID, null, null));
            assertTrue(ex.getMessage().contains("model") || ex.getMessage().contains("conversation"));
        }

        @Test
        @DisplayName("getRecords_blankConversationAndModel_throwsUsageParamMissing")
        void getRecords_blankConversationAndModel_throwsUsageParamMissing() {
            UsageServiceImpl svc = createService();

            assertThrows(BusinessException.class,
                    () -> svc.getRecords(USER_ID, "   ", "   "));
        }

        @Test
        @DisplayName("getRecords_conversationTakesPriorityOverModel")
        void getRecords_conversationTakesPriorityOverModel() {
            try (MockedStatic<ConversationIdUtil> mock = mockStatic(ConversationIdUtil.class)) {
                mock.when(() -> ConversationIdUtil.buildIsolatedId(USER_ID, CONVERSATION))
                        .thenReturn(ISOLATED_CONV_ID);

                when(mapper.selectByConversationId(ISOLATED_CONV_ID))
                        .thenReturn(List.of());

                UsageServiceImpl svc = createService();
                svc.getRecords(USER_ID, CONVERSATION, MODEL_ID);

                // Only buildIsolatedId should be called, not buildLikePrefix
                mock.verify(() -> ConversationIdUtil.buildIsolatedId(USER_ID, CONVERSATION));
                mock.verify(() -> ConversationIdUtil.buildLikePrefix(anyLong()), never());
                verify(mapper).selectByConversationId(ISOLATED_CONV_ID);
                verify(mapper, never()).selectByModelAndUserPrefix(anyString(), anyString());
            }
        }
    }

    @Nested
    @DisplayName("statsByModel")
    class StatsByModel {

        @Test
        @DisplayName("statsByModel_delegatesToAggregateByModelForUser")
        void statsByModel_delegatesToAggregateByModelForUser() {
            try (MockedStatic<ConversationIdUtil> mock = mockStatic(ConversationIdUtil.class)) {
                mock.when(() -> ConversationIdUtil.buildLikePrefix(USER_ID))
                        .thenReturn(LIKE_PREFIX);

                UsageStats stats = new UsageStats(MODEL_ID, 10, 1000L, 500L, 1500L, 150.0);
                when(mapper.aggregateByModelForUser(eq(MODEL_ID), eq(LIKE_PREFIX), any(), isNull()))
                        .thenReturn(List.of(stats));

                UsageServiceImpl svc = createService();
                List<UsageStats> result = svc.statsByModel(USER_ID, MODEL_ID, null, null);

                assertEquals(1, result.size());
                assertEquals(MODEL_ID, result.get(0).groupKey());
                assertEquals(10, result.get(0).requestCount());
                mock.verify(() -> ConversationIdUtil.buildLikePrefix(USER_ID));
            }
        }
    }

    @Nested
    @DisplayName("statsByConversation")
    class StatsByConversation {

        @Test
        @DisplayName("statsByConversation_withConversation_delegatesToAggregateByConversation")
        void statsByConversation_withConversation_delegatesToAggregateByConversation() {
            try (MockedStatic<ConversationIdUtil> mock = mockStatic(ConversationIdUtil.class)) {
                mock.when(() -> ConversationIdUtil.buildIsolatedId(USER_ID, CONVERSATION))
                        .thenReturn(ISOLATED_CONV_ID);

                UsageStats stats = new UsageStats(ISOLATED_CONV_ID, 5, 500L, 250L, 750L, 120.0);
                when(mapper.aggregateByConversation(eq(ISOLATED_CONV_ID), any(), isNull()))
                        .thenReturn(List.of(stats));

                UsageServiceImpl svc = createService();
                List<UsageStats> result = svc.statsByConversation(USER_ID, CONVERSATION, null, null);

                assertEquals(1, result.size());
                assertEquals(ISOLATED_CONV_ID, result.get(0).groupKey());
                mock.verify(() -> ConversationIdUtil.buildIsolatedId(USER_ID, CONVERSATION));
            }
        }

        @Test
        @DisplayName("statsByConversation_withoutConversation_delegatesToAggregateByUserConversations")
        void statsByConversation_withoutConversation_delegatesToAggregateByUserConversations() {
            try (MockedStatic<ConversationIdUtil> mock = mockStatic(ConversationIdUtil.class)) {
                mock.when(() -> ConversationIdUtil.buildLikePrefix(USER_ID))
                        .thenReturn(LIKE_PREFIX);

                UsageStats stats = new UsageStats("u_42_conv-1", 3, 300L, 150L, 450L, 100.0);
                when(mapper.aggregateByUserConversations(eq(LIKE_PREFIX), any(), isNull()))
                        .thenReturn(List.of(stats));

                UsageServiceImpl svc = createService();
                List<UsageStats> result = svc.statsByConversation(USER_ID, null, null, null);

                assertEquals(1, result.size());
                mock.verify(() -> ConversationIdUtil.buildLikePrefix(USER_ID));
            }
        }
    }
}
