package com.smart.rag.chat.service;

import com.smart.rag.chat.dto.Reference;
import org.jspecify.annotations.Nullable;
import reactor.core.publisher.Flux;

import java.util.List;

/**
 * 流式执行结果 — 内容流 + 检索引用映射（design §2.10，R8）。
 * <p>
 * references 是一次性检索元数据（不随流元素变化）；content Flux 走 fallbackExecutor 的
 * 跨模型降级管道，references 由 chatStream 用 AtomicReference 捕获最终成功模型的值。
 *
 * @param content    流式内容（已走 advisor 链：Redis 记忆 load/save + RagContextAdvisor 动态尾注入）
 * @param references 检索引用映射（#n → chunkId/documentId/fileName/page），非 RAG 时为 null
 */
public record StreamResult(Flux<String> content, @Nullable List<Reference> references) {}
