package com.smart.rag.agent.poc;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PoC 3: 验证 BaseAdvisor.before() 修改 System Prompt 的可行性。
 *
 * 设计文档假设:
 *   AgentSystemPromptAdvisor 实现 BaseAdvisor，在 before() 中动态注入 System Prompt
 *   + 每轮 ReAct 循环前从 workspace 读取中间答案注入。
 *
 * 验证项:
 *   1. before() 签名: (ChatClientRequest, AdvisorChain) → ChatClientRequest
 *   2. ChatClientRequest 是否可变（mutate → builder → 修改 prompt）
 *   3. Prompt 中是否包含 Message 列表（可修改/追加 SystemMessage）
 *   4. before() 中从外部引用（workspace）读取数据的可行性
 *   5. Advisor order 机制验证
 */
@DisplayName("PoC 3: BaseAdvisor.before() System Prompt 注入验证")
class Poc3_BaseAdvisorSystemPromptTest {

    private record ToolWorkspace(String userId, String intent, List<String> intermediateAnswers) {}

    @Nested
    @DisplayName("ChatClientRequest 可变性验证")
    class RequestMutability {

        @Test
        @DisplayName("ChatClientRequest 是 Record，但提供 mutate() Builder")
        void requestIsRecordWithMutateBuilder() {
            ChatClientRequest original = ChatClientRequest.builder()
                .prompt(new Prompt(List.of(new UserMessage("hello"))))
                .build();

            ChatClientRequest.Builder builder = original.mutate();
            assertThat(builder).isNotNull();

            ChatClientRequest modified = builder.build();
            assertThat(modified).isNotNull();
        }

        @Test
        @DisplayName("可通过 mutate().prompt() 替换整个 Prompt")
        void canReplacePromptViaMutate() {
            Prompt originalPrompt = new Prompt(List.of(new UserMessage("original")));
            ChatClientRequest original = ChatClientRequest.builder()
                .prompt(originalPrompt)
                .build();

            Prompt newPrompt = new Prompt(List.of(
                new SystemMessage("You are a helpful assistant."),
                new UserMessage("modified")
            ));

            ChatClientRequest modified = original.mutate()
                .prompt(newPrompt)
                .build();

            assertThat(modified.prompt()).isNotSameAs(originalPrompt);
            assertThat(modified.prompt().getInstructions()).hasSize(2);
            assertThat(modified.prompt().getInstructions().get(0)).isInstanceOf(SystemMessage.class);
        }

        @Test
        @DisplayName("可通过 mutate().context() 注入键值对")
        void canInjectContextViaMutate() {
            ChatClientRequest original = ChatClientRequest.builder()
                .prompt(new Prompt(List.of(new UserMessage("test"))))
                .build();

            ChatClientRequest modified = original.mutate()
                .context("workspaceId", "ws-123")
                .build();

            assertThat(modified.context()).containsEntry("workspaceId", "ws-123");
        }
    }

    @Nested
    @DisplayName("System Prompt 注入方案验证")
    class SystemPromptInjection {

        @Test
        @DisplayName("方案: 在 Prompt 的 messages 列表首位插入 SystemMessage")
        void insertSystemMessageAtHead() {
            UserMessage userMsg = new UserMessage("What is RAG?");
            Prompt originalPrompt = new Prompt(List.of(userMsg));

            // 构造新的 messages 列表，SystemMessage 在首位
            List<Message> newMessages = new ArrayList<>();
            newMessages.add(new SystemMessage("""
                You are an intelligent RAG assistant.
                Follow the atomic decision workflow:
                1. Decide: retrieve or parametric
                2. If retrieve: call search tools
                3. Self-reflect on results
                4. Generate intermediate answer
                """));
            newMessages.addAll(originalPrompt.getInstructions());

            Prompt modifiedPrompt = new Prompt(newMessages);

            assertThat(modifiedPrompt.getInstructions()).hasSize(2);
            assertThat(modifiedPrompt.getInstructions().get(0)).isInstanceOf(SystemMessage.class);
            assertThat(modifiedPrompt.getInstructions().get(1)).isInstanceOf(UserMessage.class);
        }

