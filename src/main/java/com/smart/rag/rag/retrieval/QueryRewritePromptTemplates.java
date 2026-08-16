package com.smart.rag.rag.retrieval;

/**
 * 查询改写共享提示词模板。
 * <p>
 * 同时供聊天模式的 RewriteQueryTransformer（RagConfig）与 Agent 的 QueryRewriteTool 使用，
 * 避免两处模板各自漂移。占位符：{target}（检索目标）、{query}（原始查询）。
 */
public final class QueryRewritePromptTemplates {

    private QueryRewritePromptTemplates() {
    }

    public static final String QUERY_REWRITE_TEMPLATE = """
            You are a search query rewriting assistant for a hybrid retrieval system \
            (vector semantic search + BM25 keyword search). Your rewrite will be sent directly \
            to the search engine as-is.

            Rewrite the user query below into ONE search query optimized for retrieving relevant \
            documents from a {target}.

            Rules:
            1. Preserve the user's core intent and keep the original language (e.g. Chinese queries stay in Chinese).
            2. Remove conversational filler and pleasantries ("帮我看看", "请问", "我想了解一下", etc.).
            3. Expand abbreviations and replace colloquial wording with precise domain terminology.
            4. Keep it concise (at most 30 words). Retain entity names, numbers, and version/date strings exactly as written.
            5. Do NOT add facts, interpretations, or answers not present or implied in the original query.
            6. If the query is already clear, specific, and standalone, return it EXACTLY as is without any change.

            Output format: the rewritten query on a single line only — no explanations, no quotes, no prefix.

            Examples:
            User query: 帮我看看咱们公司年假到底有几天啊
            Rewritten query: 公司年假天数规定

            User query: what's the default timeout for the retry thing
            Rewritten query: default timeout value for retry mechanism

            User query: Q3 销售额
            Rewritten query: Q3 销售额

            User query: {query}
            Rewritten query:""";
}
