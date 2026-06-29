package com.smart.rag.mcp.adapter;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.function.FunctionToolCallback;
import org.springframework.core.ParameterizedTypeReference;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * B1 行为验证：{@link FunctionToolCallback} 的 inputType / inputSchema 组合（源码级已推导，本测试坐实运行时）。
 * <p>
 * 设计见 {@code docs/MCP-CLIENT-INTEGRATION.md} §6.1 B1：MCP 工具 args 是远端定义的动态 JSON object，
 * adapter 必须用 {@code inputType(Map) + .inputSchema(MCP schema)}。源码（1.1.6）：
 * {@code FunctionToolCallback.call()} {@code :103} 执行 {@code JsonParser.fromJson(toolInput, toolInputType)}，
 * 而 {@code JsonParser.fromJson} 就是 {@code ObjectMapper.readValue(json, type)}。故：
 * <ul>
 *   <li>{@code inputType(String.class)} + JSON object args → readValue 遇 START_OBJECT 抛 MismatchedInputException（证伪）。</li>
 *   <li>{@code inputType(Map)} + JSON object args → 正常反序列化成 Map 喂 BiFunction（修正）。</li>
 *   <li>{@code .inputSchema(...)} 覆盖框架按 inputType 生成的 schema（getToolDefinition().inputSchema() 透传）。</li>
 * </ul>
 * <p>
 * 纯框架行为测试：无 MCP server、无模型、无 Spring context。
 */
@DisplayName("B1: FunctionToolCallback inputType/inputSchema 行为验证")
class FunctionToolCallbackB1Test {

    /** 模拟 MCP 工具的 inputSchema（远端定义的 JSON Schema，object 类型）。 */
    private static final String OBJECT_SCHEMA =
            "{\"type\":\"object\",\"properties\":{\"query\":{\"type\":\"string\"}},\"required\":[\"query\"]}";

    // ==================== inputType(String.class) —— 证伪（应抛） ====================

    @Nested
    @DisplayName("inputType(String.class) — 证伪：JSON object 无法反序列化为 String")
    class InputTypeStringFails {

        @Test
        @DisplayName("call_withJsonObjectArgs_throws: 按 object schema 产的 JSON object 让 call 抛异常")
        void call_withJsonObjectArgs_throws() {
            ToolCallback cb = FunctionToolCallback.<String, String>builder(
                            "knowledge_search",
                            (args, ctx) -> "should-not-reach: " + args)
                    .description("d")
                    .inputSchema(OBJECT_SCHEMA)
                    .inputType(String.class)
                    .build();

            // LLM 按 object schema 产出的 args 是 JSON object 串
            Exception ex = assertThrows(Exception.class, () -> cb.call("{\"query\":\"x\"}"),
                    "inputType(String.class) + JSON object args 必须抛异常（B1 证伪）");

            // 根因链：ToolExecutionException ← IllegalStateException("Conversion from JSON to java.lang.String failed")
            //         ← MismatchedInputException
            assertTrue(containsMessage(ex, "Conversion from JSON to java.lang.String failed")
                            || containsMessage(ex, "Cannot deserialize"),
                    () -> "expected JSON→String 转换失败，实际: " + rootMessage(ex));
        }

        @Test
        @DisplayName("call_withEmptyObject_throws: 即使空 object {} 也无法反序列化为 String")
        void call_withEmptyObject_throws() {
            ToolCallback cb = FunctionToolCallback.<String, String>builder(
                            "noop", (args, ctx) -> "x")
                    .description("d")
                    .inputSchema("{\"type\":\"object\",\"properties\":{}}")
                    .inputType(String.class)
                    .build();

            assertThrows(Exception.class, () -> cb.call("{}"));
        }
    }

    // ==================== inputType(Map) —— 修正（应正常） ====================

    @Nested
    @DisplayName("inputType(Map) — 修正：JSON object 正常反序列化成 Map")
    class InputTypeMapWorks {