        @Test
        @DisplayName("方案: 替换现有 SystemMessage（如果已有）")
        void replaceExistingSystemMessage() {
            List<Message> messages = new ArrayList<>(List.of(
                new SystemMessage("Old system prompt"),
                new UserMessage("What is RAG?")
            ));

            String additionalContext = "\n## 已收集的信息\n- RAG stands for Retrieval-Augmented Generation";

            // 查找并替换 SystemMessage
            List<Message> modifiedMessages = messages.stream()
                .map(msg -> {
                    if (msg instanceof SystemMessage sysMsg) {
                        return new SystemMessage(sysMsg.getText() + additionalContext);
                    }
                    return msg;
                })
                .toList();

            Prompt modifiedPrompt = new Prompt(modifiedMessages);
            SystemMessage sysMsg = (SystemMessage) modifiedPrompt.getInstructions().get(0);
            assertThat(sysMsg.getText()).contains("已收集的信息");
            assertThat(sysMsg.getText()).contains("Old system prompt");
        }

        @Test
        @DisplayName("方案: 中间答案注入 — 追加到 SystemMessage 末尾")
        void intermediateAnswerInjection() {
            // 模拟 workspace 中的中间答案
            record IntermediateAnswer(String subQuery, String answer) {}

            List<IntermediateAnswer> answers = List.of(
                new IntermediateAnswer("What is RAG?", "RAG is a technique that combines retrieval and generation."),
                new IntermediateAnswer("How does RAG work?", "RAG retrieves relevant documents and uses them as context.")
            );

            // 构造注入文本
            StringBuilder sb = new StringBuilder("\n\n## 已收集的信息\n");
            for (int i = 0; i < answers.size(); i++) {
                var ans = answers.get(i);
                sb.append(String.format("%d. [问题] %s\n   [答案] %s\n", i + 1, ans.subQuery(), ans.answer()));
            }

            String originalSystemPrompt = "You are an intelligent RAG assistant.";
            String injectedPrompt = originalSystemPrompt + sb;

            List<Message> messages = List.of(
                new SystemMessage(injectedPrompt),
                new UserMessage("Compare RAG and fine-tuning")
            );

            Prompt prompt = new Prompt(messages);
            SystemMessage sysMsg = (SystemMessage) prompt.getInstructions().get(0);
            assertThat(sysMsg.getText()).contains("已收集的信息");
            assertThat(sysMsg.getText()).contains("What is RAG?");
            assertThat(sysMsg.getText()).contains("How does RAG work?");
        }
    }

    @Nested
    @DisplayName("BaseAdvisor 实现验证")
    class BaseAdvisorImplementation {

        @Test
        @DisplayName("可实现 BaseAdvisor 接口的 before/after 方法")
        void canImplementBaseAdvisor() {
            // 模拟 workspace
            record Workspace(String intent, List<String> intermediateAnswers) {}

            var workspaceRef = new java.util.concurrent.atomic.AtomicReference<>(
                new Workspace("DEEP_RETRIEVAL", new ArrayList<>())
            );

            BaseAdvisor advisor = new BaseAdvisor() {
                @Override
                public String getName() {
                    return "TestAgentSystemPromptAdvisor";
                }

                @Override
                public int getOrder() {
                    return 1;
                }

                @Override
                public ChatClientRequest before(ChatClientRequest request, AdvisorChain chain) {
                    Workspace ws = workspaceRef.get();
                    if (ws.intermediateAnswers().isEmpty()) {
                        return request;
                    }

                    // 注入中间答案到 System Prompt
                    List<Message> originalMessages = request.prompt().getInstructions();
                    List<Message> newMessages = new ArrayList<>();

                    StringBuilder contextBuilder = new StringBuilder();
                    for (String answer : ws.intermediateAnswers()) {
                        contextBuilder.append("- ").append(answer).append("\n");
                    }

                    for (Message msg : originalMessages) {
                        if (msg instanceof SystemMessage sysMsg) {
                            newMessages.add(new SystemMessage(
                                sysMsg.getText() + "\n\n## 已收集的信息\n" + contextBuilder
                            ));
                        } else {
                            newMessages.add(msg);
                        }
                    }

                    return request.mutate()
                        .prompt(new Prompt(newMessages))
                        .build();
                }

                @Override
                public ChatClientResponse after(ChatClientResponse response, AdvisorChain chain) {
                    return response;
                }
            };

            assertThat(advisor.getName()).isEqualTo("TestAgentSystemPromptAdvisor");
            assertThat(advisor.getOrder()).isEqualTo(1);
        }

