package com.smart.rag.mode;

import org.jspecify.annotations.Nullable;

/**
 * 检索引用映射 — agent + chat 双路径统一。
 * <p>
 * 每条对应一个检索段，前端据此把正文中的「来源#n：文件名」渲染成可反查的超链接。
 *
 * @param refNumber  稳定编号 [n]，与检索段注入给 LLM 的编号一致
 * @param chunkId    vector_store.id（chunk 级）
 * @param documentId 所属文档 ID
 * @param fileName   文件名（缺失时为 documentId / "未知"）
 * @param page       页码（可空）
 */
public record Reference(
    int refNumber,
    String chunkId,
    String documentId,
    String fileName,
    @Nullable Integer page
) {}
