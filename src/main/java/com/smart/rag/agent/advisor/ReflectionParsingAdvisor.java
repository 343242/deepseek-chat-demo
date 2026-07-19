package com.smart.rag.agent.advisor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smart.rag.agent.dto.AtomicDecisionResult;
import com.smart.rag.agent.dto.IntermediateAnswer;
import com.smart.rag.agent.dto.SelfReflection;
import com.smart.rag.agent.event.AgentEventStore;
import com.smart.rag.agent.event.payload.IntermediateAnswerPayload;
import com.smart.rag.agent.event.payload.RetrievalStrategyPayload;
import com.smart.rag.agent.event.payload.SelfReflectionPayload;
import com.smart.rag.agent.workspace.ToolWorkspace;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 反思标记解析 Advisor — Self-RAG/DeepRAG 工程化落地的"消费侧"。
 * <p>
 * <b>设计依据</b>：Self-RAG (ICLR 2024) 的 reflection token 和 DeepRAG (ICLR 2026) 的原子决策/中间答案，
 * 论文中都是主模型在训练（SFT/DPO）后于单次生成过程中产出的结构化信号。闭源 LLM（DeepSeek 等）无法
 * SFT/加 token，按设计文档 §2.4 的工程化路径，改为：LLM 在响应文本中以 XML 标记包裹 JSON 输出，
 * 由本 Advisor 在 {@code after()} 钩子解析、写入 {@link ToolWorkspace}、emit 事件到 {@link AgentEventStore}。
 * <p>
 * <b>职责边界</b>：只做"解析 + 落地 + 剥离标记"，不做"注入"（注入归 {@link AgentSystemPromptAdvisor}），
 * 不改变控制流（原子决策为软引导，设计文档 §2.6：LLM ReAct 隐式驱动，不由代码显式控制）。
 * <p>
 * <b>order=3</b>，在 ToolCallAdvisor(order=2) 之后执行。
 * <p>
 * <b>已知限制（PoC9 VERDICT）</b>：{@code BaseAdvisor.after()} 在多轮 ReAct 中<b>只触发一次</b>
 * （续轮由 {@code ToolCallAdvisor} 内部 {@code doBeforeStream/doAfterStream} 驱动，不重经过上游 BaseAdvisor）。
 * 因此本解析器只能看到<b>最终响应</b>（finishReason=stop 那一轮）中的标记，看不到 tool_calls 间隙的中间轮文本。
 * 这意味着 LLM 应在最终回答时输出 reflection/intermediate_answer 标记（prompt 引导已说明）。
 * {@code incrementRound()} 反映的是"本请求解析到的 reflection 次数"，不是真实 ReAct 轮次——但足以激活
 * 之前恒为 0 的 {@code getRetrievalRound()} 死字段，供 metadata 观测。
 * <p>
 * <b>容错契约</b>：闭源 LLM 输出不可靠，本 Advisor 任何异常都吞掉只 log，绝不影响主流程
 * （与 {@code AgentEventStore} 异步写入的容错哲学一致）。标记缺失视为 LLM 跳步，静默跳过（§2.6 允许）。
 */
public class ReflectionParsingAdvisor implements BaseAdvisor {

    private static final Logger log = LoggerFactory.getLogger(ReflectionParsingAdvisor.class);

    /** nextAction 合法取值（设计文档 §2.4.2 字段注释） */
    private static final List<String> VALID_NEXT_ACTIONS =
        List.of("proceed", "rewrite_and_search", "rerank", "switch_tool");

    /** IntermediateAnswer source 合法取值（设计文档 §2.4.3） */
    private static final List<String> VALID_SOURCES = List.of("retrieval", "parametric");

    /** answerHash 截断长度（与 AgentModeStrategy.hashQuery 同语义，64-bit 前缀） */
    private static final int HASH_PREFIX_LENGTH = 16;

    private final ToolWorkspace workspace;
    private final AgentEventStore eventStore;
    private final String sessionId;
    private final @Nullable Long userId;
    private final ObjectMapper objectMapper;

