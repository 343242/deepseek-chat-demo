package com.smart.rag.rag.agent.poc;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.model.tool.DefaultToolCallingManager;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.function.FunctionToolCallback;
import org.springframework.ai.tool.resolution.StaticToolCallbackResolver;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;

/**
 * PoC 1: 验证 FunctionToolCallback.builder() 的泛型签名与闭包传递可行性。
 *
 * 设计文档假设:
 *   FunctionToolCallback.<I, O>builder(String name, BiFunction<I, ToolContext, O> fn)
 *
 * 验证项:
 *   1. BiFunction 签名是否接受 ToolContext
 *   2. 闭包能否捕获外部变量（模拟 workspace）
 *   3. 创建的 callback 能否被 StaticToolCallbackResolver 包装
 *   4. 能否被 DefaultToolCallingManager 正确解析和调用
 */
@DisplayName("PoC 1: FunctionToolCallback.builder() 签名验证")
class Poc1_FunctionToolCallbackSignatureTest {

    @Nested
    @DisplayName("签名验证")
    class SignatureVerification {

        @Test
        @DisplayName("builder 接受 BiFunction<String, ToolContext, String>")
        void builderAcceptsBiFunction() {
            BiFunction<String, ToolContext, String> fn = (input, ctx) -> "result: " + input;

            FunctionToolCallback<String, String> callback = FunctionToolCallback
                .<String, String>builder("testTool", fn)
                .description("test tool")
                .inputType(String.class)
                .build();

            assertThat(callback).isNotNull();
            assertThat(callback.getToolDefinition().name()).isEqualTo("testTool");
        }

        @Test
        @DisplayName("ToolContext 可能为 null — 闭包需防御性处理")
        void toolContextMayBeNull() {
            BiFunction<String, ToolContext, String> fn = (input, ctx) -> {
                assertThat(ctx).isNull();
                return "ok";
            };

            FunctionToolCallback<String, String> callback = FunctionToolCallback
                .<String, String>builder("nullCtxTool", fn)
                .description("test")
                .inputType(String.class)
                .build();

            // call(String) 内部通过 ToolCallResultConverter JSON 序列化返回值
            // String "ok" → "\"ok\""
            String result = callback.call("\"hello\"");
            assertThat(result).isEqualTo("\"ok\"");
        }

        @Test
        @DisplayName("ToolContext 非空时 — 可从 context map 读取数据")
        void toolContextWithMap() {
            BiFunction<String, ToolContext, String> fn = (input, ctx) -> {
                if (ctx != null && ctx.getContext() != null) {
                    Object userId = ctx.getContext().get("userId");
                    return "userId=" + userId + ", input=" + input;
                }
                return "no context";
            };

            FunctionToolCallback<String, String> callback = FunctionToolCallback
                .<String, String>builder("ctxTool", fn)
                .description("test")
                .inputType(String.class)
                .build();

            ToolContext toolContext = new ToolContext(Map.of("userId", 42));
            String result = callback.call("\"query\"", toolContext);
            assertThat(result).contains("userId=42");
        }
    }

    @Nested
    @DisplayName("闭包捕获 workspace 模拟")
    class ClosureCaptureSimulation {

        @Test
        @DisplayName("闭包可捕获外部局部变量（模拟 ToolWorkspace）")
        void closureCapturesLocalVariable() {
            // 模拟 workspace — 请求级局部变量
            AtomicReference<String> workspace = new AtomicReference<>("initial-state");

            BiFunction<String, ToolContext, String> fn = (input, ctx) -> {
                String currentState = workspace.get();
                workspace.set("updated: " + input);
                return "state was: " + currentState;
            };

            FunctionToolCallback<String, String> callback = FunctionToolCallback
                .<String, String>builder("closureTool", fn)
                .description("test")
                .inputType(String.class)
                .build();

            // call("\"first\"") → JSON "first" → deserialized to first (no quotes)
            // ToolCallResultConverter JSON 序列化返回值
            String result1 = callback.call("\"first\"");
            assertThat(result1).isEqualTo("\"state was: initial-state\"");
            assertThat(workspace.get()).isEqualTo("updated: first");

            // 第二次调用 — 闭包共享同一个 workspace 引用
            String result2 = callback.call("\"second\"");
            assertThat(result2).isEqualTo("\"state was: updated: first\"");
            assertThat(workspace.get()).isEqualTo("updated: second");
        }

        @Test
        @DisplayName("多个 Tool 闭包可共享同一个 workspace")
        void multipleToolClosuresShareWorkspace() {
            AtomicReference<String> workspace = new AtomicReference<>("empty");

            BiFunction<String, ToolContext, String> searchFn = (input, ctx) -> {
                workspace.set("searched: " + input);
                return "search done";
            };

            BiFunction<String, ToolContext, String> rerankFn = (input, ctx) -> {
                String prev = workspace.get();
                workspace.set("reranked after: " + prev);
                return "rerank done, prev was: " + prev;
            };

            var searchTool = FunctionToolCallback
                .<String, String>builder("search", searchFn)
                .description("search").inputType(String.class).build();

            var rerankTool = FunctionToolCallback
                .<String, String>builder("rerank", rerankFn)
                .description("rerank").inputType(String.class).build();

            searchTool.call("\"query1\"");
            String rerankResult = rerankTool.call("\"top5\"");

            // ToolCallResultConverter JSON 序列化: 字符串结果被引号包裹
            // call("\"query1\"") → input deserialized to query1 (no quotes)
            assertThat(rerankResult).contains("searched: query1");
            assertThat(workspace.get()).isEqualTo("reranked after: searched: query1");
        }
    }

