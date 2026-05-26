package com.smart.rag.rag.agent.tool.callback;

import com.smart.rag.chat.tool.ToolRegistry;
import com.smart.rag.rag.agent.intent.AgentIntent;
import com.smart.rag.rag.agent.tool.*;
import com.smart.rag.rag.agent.workspace.ToolWorkspace;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.function.FunctionToolCallback;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Agent Tool 回调工厂 -- 根据意图动态决定暴露给 LLM 的 Tool 子集
 * <p>
 * 通过闭包捕获 {@link ToolWorkspace} 局部变量，创建 {@link FunctionToolCallback}。
 * 每个请求调用 {@link #createToolCallbacks(AgentIntent, ToolWorkspace)}，
 * 返回的 ToolCallback 数组通过 StaticToolCallbackResolver 注入到独立的 ToolCallAdvisor。
 * <p>
 * 意图->Tool 映射：
 * <table>
 *   <tr><th>意图</th><th>Tools</th></tr>
 *   <tr><td>DIRECT_ANSWER</td><td>无</td></tr>
 *   <tr><td>RETRIEVAL</td><td>hybridSearch, rerank, docDetail, knowledgeBaseInfo, agentEventLookup</td></tr>
 *   <tr><td>DEEP_RETRIEVAL</td><td>vectorSearch, hybridSearch, bm25Search, rerank, queryRewrite, parentDocLookup, docDetail, knowledgeBaseInfo, agentEventLookup</td></tr>
 *   <tr><td>GENERAL_TOOL</td><td>Calculator, DateTime 等通用工具</td></tr>
 * </table>
 */
@Component
public class AgentToolCallbackFactory {

    private static final Logger log = LoggerFactory.getLogger(AgentToolCallbackFactory.class);

    private final HybridSearchTool hybridSearchTool;
    private final VectorSearchTool vectorSearchTool;
    private final Bm25SearchTool bm25SearchTool;
    private final RerankTool rerankTool;
    private final QueryRewriteTool queryRewriteTool;
    private final ParentDocLookupTool parentDocLookupTool;
    private final DocDetailTool docDetailTool;
    private final KnowledgeBaseInfoTool knowledgeBaseInfoTool;
    private final AgentEventLookupTool agentEventLookupTool;
    private final ToolCallback[] generalToolCallbacks;

    public AgentToolCallbackFactory(HybridSearchTool hybridSearchTool,
                                    VectorSearchTool vectorSearchTool,
                                    Bm25SearchTool bm25SearchTool,
                                    RerankTool rerankTool,
                                    QueryRewriteTool queryRewriteTool,
                                    ParentDocLookupTool parentDocLookupTool,
                                    DocDetailTool docDetailTool,
                                    KnowledgeBaseInfoTool knowledgeBaseInfoTool,
                                    AgentEventLookupTool agentEventLookupTool,
                                    ToolRegistry toolRegistry) {
        this.hybridSearchTool = hybridSearchTool;
        this.vectorSearchTool = vectorSearchTool;
        this.bm25SearchTool = bm25SearchTool;
        this.rerankTool = rerankTool;
        this.queryRewriteTool = queryRewriteTool;
        this.parentDocLookupTool = parentDocLookupTool;
        this.docDetailTool = docDetailTool;
        this.knowledgeBaseInfoTool = knowledgeBaseInfoTool;
        this.agentEventLookupTool = agentEventLookupTool;
        this.generalToolCallbacks = toolRegistry.getToolCallbacks();
    }

    /**
     * 根据意图创建动态 Tool 回调数组
     * <p>
     * 每个 FunctionToolCallback 的 lambda 闭包捕获 request 级 workspace 局部变量。
     *
     * @param intent    意图分类结果
     * @param workspace 请求级 ToolWorkspace（闭包捕获）
     * @return 按意图过滤后的 ToolCallback 数组
     */
    public ToolCallback[] createToolCallbacks(AgentIntent intent, ToolWorkspace workspace) {
        ToolCallback[] callbacks = switch (intent) {
            case RETRIEVAL -> buildRetrievalToolSet(workspace);
            case DEEP_RETRIEVAL -> buildDeepRetrievalToolSet(workspace);
            case GENERAL_TOOL -> buildGeneralToolSet();
            case DIRECT_ANSWER -> new ToolCallback[]{};
        };

        log.debug("Created {} tool callbacks for intent {}", callbacks.length, intent);
        return callbacks;
    }

    // === RETRIEVAL 工具集 ===

    private ToolCallback[] buildRetrievalToolSet(ToolWorkspace workspace) {
        List<ToolCallback> tools = new ArrayList<>();
        tools.add(buildHybridSearch(workspace));
        tools.add(buildRerank(workspace));
        tools.add(buildDocDetail(workspace));
        tools.add(buildKnowledgeBaseInfo(workspace));
        tools.add(buildAgentEventLookup(workspace));
        return tools.toArray(ToolCallback[]::new);
    }

    // === DEEP_RETRIEVAL 工具集 ===

    private ToolCallback[] buildDeepRetrievalToolSet(ToolWorkspace workspace) {
        List<ToolCallback> tools = new ArrayList<>();
        tools.add(buildVectorSearch(workspace));
        tools.add(buildHybridSearch(workspace));
        tools.add(buildBm25Search(workspace));
        tools.add(buildRerank(workspace));
        tools.add(buildQueryRewrite(workspace));
        tools.add(buildParentDocLookup(workspace));
        tools.add(buildDocDetail(workspace));
        tools.add(buildKnowledgeBaseInfo(workspace));
        tools.add(buildAgentEventLookup(workspace));
        return tools.toArray(ToolCallback[]::new);
    }

    // === GENERAL_TOOL 工具集 ===

    private ToolCallback[] buildGeneralToolSet() {
        return Arrays.copyOf(generalToolCallbacks, generalToolCallbacks.length);
    }

    // === 各 Tool 的闭包构建方法 ===

    private ToolCallback buildHybridSearch(ToolWorkspace workspace) {
        return FunctionToolCallback.<String, String>builder(
                "hybridSearch",
                (request, ctx) -> hybridSearchTool.execute(request, null, workspace)
            )
            .description("混合检索：结合向量语义搜索和 BM25 关键词搜索，通过 RRF 融合排序。输入查询文本。")
            .inputType(String.class)
            .build();
    }

    private ToolCallback buildVectorSearch(ToolWorkspace workspace) {
        return FunctionToolCallback.<String, String>builder(
                "vectorSearch",
                (request, ctx) -> vectorSearchTool.execute(request, workspace)
            )
            .description("纯向量语义检索，适用于概念性查询。输入查询文本。")
            .inputType(String.class)
            .build();
    }

    private ToolCallback buildRerank(ToolWorkspace workspace) {
        return FunctionToolCallback.<String, String>builder(
                "rerank",
                (request, ctx) -> rerankTool.execute(request, workspace)
            )
            .description("对已检索文档进行语义精排。输入用于精排的查询文本。前提：workspace 中必须有已检索的文档。")
            .inputType(String.class)
            .build();
    }

    private ToolCallback buildQueryRewrite(ToolWorkspace workspace) {
        return FunctionToolCallback.<String, String>builder(
                "queryRewrite",
                (request, ctx) -> queryRewriteTool.execute(request, workspace)
            )
            .description("改写查询以提升检索效果，支持多角度改写生成多个变体。输入原始查询文本。")
            .inputType(String.class)
            .build();
    }

    private ToolCallback buildParentDocLookup(ToolWorkspace workspace) {
        return FunctionToolCallback.<String, String>builder(
                "parentDocLookup",
                (request, ctx) -> parentDocLookupTool.execute(workspace)
            )
            .description("将检索到的文档片段替换为其所属的完整父文档。无需输入参数。前提：workspace 中必须有含父子关系的文档。")
            .inputType(String.class)
            .build();
    }

    private ToolCallback buildDocDetail(ToolWorkspace workspace) {
        return FunctionToolCallback.<String, String>builder(
                "docDetail",
                (request, ctx) -> docDetailTool.execute(request, null, workspace)
            )
            .description("按需获取文档详情片段。输入文档 ID（逗号分隔）。")
            .inputType(String.class)
            .build();
    }

    private ToolCallback buildAgentEventLookup(ToolWorkspace workspace) {
        return FunctionToolCallback.<String, String>builder(
                "agentEventLookup",
                (request, ctx) -> agentEventLookupTool.execute(request, null, workspace)
            )
            .description("查找 Agent 历史事件，用于会话连续性。输入查询文本。")
            .inputType(String.class)
            .build();
    }

    private ToolCallback buildBm25Search(ToolWorkspace workspace) {
        return FunctionToolCallback.<String, String>builder(
                "bm25Search",
                (request, ctx) -> bm25SearchTool.execute(request, workspace)
            )
            .description("BM25 关键词全文检索，适用于精确关键词匹配。输入查询文本。")
            .inputType(String.class)
            .build();
    }

    private ToolCallback buildKnowledgeBaseInfo(ToolWorkspace workspace) {
        return FunctionToolCallback.<String, String>builder(
                "knowledgeBaseInfo",
                (request, ctx) -> knowledgeBaseInfoTool.execute(request, workspace)
            )
            .description("查询知识库元信息（文档数量等），帮助判断知识库规模。无需输入参数。")
            .inputType(String.class)
            .build();
    }
}
