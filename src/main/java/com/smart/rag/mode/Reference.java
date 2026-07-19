package com.smart.rag.mode;

import org.jspecify.annotations.Nullable;

/**
 * 检索引用映射 — agent + chat 双路径统一。
 * <p>
 * 每条对应一个检索段，前端据此把正文中的「来源#n：文件名」渲染成可反查的超链接，
 * 并在引用卡片（ReferenceCard）中展示相关性得分、来源 Tool 和文本片段。
 *
 * @param refNumber  稳定编号 [n]，与检索段注入给 LLM 的编号一致
 * @param chunkId    vector_store.id（chunk 级）
 * @param documentId 所属文档 ID
 * @param fileName   文件名（缺失时为 documentId / "未知"）
 * @param page       页码（可空）
 * @param score      相关性得分（向量相似度 / RRF 融合分 / rerank 分，取决于检索路径）。
 *                   前端据此排序、高亮、展示置信度；agent 路径默认 0.0（未参与打分）。
 * @param source     检索来源 Tool 名（如 {@code hybridSearch} / {@code vectorSearch}），可空。
 *                   前端据此标注"来源：混合检索"等。
 * @param content    文本片段（截断后的 chunk 内容），可空。
 *                   <p>仅用于前端引用卡片预览，不参与 LLM prompt（prompt 里的内容走 refBlock）。
 *                   agent 路径可能为 null（workspace 检索结果在注入 prompt 后未单独保留）。
 */
public record Reference(
    int refNumber,
    String chunkId,
    String documentId,
    String fileName,
    @Nullable Integer page,
    double score,
    @Nullable String source,
    @Nullable String content
) {}