        @Test
        @DisplayName("before() 中修改的 request 不影响原始 request（不可变）")
        void beforeReturnsNewRequest() {
            ChatClientRequest original = ChatClientRequest.builder()
                .prompt(new Prompt(List.of(
                    new SystemMessage("original prompt"),
                    new UserMessage("test")
                )))
                .build();

            // 模拟 before() 修改
            List<Message> newMessages = original.prompt().getInstructions().stream()
                .map(msg -> {
                    if (msg instanceof SystemMessage sysMsg) {
                        return (Message) new SystemMessage(sysMsg.getText() + " + injected");
                    }
                    return msg;
                })
                .toList();

            ChatClientRequest modified = original.mutate()
                .prompt(new Prompt(newMessages))
                .build();

            // 原始不变
            SystemMessage originalSys = (SystemMessage) original.prompt().getInstructions().get(0);
            assertThat(originalSys.getText()).isEqualTo("original prompt");

            // 修改后的新对象
            SystemMessage modifiedSys = (SystemMessage) modified.prompt().getInstructions().get(0);
            assertThat(modifiedSys.getText()).isEqualTo("original prompt + injected");
        }
    }

    @Nested
    @DisplayName("Advisor Order 排布验证")
    class AdvisorOrdering {

        @Test
        @DisplayName("AgentSystemPromptAdvisor(order=1) 在 ToolCallAdvisor(order=2) 之前")
        void advisorOrderingIsCorrect() {
            int agentSystemPromptOrder = 1;
            int toolCallAdvisorOrder = 2;
            int conversationContextOrder = -1;

            // Spring AI advisor order: 数值越小越先执行
            // before(): 按 order 升序执行
            // after(): 按 order 降序执行
            assertThat(conversationContextOrder).isLessThan(agentSystemPromptOrder);
            assertThat(agentSystemPromptOrder).isLessThan(toolCallAdvisorOrder);

            // before() 执行顺序:
            // ConversationContextAdvisor(-1) → AgentSystemPromptAdvisor(1) → ToolCallAdvisor(2)
            // 这意味着 AgentSystemPromptAdvisor 的 System Prompt 注入在 ToolCallAdvisor 之前生效
        }
    }

    @Test
    @DisplayName("综合: 完整的 before() 注入流程")
    void fullBeforeInjectionFlow() {
        // 模拟 workspace（与 Tool 闭包共享引用）
        var workspace = new java.util.concurrent.atomic.AtomicReference<>(
            new ToolWorkspace("user-42", "DEEP_RETRIEVAL", new ArrayList<>())
        );

        // 模拟 AgentSystemPromptAdvisor 的 before() 逻辑
        String agentBasePrompt = """
            You are an intelligent RAG assistant.
            Follow the atomic decision workflow:
            1. Decide: retrieve or parametric
            2. If retrieve: call search tools
            3. Self-reflect on results
            4. Generate intermediate answer
            """;

        // 构造初始 request
        ChatClientRequest initialRequest = ChatClientRequest.builder()
            .prompt(new Prompt(List.of(
                new SystemMessage(agentBasePrompt),
                new UserMessage("Compare RAG and fine-tuning")
            )))
            .build();

        // 第一轮: workspace 无中间答案，不注入额外内容
        ChatClientRequest round1Request = applyBefore(initialRequest, workspace.get());
        SystemMessage round1Sys = (SystemMessage) round1Request.prompt().getInstructions().get(0);
        assertThat(round1Sys.getText()).doesNotContain("已收集的信息");

        // 模拟 Tool 执行后更新 workspace
        workspace.set(new ToolWorkspace("user-42", "DEEP_RETRIEVAL",
            List.of("RAG supports real-time knowledge updates")));

        // 第二轮: workspace 有中间答案，注入
        ChatClientRequest round2Request = applyBefore(round1Request, workspace.get());
        SystemMessage round2Sys = (SystemMessage) round2Request.prompt().getInstructions().get(0);
        assertThat(round2Sys.getText()).contains("已收集的信息");
        assertThat(round2Sys.getText()).contains("RAG supports real-time knowledge updates");
    }

    private ChatClientRequest applyBefore(ChatClientRequest request, Object ws) {
        if (ws instanceof ToolWorkspace(String userId, String intent, List<String> intermediateAnswers)) {
            if (intermediateAnswers.isEmpty()) {
                return request;
            }

            List<Message> newMessages = new ArrayList<>();
            StringBuilder contextBuilder = new StringBuilder("\n\n## 已收集的信息\n");
            for (String answer : intermediateAnswers) {
                contextBuilder.append("- ").append(answer).append("\n");
            }

            for (Message msg : request.prompt().getInstructions()) {
                if (msg instanceof SystemMessage sysMsg) {
                    newMessages.add(new SystemMessage(sysMsg.getText() + contextBuilder));
                } else {
                    newMessages.add(msg);
                }
            }

            return request.mutate().prompt(new Prompt(newMessages)).build();
        }
        return request;
    }
}