    public ReflectionParsingAdvisor(ToolWorkspace workspace,
                                   AgentEventStore eventStore,
                                   String sessionId,
                                   @Nullable Long userId,
                                   ObjectMapper objectMapper) {
        this.workspace = workspace;
        this.eventStore = eventStore;
        this.sessionId = sessionId;
        this.userId = userId;
        this.objectMapper = objectMapper;
    }

    @Override
    @NonNull
    public String getName() {
        return "ReflectionParsingAdvisor";
    }

    @Override
    public int getOrder() {
        return 3; // 在 ToolCallAdvisor(order=2) 之后
    }

    @Override
    @NonNull
    public ChatClientRequest before(@NonNull ChatClientRequest request, @NonNull AdvisorChain chain) {
        // 不修改请求——注入归 AgentSystemPromptAdvisor，本 Advisor 只消费响应
        return request;
    }

    @Override
    @NonNull
    public ChatClientResponse after(@NonNull ChatClientResponse response, @NonNull AdvisorChain chain) {
        String text = extractResponseText(response);
        if (text == null || text.isBlank()) {
            return response;
        }

        // 整体 try-catch：解析任何异常都不影响主流程，返回原 response
        try {
            AtomicDecisionResult atomic = parseAtomicDecision(text);
            SelfReflection reflection = parseReflection(text);
            IntermediateAnswer intermediate = parseIntermediateAnswer(text);

            // 落地 workspace + emit 事件（每项独立 try-catch，互不影响）
            if (atomic != null) {
                handleAtomicDecision(atomic);
            }
            if (reflection != null) {
                handleReflection(reflection, atomic);
            }
            if (intermediate != null) {
                handleIntermediateAnswer(intermediate);
            }

            // 剥离所有标记后写回 response，避免标记污染最终答案/ChatMemory
            String cleanText = stripAllMarkers(text);
            if (!cleanText.equals(text)) {
                return rebuildResponseWithText(response, cleanText);
            }
        } catch (Exception e) {
            log.error("Reflection parsing failed (non-fatal, returning original response): sessionId={}",
                sessionId, e);
        }
        return response;
    }

    // === 提取响应文本 ===

    private @Nullable String extractResponseText(ChatClientResponse response) {
        ChatResponse chatResponse = response.chatResponse();
        if (chatResponse == null || chatResponse.getResults() == null || chatResponse.getResults().isEmpty()) {
            return null;
        }
        Generation gen = chatResponse.getResult();
        if (gen == null || gen.getOutput() == null) {
            return null;
        }
        return gen.getOutput().getText();
    }

    // === 解析三种标记 ===

    private @Nullable AtomicDecisionResult parseAtomicDecision(String text) {
        return ReflectionMarker.ATOMIC_DECISION.extract(text)
            .flatMap(json -> parseJson(json, "atomic_decision"))
            .map(node -> {
                String decision = textOf(node, "decision");
                String subQuery = textOf(node, "subQuery");
                String reason = textOf(node, "reason");
                String fromTool = textOf(node, "fromTool");
                String toTool = textOf(node, "toTool");
                AtomicDecisionResult ad = new AtomicDecisionResult(subQuery, decision, reason, fromTool, toTool);
                return ad.isValid() ? ad : null;
            })
            .orElse(null);
    }

    private @Nullable SelfReflection parseReflection(String text) {
        return ReflectionMarker.REFLECTION.extract(text)
            .flatMap(json -> parseJson(json, "reflection"))
            .map(node -> {
                // isRelevant/isSufficient 必须存在，缺失视为未解析跳过
                if (!node.has("isRelevant") || !node.has("isSufficient")) {
                    log.warn("Reflection marker missing required field isRelevant/isSufficient, skipping");
                    return null;
                }
                boolean isRelevant = node.get("isRelevant").asBoolean(false);
                boolean isSufficient = node.get("isSufficient").asBoolean(false);
                List<String> missingAspects = stringListOf(node.get("missingAspects"));
                String nextAction = normalizeNextAction(textOf(node, "nextAction"));
                // 第一版查询分解未启用，subQueryIndex 固定为 0（设计文档 §2.4 简化）
                return new SelfReflection(0, isRelevant, isSufficient, missingAspects, nextAction);
            })
            .orElse(null);
    }

