package com.smart.rag.mode;

import org.jspecify.annotations.Nullable;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 流式执行结果 — 帧流 + 检索引用映射（design §2.10，R8）+ Agent 元数据/工作区 + 用量快照。
 * <p>
 * {@code frames} 是 {@link StreamFrame} 流（区分正文 CONTENT 与思考 REASONING），
 * 走 fallbackExecutor 的跨模型降级管道。references 由 chatStream 用 AtomicReference
 * 捕获最终成功模型的值。
 * <p>
 * Agent 模式额外携带：
 * <ul>
 *   <li>{@code agentMetadata}：intent/confidence 在 buildAdvisorChain 同步阶段就绪（订阅前）；
 *       retrievalRounds 初始为 0，由 chatStream 的外层 doOnComplete 在流结束后从 workspace 刷新为终值</li>
 *   <li>{@code workspace}：请求级工作区引用，供 doOnComplete 读取 retrievalRounds 终值 + 现场构建 references</li>
 * </ul>
 * <p>
 * {@code usageRef}（所有模式）：策略层在流 {@code doOnComplete} 写入 {@link StreamUsageSnapshot}
 * （token 累计 + 耗时），由 chatStream 转交 SSE 桥接层发 {@code event:usage} 尾帧。
 * reactor 语义保证策略层 doOnComplete 先于桥接层 onComplete（发帧）——与 agentMetadata
 * 终值刷新依赖的同一保证。
 *
 * @param frames        帧流（已走 advisor 链：Redis 记忆 load/save + RagContextAdvisor 动态尾注入）
 * @param references    标准模式检索引用（订阅前同步就绪）；Agent 模式为 null（改由 doOnComplete 从 workspace 构建）
 * @param agentMetadata Agent 模式元数据（intent/confidence 订阅前就绪；retrievalRounds 流后刷新）；非 Agent 模式为 null
 * @param workspace     Agent 模式工作区引用（供流后读取终值）；非 Agent 模式为 null
 * @param usageRef      流式用量快照（成功完成时写入；错误/取消保持空，不发 usage 帧）
 */
public record StreamResult(
    Flux<StreamFrame> frames,
    @Nullable List<Reference> references,
    @Nullable Map<String, Object> agentMetadata,
    @Nullable WorkspaceInfo workspace,
    AtomicReference<StreamUsageSnapshot> usageRef
) {
    public StreamResult(Flux<StreamFrame> frames, @Nullable List<Reference> references) {
        this(frames, references, null, null, new AtomicReference<>());
    }
}
