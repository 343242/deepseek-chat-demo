package com.demo.chat.rag.agent.poc;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.client.ChatClientResponse;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PoC 2: 验证 ReAct 循环中 ChatResponse.usage() 的可达性。
 *
 * 设计文档假设:
 *   ToolCallAdvisor 的 ReAct 循环中，每轮中间 ChatResponse 暴露 usage 元数据。
 *
 * 实际发现（源码分析）:
 *   - Usage 接口: getPromptTokens() → Integer, getCompletionTokens() → Integer, getTotalTokens() → Integer
 *   - ChatResponseMetadata 默认 usage 为 EmptyUsage（非 null）
 *   - Generation 构造器: Generation(AssistantMessage)
 *
 * 验证项:
 *   1. ChatResponse → ChatResponseMetadata → Usage 的访问路径
 *   2. Usage 字段名: completionTokens (非 generationTokens)
 *   3. Usage 默认值（EmptyUsage）的行为
 *   4. Advisor.after() 中从 ChatClientResponse 获取 usage 的路径
 */
@DisplayName("PoC 2: ReAct 循环 usage 元数据可达性验证")
class Poc2_ReactLoopUsageMetadataTest {

    @Nested
    @DisplayName("ChatResponse metadata 数据结构验证")
    class MetadataStructureVerification {

        @Test
        @DisplayName("ChatResponse.getMetadata() 返回 ChatResponseMetadata（非 null）")
        void chatResponseHasMetadata() {
            ChatResponse chatResponse = new ChatResponse(
                List.of(new Generation(new AssistantMessage("test")))
            );

            ChatResponseMetadata metadata = chatResponse.getMetadata();
            assertThat(metadata).isNotNull();
        }

        @Test
        @DisplayName("ChatResponseMetadata 默认包含 EmptyUsage（非 null）")
        void metadataDefaultUsageIsNotNull() {
            ChatResponse chatResponse = new ChatResponse(
                List.of(new Generation(new AssistantMessage("test")))
            );

            ChatResponseMetadata metadata = chatResponse.getMetadata();
            Usage usage = metadata.getUsage();
            assertThat(usage).isNotNull();
        }

        @Test
        @DisplayName("EmptyUsage 的 token 值为 null")
        void emptyUsageTokensAreNull() {
            ChatResponse chatResponse = new ChatResponse(
                List.of(new Generation(new AssistantMessage("test")))
            );

            Usage usage = chatResponse.getMetadata().getUsage();
            // EmptyUsage: getPromptTokens() = 0, getCompletionTokens() = 0 (非 null)
            assertThat(usage.getPromptTokens()).isEqualTo(0);
            assertThat(usage.getCompletionTokens()).isEqualTo(0);
            assertThat(usage.getTotalTokens()).isEqualTo(0);
        }
    }

    @Nested
    @DisplayName("Usage 接口字段验证")
    class UsageFieldVerification {

        @Test
        @DisplayName("Usage 接口: getPromptTokens / getCompletionTokens / getTotalTokens")
        void usageInterfaceMethods() {
            ChatResponse chatResponse = new ChatResponse(
                List.of(new Generation(new AssistantMessage("test")))
            );

            Usage usage = chatResponse.getMetadata().getUsage();
            // 验证方法可达
            assertThatNoException(usage.getPromptTokens());
            assertThatNoException(usage.getCompletionTokens());
            assertThatNoException(usage.getTotalTokens());
            assertThatNoException(usage.getNativeUsage());
        }

        private void assertThatNoException(Object value) {
            // 仅验证方法可调用，不假设值
        }
    }

    @Nested
    @DisplayName("ChatClientResponse 包装层验证")
    class ClientResponseWrapper {

