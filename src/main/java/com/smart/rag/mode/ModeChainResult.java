package com.smart.rag.mode;

import com.smart.rag.infrastructure.llm.adapter.UsageRecordingChatModel;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.tool.ToolCallback;

import java.util.List;

/**
 * Strategy buildAdvisorChain() 的统一返回类型。
 * <p>
 * 标准模式：仅 chain 非空，其余字段为 null。
 * Agent 模式：chain + agent 元数据 + toolCallbacks（用于 .tools() 注册 ToolCallingChatOptions）。
 * <p>
 * 字段类型设计（消除对 agent 包的依赖）：
 * <ul>
 *   <li>{@link IntentResult} 同在 mode 包（纯 record）</li>
 *   <li>{@link WorkspaceInfo} 为 mode 定义的窄接口，agent 的 ToolWorkspace 实现它（ISP）</li>
 *   <li>{@link UsageRecordingChatModel} 为 infrastructure 的统一用量采集装饰器——护栏检查与
 *       usage 落库消费同一实例（Agent 必须经 {@code ChatModelAssembler.chatClient(model)} 包装，
 *       不得二次装配造成双实例）</li>
 * </ul>
 *
 * @param chain         组装好的 Advisor 链
 * @param intentResult  意图分类结果（Agent 模式，nullable）
 * @param workspace     请求级工作区视图（Agent 模式，nullable）
 * @param chatModel     用量采集装饰器（Agent 模式，nullable）
 * @param toolCallbacks Agent 模式的工具回调（nullable；传入 ChatClient .tools() 以触发 ToolCallingChatOptions 创建）
 */
public record ModeChainResult(
    List<Advisor> chain,

    @Nullable IntentResult intentResult,
    @Nullable WorkspaceInfo workspace,
    @Nullable UsageRecordingChatModel chatModel,
    @Nullable ToolCallback[] toolCallbacks
) {
    public static ModeChainResult standard(List<Advisor> chain) {
        return new ModeChainResult(chain, null, null, null, null);
    }

    public static ModeChainResult agent(List<Advisor> chain,
                                         IntentResult intentResult,
                                         WorkspaceInfo workspace,
                                         UsageRecordingChatModel chatModel,
                                         ToolCallback[] toolCallbacks) {
        return new ModeChainResult(chain, intentResult, workspace, chatModel, toolCallbacks);
    }
}