    private @Nullable IntermediateAnswer parseIntermediateAnswer(String text) {
        return ReflectionMarker.INTERMEDIATE_ANSWER.extract(text)
            .flatMap(json -> parseJson(json, "intermediate_answer"))
            .map(node -> {
                String source = textOf(node, "source");
                if (!VALID_SOURCES.contains(source)) {
                    log.warn("intermediate_answer source invalid '{}', expected one of {}", source, VALID_SOURCES);
                    return null;
                }
                String subQuery = textOf(node, "subQuery");
                String answer = textOf(node, "answer");
                if (answer == null || answer.isBlank()) {
                    log.warn("intermediate_answer missing 'answer' content, skipping");
                    return null;
                }
                List<String> citedDocIds = stringListOf(node.get("citedDocIds"));
                return new IntermediateAnswer(0, subQuery, answer, source, citedDocIds);
            })
            .orElse(null);
    }

    // === 落地处理（workspace 写入 + 事件 emit） ===

    private void handleAtomicDecision(AtomicDecisionResult atomic) {
        // 软引导：仅记录日志（设计文档 §2.4.1 工程化实现，§2.6 不由代码显式控制）
        // fromTool/toTool 在 handleReflection 中供 switch_tool 决策复用
        log.info("AtomicDecision parsed: decision={}, reason={}, fromTool={}, toTool={}",
            atomic.decision(), atomic.reason(), atomic.fromTool(), atomic.toTool());
    }

    private void handleReflection(SelfReflection reflection, @Nullable AtomicDecisionResult atomic) {
        try {
            workspace.addSelfReflection(reflection);
            // 推进轮次（激活一直为 0 的 getRetrievalRound 死字段）
            int round = workspace.incrementRound();

            double relevanceScore = reflection.isRelevant() ? 1.0 : 0.0;
            double completenessScore = reflection.isSufficient() ? 1.0 : 0.0;
            SelfReflectionPayload payload = new SelfReflectionPayload(
                relevanceScore, completenessScore, reflection.nextAction());
            eventStore.recordSelfReflection(sessionId, userId, payload);

            // switch_tool 且有 from/to → emit RETRIEVAL_STRATEGY（对齐 OPTIMIZATIONS.md §3.2）
            if ("switch_tool".equals(reflection.nextAction())) {
                String from = atomic != null && atomic.fromTool() != null ? atomic.fromTool() : null;
                String to = atomic != null && atomic.toTool() != null ? atomic.toTool() : null;
                if (from != null && to != null) {
                    String reason = reflection.missingAspects() == null || reflection.missingAspects().isEmpty()
                        ? "unspecified" : String.join("; ", reflection.missingAspects());
                    eventStore.recordRetrievalStrategy(sessionId, userId,
                        new RetrievalStrategyPayload(from, to, reason));
                }
            }
            log.info("Reflection parsed: round={}, relevant={}, sufficient={}, nextAction={}",
                round, reflection.isRelevant(), reflection.isSufficient(), reflection.nextAction());
        } catch (Exception e) {
            log.error("Failed to persist reflection (non-fatal)", e);
        }
    }

    private void handleIntermediateAnswer(IntermediateAnswer ia) {
        try {
            workspace.addIntermediateAnswer(ia);
            String answerHash = sha256Hex(ia.answer());
            IntermediateAnswerPayload payload = new IntermediateAnswerPayload(
                ia.source(), ia.subQuery(), answerHash, ia.citedDocIds());
            eventStore.recordIntermediateAnswer(sessionId, userId, payload);
            log.info("IntermediateAnswer parsed: source={}, subQueryLen={}, answerLen={}, citedDocs={}",
                ia.source(),
                ia.subQuery() == null ? 0 : ia.subQuery().length(),
                ia.answer().length(),
                ia.citedDocIds() == null ? 0 : ia.citedDocIds().size());
        } catch (Exception e) {
            log.error("Failed to persist intermediate answer (non-fatal)", e);
        }
    }

