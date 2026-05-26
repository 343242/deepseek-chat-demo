package com.smart.rag.rag.agent.tool;

import com.smart.rag.rag.agent.dto.ToolResult;
import com.smart.rag.rag.agent.workspace.ToolWorkspace;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 查询改写 Tool -- 改写查询以提升检索效果
 * <p>
 * 复用 RewriteQueryTransformer 的 prompt 模板，通过 ChatClient 调用 LLM 改写查询。
 * 改写结果写入 workspace.addRewrittenQuery()，返回改写后的查询文本供 LLM 使用。
 */
@Component
public class QueryRewriteTool implements RagTool {

    private static final Logger log = LoggerFactory.getLogger(QueryRewriteTool.class);

    private static final String REWRITE_PROMPT_TEMPLATE = """
            Given the following user query, rewrite it into a clear and specific search query \
            suitable for querying a {target}. Keep the core intent, remove conversational filler, \
            and use precise terminology.

            IMPORTANT: If the query is already clear, specific, and standalone, return it EXACTLY as is.
            Do NOT over-elaborate short factual queries.

            Original query: {query}

            Rewritten search query:""";

    private final ChatClient chatClient;

    public QueryRewriteTool(ChatClient.Builder chatClientBuilder) {
        this.chatClient = chatClientBuilder.build();
    }

    /**
     * 执行查询改写
     *
     * @param queryText 原始查询文本
     * @param workspace 闭包捕获的 workspace 局部变量
     * @return JSON 格式的 ToolResult
     */
    public String execute(String queryText, ToolWorkspace workspace) {
        long start = System.currentTimeMillis();
        try {
            if (queryText == null || queryText.isBlank()) {
                return ToolResult.failure("queryRewrite",
                    "查询文本不能为空", "INVALID_INPUT", 0).toJson();
            }

            // 使用 PromptTemplate 渲染改写提示
            PromptTemplate promptTemplate = new PromptTemplate(REWRITE_PROMPT_TEMPLATE);
            String renderedPrompt = promptTemplate.render(Map.of(
                "target", "knowledge base",
                "query", queryText
            ));

            // 调用 LLM 进行改写
            String rewrittenQuery = chatClient.prompt()
                .user(renderedPrompt)
                .call()
                .content();

            if (rewrittenQuery == null || rewrittenQuery.isBlank()) {
                log.warn("Query rewrite returned empty result, using original query");
                rewrittenQuery = queryText;
            }

            // 清理 LLM 返回中可能的引号包裹
            rewrittenQuery = rewrittenQuery.trim()
                .replaceAll("^[\"'`]+|[\"'`]+$", "")
                .trim();

            // 如果改写结果与原始查询完全相同（LLM 判断无需改写），保留原文
            if (rewrittenQuery.isBlank()) {
                rewrittenQuery = queryText;
            }

            // 写入 workspace
            workspace.addRewrittenQuery(rewrittenQuery);

            long duration = System.currentTimeMillis() - start;
            log.debug("Query rewrite: inputLen={}, outputLen={}, duration={}ms",
                queryText.length(), rewrittenQuery.length(), duration);

            return ToolResult.success("queryRewrite",
                "查询改写完成：\"" + queryText + "\" -> \"" + rewrittenQuery + "\"。请使用改写后的查询进行检索。",
                null, duration).toJson();

        } catch (Exception e) {
            long duration = System.currentTimeMillis() - start;
            log.error("Query rewrite error", e);
            return ToolResult.failure("queryRewrite",
                ToolErrorMessages.rewriteFailed(),
                "API_ERROR", duration).toJson();
        }
    }
}