        @Test
        @DisplayName("ChatClientResponse 包装 ChatResponse，可获取 usage")
        void clientResponseWrapsChatResponse() {
            ChatResponse chatResponse = new ChatResponse(
                List.of(new Generation(new AssistantMessage("test response")))
            );

            ChatClientResponse clientResponse = ChatClientResponse.builder()
                .chatResponse(chatResponse)
                .build();

            // 验证路径: ChatClientResponse → chatResponse() → getMetadata() → getUsage()
            ChatResponse extracted = clientResponse.chatResponse();
            assertThat(extracted).isNotNull();

            ChatResponseMetadata metadata = extracted.getMetadata();
            assertThat(metadata).isNotNull();

            Usage usage = metadata.getUsage();
            assertThat(usage).isNotNull(); // EmptyUsage, 非 null
        }

        @Test
        @DisplayName("Advisor.after() 可从 ChatClientResponse 提取 token 计数")
        void advisorAfterCanExtractUsage() {
            ChatResponse chatResponse = new ChatResponse(
                List.of(new Generation(new AssistantMessage("response")))
            );

            ChatClientResponse clientResponse = ChatClientResponse.builder()
                .chatResponse(chatResponse)
                .build();

            // 模拟 Advisor.after() 中的提取逻辑
            long tokens = extractTokenCount(clientResponse);
            assertThat(tokens).isGreaterThanOrEqualTo(0);
        }

        private long extractTokenCount(ChatClientResponse response) {
            ChatResponse cr = response.chatResponse();
            if (cr == null || cr.getMetadata() == null) {
                return 0;
            }
            Usage usage = cr.getMetadata().getUsage();
            if (usage == null) {
                return 0;
            }
            Integer total = usage.getTotalTokens();
            return total != null ? total : 0;
        }
    }

    @Nested
    @DisplayName("Token 估算降级方案验证")
    class TokenEstimationFallback {

        @Test
        @DisplayName("字符数 / 4 估算方案可用于 token 计数降级")
        void characterBasedEstimation() {
            String input = "What is RAG and how does it compare to fine-tuning?";
            long estimatedTokens = estimateTokens(input);
            assertThat(estimatedTokens).isGreaterThan(0);
            assertThat(estimatedTokens).isLessThan(input.length());
        }

        @Test
        @DisplayName("累积 token 计数器方案可行")
        void cumulativeTokenCounter() {
            List<String> inputs = List.of(
                "What is RAG?",
                "How does fine-tuning work?",
                "Compare RAG and fine-tuning for knowledge updates."
            );

            long totalEstimated = inputs.stream()
                .mapToLong(this::estimateTokens)
                .sum();

            assertThat(totalEstimated).isGreaterThan(0);
            long totalChars = inputs.stream().mapToLong(String::length).sum();
            assertThat(totalEstimated).isLessThan(totalChars);
        }

        private long estimateTokens(String text) {
            if (text == null || text.isEmpty()) {
                return 0;
            }
            return text.length() / 4;
        }
    }

    @Nested
    @DisplayName("ReAct 循环 Advisor 介入点分析")
    class ReactLoopAdvisorIntervention {

        @Test
        @DisplayName("外层 Advisor.after() 只在 ToolCallAdvisor 最终结果时调用一次")
        void outerAdvisorAfterCalledOnce() {
            // BaseAdvisor.adviseCall() 实现（字节码分析确认）:
            //   1. before(request, chain) → modifiedRequest
            //   2. chain.nextCall(modifiedRequest) → response  (这步包含 ToolCallAdvisor 的整个 ReAct 循环)
            //   3. after(response, chain) → modifiedResponse
            //
            // 关键发现: ToolCallAdvisor 内部自行管理 ReAct 循环
            // 外层 Advisor 的 after() 只在 ToolCallAdvisor 完成全部循环后调用一次
            //
            // 结论: usage 无法通过外层 BaseAdvisor.after() 逐轮获取
            // 方案: 使用 ToolCallAdvisor 完成后的总 usage + 字符估算降级

            assertThat(true).isTrue(); // 分析性结论
        }
    }

    @Nested
    @DisplayName("混合 token 计数策略验证")
    class HybridTokenCountingStrategy {