    // === 标记剥离 + 响应重建 ===

    private String stripAllMarkers(String text) {
        String result = text;
        for (ReflectionMarker m : ReflectionMarker.values()) {
            result = m.strip(result);
        }
        // 折叠因剥离产生的连续空行（超过 2 个换行压成 2 个）
        return result.replaceAll("\n{3,}", "\n\n");
    }

    private ChatClientResponse rebuildResponseWithText(ChatClientResponse response, String cleanText) {
        try {
            ChatResponse original = response.chatResponse();
            if (original == null) {
                return response;
            }
            Generation oldGen = original.getResult();
            if (oldGen == null) {
                return response;
            }
            AssistantMessage oldMsg = oldGen.getOutput();
            // 保留 toolCalls/media，仅替换文本内容（标记是文本内的注释，需剥离避免污染最终答案/记忆）
            AssistantMessage.Builder msgBuilder = AssistantMessage.builder().content(cleanText);
            if (oldMsg != null) {
                if (oldMsg.getToolCalls() != null && !oldMsg.getToolCalls().isEmpty()) {
                    msgBuilder.toolCalls(oldMsg.getToolCalls());
                }
                if (oldMsg.getMedia() != null && !oldMsg.getMedia().isEmpty()) {
                    msgBuilder.media(oldMsg.getMedia());
                }
            }
            AssistantMessage newMsg = msgBuilder.build();
            ChatGenerationMetadata genMeta = oldGen.getMetadata();
            Generation newGen = genMeta != null
                ? new Generation(newMsg, genMeta)
                : new Generation(newMsg);
            List<Generation> newGens = new ArrayList<>(original.getResults());
            if (!newGens.isEmpty()) {
                newGens.set(0, newGen);
            } else {
                newGens.add(newGen);
            }
            ChatResponse newChat = ChatResponse.builder()
                .from(original)
                .generations(newGens)
                .build();
            return response.mutate().chatResponse(newChat).build();
        } catch (Exception e) {
            log.warn("Failed to rebuild response with clean text (keeping original)", e);
            return response;
        }
    }

    // === JSON / 字段提取辅助 ===

    private java.util.Optional<JsonNode> parseJson(String json, String markerName) {
        try {
            return java.util.Optional.of(objectMapper.readTree(json));
        } catch (Exception e) {
            log.warn("Failed to parse {} marker JSON (skipping): {}", markerName, e.getMessage());
            return java.util.Optional.empty();
        }
    }

    private static String textOf(JsonNode node, String field) {
        if (node == null || !node.has(field) || node.get(field).isNull()) {
            return null;
        }
        return node.get(field).asText();
    }

    private static List<String> stringListOf(@Nullable JsonNode node) {
        if (node == null || !node.isArray() || node.isEmpty()) {
            return Collections.emptyList();
        }
        List<String> result = new ArrayList<>(node.size());
        for (JsonNode el : node) {
            if (el != null && !el.isNull()) {
                String s = el.asText();
                if (s != null && !s.isBlank()) {
                    result.add(s);
                }
            }
        }
        return result;
    }

    private static String normalizeNextAction(@Nullable String raw) {
        if (raw == null || raw.isBlank()) {
            return "proceed"; // 缺失时视为放行（保守：不触发重检索）
        }
        String trimmed = raw.trim();
        if (VALID_NEXT_ACTIONS.contains(trimmed)) {
            return trimmed;
        }
        log.warn("Unknown nextAction '{}', defaulting to 'proceed'", trimmed);
        return "proceed";
    }

    // === 脱敏哈希（与 AgentModeStrategy.hashQuery 同语义，但作用于 answer） ===

    static String sha256Hex(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.substring(0, HASH_PREFIX_LENGTH);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 是 JDK 标准算法，理论上不会抛
            return "";
        }
    }
}
