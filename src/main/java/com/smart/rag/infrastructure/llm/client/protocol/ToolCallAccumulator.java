package com.smart.rag.infrastructure.llm.client.protocol;

import com.smart.rag.infrastructure.llm.StreamChunk;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * SSE 单轮 tool_call 累积器（design §0 #3：累积职责归 GenericChatClient SSE 解析层，ChatModelAdapter 不再累积）。
 * <p>
 * OpenAI 流式 tool_calls 按 {@code index} 分片：首片携带 {@code id} + {@code function.name}，
 * 后续片仅携带 {@code function.arguments} 的流式 JSON 片段。本类按 index 合并：
 * {@code id}/{@code name} 首次设置后不变，{@code arguments} 拼接（流式 JSON 在 finishReason 前不解析）。
 * <p>
 * 生命周期：单次 {@code readSse} 调用 = 单轮 = 一个实例；轮末 {@link #drain()} 发完整 toolCalls 后丢弃。
 * Spring AI {@code ToolCallAdvisor} 续轮 ReAct 会重新 {@code chatStream} → 新 readSse → 新实例。
 */
final class ToolCallAccumulator {

    private final Map<Integer, Acc> byIndex = new LinkedHashMap<>();

    /** 合并一片 tool_call delta（index 标识，id/name 首次设置，arguments 拼接）。 */
    void merge(int index, String id, String name, String arguments) {
        Acc a = byIndex.get(index);
        if (a == null) {
            byIndex.put(index, new Acc(id, name, new StringBuilder(arguments != null ? arguments : "")));
        } else {
            if (a.id == null && id != null) a.id = id;
            if (a.name == null && name != null) a.name = name;
            if (arguments != null && !arguments.isEmpty()) a.args.append(arguments);
        }
    }

    /** 取出完整 toolCalls（按 index 顺序）；累积器用尽。无累积返回空 List。 */
    List<StreamChunk.ToolCallDelta> drain() {
        if (byIndex.isEmpty()) return List.of();
        List<StreamChunk.ToolCallDelta> out = new ArrayList<>(byIndex.size());
        for (Map.Entry<Integer, Acc> e : byIndex.entrySet()) {
            Acc a = e.getValue();
            out.add(new StreamChunk.ToolCallDelta(e.getKey(), a.id, a.name, a.args.toString()));
        }
        byIndex.clear();
        return out;
    }

    private static final class Acc {
        String id;
        String name;
        final StringBuilder args;
        Acc(String id, String name, StringBuilder args) {
            this.id = id; this.name = name; this.args = args;
        }
    }
}