    @Nested
    @DisplayName("StaticToolCallbackResolver + DefaultToolCallingManager 集成")
    class ResolverAndManagerIntegration {

        @Test
        @DisplayName("闭包 callback 可被 StaticToolCallbackResolver 包装")
        void staticResolverCanWrapCallback() {
            AtomicReference<String> workspace = new AtomicReference<>("");

            var tool = FunctionToolCallback
                .<String, String>builder("resolvableTool", (input, ctx) -> {
                    workspace.set("executed");
                    return "done";
                })
                .description("test")
                .inputType(String.class)
                .build();

            StaticToolCallbackResolver resolver = new StaticToolCallbackResolver(List.of(tool));
            ToolCallback resolved = resolver.resolve("resolvableTool");

            assertThat(resolved).isNotNull();
            assertThat(resolved.getToolDefinition().name()).isEqualTo("resolvableTool");

            resolved.call("\"test\"");
            assertThat(workspace.get()).isEqualTo("executed");
        }

        @Test
        @DisplayName("DefaultToolCallingManager 可通过内部 resolver 解析闭包 callback")
        void defaultManagerUsesResolverInternally() {
            AtomicReference<String> workspace = new AtomicReference<>("initial");

            var tool = FunctionToolCallback
                .<String, String>builder("managedTool", (input, ctx) -> {
                    workspace.set("managed: " + input);
                    return "managed result";
                })
                .description("test")
                .inputType(String.class)
                .build();

            // DefaultToolCallingManager 内部通过 ToolCallbackResolver 解析
            // 它没有公开的 resolveTool() 方法，resolveToolDefinitions() 接受 ToolCallingChatOptions
            StaticToolCallbackResolver resolver = new StaticToolCallbackResolver(List.of(tool));

            // 直接通过 resolver 验证（这也是 DefaultToolCallingManager 内部使用的路径）
            ToolCallback resolved = resolver.resolve("managedTool");
            assertThat(resolved).isNotNull();

            // ToolCallResultConverter JSON 序列化: String → "\"...\""
            String result = resolved.call("\"hello\"");
            assertThat(result).isEqualTo("\"managed result\"");
            assertThat(workspace.get()).isEqualTo("managed: hello");

            // 验证 DefaultToolCallingManager 可被构建（内部持有 resolver）
            DefaultToolCallingManager manager = DefaultToolCallingManager.builder()
                .toolCallbackResolver(resolver)
                .build();
            assertThat(manager).isNotNull();
        }
    }

    @Nested
    @DisplayName("ToolMetadata.returnDirect 验证")
    class ReturnDirectVerification {

        @Test
        @DisplayName("可设置 returnDirect = true")
        void canSetReturnDirect() {
            var tool = FunctionToolCallback
                .<String, String>builder("directTool", (input, ctx) -> "direct result")
                .description("test")
                .inputType(String.class)
                .toolMetadata(org.springframework.ai.tool.metadata.ToolMetadata.builder()
                    .returnDirect(true)
                    .build())
                .build();

            assertThat(tool.getToolMetadata().returnDirect()).isTrue();
        }
    }

    @Test
    @DisplayName("综合: 完整的闭包传递路径验证")
    void fullClosurePathVerification() {
        // 模拟完整的 workspace 传递路径
        record ToolWorkspace(String userId, String state) {
            ToolWorkspace update(String newState) {
                return new ToolWorkspace(userId, newState);
            }
        }

        // 创建 workspace 局部变量
        var workspace = new AtomicReference<>(new ToolWorkspace("user-42", "created"));

        // 搜索 Tool 闭包捕获 workspace
        var searchTool = FunctionToolCallback
            .<String, String>builder("hybridSearch", (input, ctx) -> {
                var ws = workspace.get();
                workspace.set(ws.update("searched: " + input));
                return """
                    {"status":"success","action":"hybridSearch","docCount":5}
                    """;
            })
            .description("Hybrid search combining vector and BM25")
            .inputType(String.class)
            .build();

        // Rerank Tool 闭包捕获同一个 workspace
        var rerankTool = FunctionToolCallback
            .<String, String>builder("rerank", (input, ctx) -> {
                var ws = workspace.get();
                assertThat(ws.state()).startsWith("searched:");
                workspace.set(ws.update("reranked"));
                return """
                    {"status":"success","action":"rerank","docCount":3}
                    """;
            })
            .description("Semantic rerank")
            .inputType(String.class)
            .build();

        // 组装到 StaticToolCallbackResolver（与 DefaultToolCallingManager 内部路径一致）
        StaticToolCallbackResolver resolver = new StaticToolCallbackResolver(
            List.of(searchTool, rerankTool));

        // 执行搜索
        ToolCallback searchResolved = resolver.resolve("hybridSearch");
        String searchResult = searchResolved.call("\"RAG vs fine-tuning\"");
        assertThat(searchResult).contains("\"docCount\":5");

        // 执行 rerank — 验证闭包间 workspace 状态传递
        ToolCallback rerankResolved = resolver.resolve("rerank");
        String rerankResult = rerankResolved.call("\"top 5 docs\"");
        assertThat(rerankResult).contains("\"docCount\":3");

        // 最终 workspace 状态
        assertThat(workspace.get().state()).isEqualTo("reranked");
        assertThat(workspace.get().userId()).isEqualTo("user-42");
    }
}
