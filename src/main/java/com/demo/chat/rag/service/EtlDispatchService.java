package com.demo.chat.rag.service;

import com.demo.chat.rag.etl.EtlCandidate;
import com.demo.chat.rag.etl.EtlResult;

import java.util.List;

/**
 * ETL 调度服务 — 路由 + 并发编排的入口
 * <p>
 * 根据文档特征（数量、大小）自动选择策略：
 * <ul>
 *   <li>小文档（≤10 个且 ≤5MB）→ FastTrackStrategy（BM25 先行 + 异步向量化）</li>
 *   <li>其他 → StandardStrategy（并发 Extract/Transform/Load）</li>
 * </ul>
 */
public interface EtlDispatchService {

    /**
     * 批量调度 ETL 处理
     *
     * @param candidates 待处理的文档候选列表
     * @return 处理结果列表
     */
    List<EtlResult> dispatch(List<EtlCandidate> candidates);

    /**
     * 单文档同步执行（保持向后兼容）
     *
     * @param documentId 文档 ID
     * @param bucket     MinIO bucket
     * @param objectKey  MinIO object key
     * @param fileName   文件名
     * @param mimeType   MIME 类型
     * @param fileSize   文件大小（字节），用于路由策略判定
     * @param userId     文档所有者 ID，用于向量库检索隔离
     * @return 分块数量
     */
    int executeSingle(Long documentId, String bucket, String objectKey, String fileName, String mimeType, long fileSize, Long userId);
}
