package com.smart.rag.agent.guardrail;

import com.smart.rag.agent.config.AgentRagProperties;
import com.smart.rag.infrastructure.llm.adapter.UsageRecordingChatModel;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Agent 护栏 -- 三指标检测，防止 ReAct 循环失控
 * <p>
 * 三指标：
 * <ol>
 *   <li>循环迭代总次数 -- 硬中断（STOP）</li>
 *   <li>累计 Token 消耗 -- 硬中断（STOP）</li>
 *   <li>同一 Tool 连续调用 -- 软干预（WARN）</li>
 * </ol>
 * <p>
 * 指标 1/2 超标时返回 STOP，跳出 ReAct 循环，用已有结果生成回答。
 * 指标 3 超标时返回 WARN，不跳出循环，注入提醒到下一轮 LLM prompt，LLM 自主决策。
 * <p>
 * 注意：此类是请求级对象（每次 Agent 请求创建新实例），不是 Spring Bean。
 */
public class AgentGuardrails {

    private static final Logger log = LoggerFactory.getLogger(AgentGuardrails.class);

    private final AgentRagProperties properties;
    private final UsageRecordingChatModel chatModel;

    /** Token 上限（模型上下文窗口 x contextWindowRatio） */
    private final long tokenLimit;

    /** 连续 Tool 追踪状态 */
    private @Nullable String lastToolName = null;
    private int consecutiveSameTool = 0;

    /** 累计迭代计数 */
    private int totalIterations = 0;

    public AgentGuardrails(AgentRagProperties properties,
                           UsageRecordingChatModel chatModel,
                           long tokenLimit) {
        this.properties = properties;
        this.chatModel = chatModel;
        this.tokenLimit = tokenLimit;
    }

    /**
     * 执行护栏检查
     * <p>
     * 每次调用会递增 totalIterations 并更新连续 Tool 追踪状态。
     *
     * @param currentToolName 当前 Tool 名称（可 null，如 LLM 未调用 Tool 而直接生成回答时）
     * @return 检查结果
     */
    public GuardrailCheck check(@Nullable String currentToolName) {
        totalIterations++;

        // === 指标 1：循环迭代总次数 ===
        if (totalIterations > properties.maxToolIterations()) {
            log.warn("Agent guardrail STOP: ITERATION_LIMIT, iteration={}/{}, tokens={}",
                totalIterations, properties.maxToolIterations(), chatModel.getTotalTokens());
            return GuardrailCheck.stop("ITERATION_LIMIT",
                "已达到最大调用轮次 (%d/%d)，停止检索。"
                    .formatted(totalIterations, properties.maxToolIterations()));
        }

        // === 指标 2：累计 Token 消耗 ===
        long tokensUsed = chatModel.getTotalTokens();
        if (tokensUsed >= tokenLimit) {
            log.warn("Agent guardrail STOP: TOKEN_LIMIT, tokens={}/{}", tokensUsed, tokenLimit);
            return GuardrailCheck.stop("TOKEN_LIMIT",
                "累计 token 消耗已达 %d（上限 %d），停止检索。"
                    .formatted(tokensUsed, tokenLimit));
        }

        // === 指标 3：同一 Tool 连续调用检测（软干预） ===
        if (currentToolName != null && currentToolName.equals(lastToolName)) {
            consecutiveSameTool++;
        } else {
            lastToolName = currentToolName;
            consecutiveSameTool = 1;
        }

        if (consecutiveSameTool > properties.maxConsecutiveSameTool()) {
            log.info("Agent guardrail WARN: CONSECUTIVE_TOOL, tool={}, consecutive={}/{}",
                currentToolName, consecutiveSameTool, properties.maxConsecutiveSameTool());
            return GuardrailCheck.warn("CONSECUTIVE_TOOL",
                "注意：工具 [%s] 已连续调用 %d 次。请评估：当前已收集的信息是否足够回答用户问题？"
                    + "如果不够，还缺少哪些部分？尝试切换其他工具是否能获得更好的结果？"
                    + "如果信息已充分，请直接生成最终回答。"
                    .formatted(currentToolName, consecutiveSameTool));
        }

        return GuardrailCheck.ok();
    }

    // === 读取状态 ===

    public int getTotalIterations() {
        return totalIterations;
    }

    public int getConsecutiveSameTool() {
        return consecutiveSameTool;
    }

    public @Nullable String getLastToolName() {
        return lastToolName;
    }

    public long getTokensUsed() {
        return chatModel.getTotalTokens();
    }

    /** 获取护栏持有的用量采集装饰器（供外部包装 ChatClient，必须复用同一实例） */
    public UsageRecordingChatModel chatModel() {
        return chatModel;
    }

    // === 内部类型 ===

    /**
     * 护栏检查结果
     *
     * @param status     OK / WARN / STOP
     * @param reason     触发原因标识（如 ITERATION_LIMIT / TOKEN_LIMIT / CONSECUTIVE_TOOL）
     * @param message    给 LLM 或用户的消息
     */
    public record GuardrailCheck(Status status, @Nullable String reason, @Nullable String message) {

        static GuardrailCheck ok() {
            return new GuardrailCheck(Status.OK, null, null);
        }

        static GuardrailCheck stop(String reason, String message) {
            return new GuardrailCheck(Status.STOP, reason, message);
        }

        static GuardrailCheck warn(String reason, String message) {
            return new GuardrailCheck(Status.WARN, reason, message);
        }

        public boolean isOk() {
            return status == Status.OK;
        }

        public boolean shouldStop() {
            return status == Status.STOP;
        }

        public boolean shouldWarn() {
            return status == Status.WARN;
        }
    }

    /** 护栏状态 */
    public enum Status {
        /** 通过 -- 正常继续 */
        OK,
        /** 软干预 -- 注入提醒但继续循环 */
        WARN,
        /** 硬中断 -- 跳出 ReAct 循环 */
        STOP
    }
}