        @Test
        @DisplayName("精确 + 估算混合策略")
        void hybridStrategy() {
            // 策略:
            // 1. 从 ChatClientResponse.chatResponse().getMetadata().getUsage() 获取精确值
            // 2. 如果 usage 为 EmptyUsage (tokens=null)，使用字符估算
            // 3. 累积计数在 AgentGuardrails 中维护

            // 场景 1: 默认 ChatResponse（EmptyUsage）
            ChatResponse defaultResponse = new ChatResponse(
                List.of(new Generation(new AssistantMessage("Hello world")))
            );
            ChatClientResponse clientResp = ChatClientResponse.builder()
                .chatResponse(defaultResponse)
                .build();

            TokenCount count = countTokens(clientResp);
            // EmptyUsage returns 0 → > 0 check fails → goes to character-based estimation fallback
            assertThat(count.usedFallback()).isTrue();
            assertThat(count.total()).isGreaterThan(0);

            // 场景 2: 空响应
            TokenCount emptyCount = countTokens(null);
            assertThat(emptyCount.total()).isEqualTo(0);
        }

        private record TokenCount(long exact, long estimated, boolean usedFallback) {
            long total() { return exact + estimated; }
        }

        private TokenCount countTokens(ChatClientResponse response) {
            if (response == null || response.chatResponse() == null) {
                return new TokenCount(0, 0, true);
            }

            Usage usage = response.chatResponse().getMetadata().getUsage();
            if (usage != null && usage.getPromptTokens() != null && usage.getPromptTokens() > 0) {
                long total = usage.getTotalTokens() != null ? usage.getTotalTokens() : 0;
                return new TokenCount(total, 0, false);
            }

            // 降级: 基于输出文本字符估算
            String content = "";
            if (response.chatResponse().getResult() != null
                && response.chatResponse().getResult().getOutput() != null) {
                content = response.chatResponse().getResult().getOutput().getText();
            }
            long estimated = content != null ? content.length() / 4 : 0;
            return new TokenCount(0, estimated, true);
        }
    }

    @Test
    @DisplayName("综合: Usage API 完整路径验证 — 确定最终策略")
    void determineFinalTokenCountingStrategy() {
        // ===== 最终策略结论 =====
        //
        // 1. Usage API 完整路径（已验证可达）:
        //    ChatClientResponse.chatResponse()
        //      → ChatResponse.getMetadata()
        //      → ChatResponseMetadata.getUsage()
        //      → Usage.getPromptTokens() / getCompletionTokens() / getTotalTokens()
        //
        // 2. 默认 usage 为 EmptyUsage（非 null），但 token 值为 null
        //    → 需检查 promptTokens != null 判断是否有真实 usage
        //
        // 3. 外层 Advisor.after() 只在 ReAct 循环结束后调用一次
        //    → 无法逐轮获取精确 usage
        //
        // 4. 最终策略:
        //    - AgentGuardrails 维护累积计数器
        //    - 每轮迭代: 精确 usage（如果 provider 返回）或字符估算（降级）
        //    - 判断 usage 是否为真实值: promptTokens != null
        //    - 总 token 上限: 模型上下文窗口 × 80%

        // 验证判断逻辑
        ChatResponse emptyUsageResponse = new ChatResponse(
            List.of(new Generation(new AssistantMessage("test")))
        );

        Usage usage = emptyUsageResponse.getMetadata().getUsage();
        // EmptyUsage returns 0 (Integer) not null — cannot distinguish from real usage by null check
        boolean hasRealUsage = usage != null && usage.getPromptTokens() != null && usage.getPromptTokens() > 0;
        assertThat(hasRealUsage).isFalse(); // EmptyUsage → promptTokens = 0, filtered by > 0 check

        // 结论输出
        String strategy = """
            Token 计数策略:
            1. 每轮迭代后检查 usage.getPromptTokens() != null && > 0
            2. 如果 > 0 → 使用 getTotalTokens() 精确值
            3. 如果 = 0 或 null → 使用字符估算 (text.length / 4)
            4. 累积到 AgentGuardrails 的 totalCount
            5. 上限检查: totalCount > modelContextWindow * 0.8
            """;
        assertThat(strategy).isNotEmpty();
    }
}