        @Test
        @DisplayName("call_withJsonObjectArgs_passesMap: 框架把 JSON object 反序列化成 Map 传给 BiFunction")
        void call_withJsonObjectArgs_passesMap() {
            AtomicReference<Map<String, Object>> received = new AtomicReference<>();
            ToolCallback cb = FunctionToolCallback.<Map<String, Object>, String>builder(
                            "knowledge_search",
                            (args, ctx) -> {
                                received.set(args);
                                return "ok:" + args.get("query");
                            })
                    .description("d")
                    .inputSchema(OBJECT_SCHEMA)
                    .inputType(new ParameterizedTypeReference<Map<String, Object>>() {})
                    .build();

            String result = cb.call("{\"query\":\"x\"}");

            // ★ B1 修正的直接证据：BiFunction 收到的是 Map（不是 String），内容与 JSON object 一致
            assertNotNull(received.get(), "BiFunction 应收到非 null Map");
            assertEquals(1, received.get().size());
            assertEquals("x", received.get().get("query"));

            // 注意：ToolCallback.call() 的返回值经默认 ToolCallResultConverter 做 JSON 序列化，
            // 故 String "ok:x" 回流为带引号的 "ok:x"。这里只断言含子串（adapter 实现时据此知道
            // call() 返回的是 JSON-encoded 文本，render() 的产物会被序列化）。
            assertTrue(result.contains("ok:x"), () -> "result 应含 BiFunction 产出，实际: " + result);
        }

        @Test
        @DisplayName("call_withEmptyObject_passesEmptyMap: 空 object {} 走 Map 不抛")
        void call_withEmptyObject_passesEmptyMap() {
            AtomicReference<Map<String, Object>> received = new AtomicReference<>();
            ToolCallback cb = FunctionToolCallback.<Map<String, Object>, String>builder(
                            "noop", (args, ctx) -> {
                                received.set(args);
                                return "empty:" + args.size();
                            })
                    .description("d")
                    .inputSchema("{\"type\":\"object\",\"properties\":{}}")
                    .inputType(new ParameterizedTypeReference<Map<String, Object>>() {})
                    .build();

            String result = cb.call("{}");

            assertNotNull(received.get());
            assertTrue(received.get().isEmpty(), "空 object {} 应反序列化成空 Map");
            assertTrue(result.contains("empty:0"), () -> "result 应含 BiFunction 产出，实际: " + result);
        }
    }

    // ==================== inputSchema 透传 ====================

    @Nested
    @DisplayName("inputSchema 透传：自定义 schema 覆盖框架生成 schema")
    class InputSchemaPreserved {

        @Test
        @DisplayName("getToolDefinition_inputSchema_equalsProvided: 传 inputSchema 时 ToolDefinition 透传它")
        void getToolDefinition_inputSchema_equalsProvided() {
            ToolCallback cb = FunctionToolCallback.<Map<String, Object>, String>builder(
                            "t", (args, ctx) -> "x")
                    .description("d")
                    .inputSchema(OBJECT_SCHEMA)
                    .inputType(new ParameterizedTypeReference<Map<String, Object>>() {})
                    .build();

            assertEquals(OBJECT_SCHEMA, cb.getToolDefinition().inputSchema(),
                    "自定义 inputSchema 必须原样透传到 ToolDefinition");
        }

        @Test
        @DisplayName("inputTypeOnly_degradedSchema: 不传 inputSchema 时退化为 Map 生成 schema（反证 adapter 必须传）")
        void inputTypeOnly_degradedSchema() {
            ToolCallback cb = FunctionToolCallback.<Map<String, Object>, String>builder(
                            "t", (args, ctx) -> "x")
                    .description("d")
                    .inputType(new ParameterizedTypeReference<Map<String, Object>>() {})
                    .build();

            String generated = cb.getToolDefinition().inputSchema();
            assertNotEquals(OBJECT_SCHEMA, generated,
                    "不传 inputSchema 时框架按 Map 生成泛化 schema，不会是 MCP 真实 schema → adapter 必须显式传");
        }
    }

    // ==================== helpers ====================

    /** 沿 cause 链查找包含指定片段的 message。 */
    private static boolean containsMessage(Throwable t, String fragment) {
        while (t != null) {
            if (t.getMessage() != null && t.getMessage().contains(fragment)) {
                return true;
            }
            t = t.getCause();
        }
        return false;
    }

    /** 取根因的 类名 + message，用于失败断言的可读输出。 */
    private static String rootMessage(Throwable t) {
        Throwable cur = t;
        while (cur.getCause() != null && cur.getCause() != cur) {
            cur = cur.getCause();
        }
        return cur.getClass().getName() + ": " + cur.getMessage();
    }
}
