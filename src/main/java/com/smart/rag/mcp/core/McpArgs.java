package com.smart.rag.mcp.core;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * MCP 工具调用参数（领域值对象）。
 * <p>
 * 持 {@code Map<String,Object>}。<b>无 {@code fromJson}</b>——adapter 用 {@code inputType(Map)}
 * （B1），框架已把 LLM 的 JSON args 反序列化成 {@code Map} 传给 BiFunction，BiFunction 直接
 * {@code McpArgs.of(args)} 包成本类型；runtime 组装 {@code new CallToolRequest(rawName, args.asMap())}。
 */
public final class McpArgs {

    private final Map<String, Object> map;

    private McpArgs(Map<String, Object> map) {
        // defensive copy + unmodifiable：保留 null value（LLM JSON 可能有 null），但屏蔽外部变更
        this.map = Collections.unmodifiableMap(new LinkedHashMap<>(map));
    }

    /** 包装一个参数 Map（defensive copy）。 */
    public static McpArgs of(Map<String, Object> map) {
        Objects.requireNonNull(map, "map");
        return new McpArgs(map);
    }

    /** 空参数。 */
    public static McpArgs empty() {
        return new McpArgs(Map.of());
    }

    /** 返回不可变视图，喂 {@code CallToolRequest}。 */
    public Map<String, Object> asMap() {
        return map;
    }
}
