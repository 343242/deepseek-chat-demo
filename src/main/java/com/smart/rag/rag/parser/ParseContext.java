package com.smart.rag.rag.parser;

/**
 * 解析上下文 — 携带文档身份进入解析层（design §6.1）。
 * <p>
 * 现状 {@code DocumentParser.parse(Resource, String)} 无文档身份，而确定性图片 key
 * （{@code images/{documentId}/p{page+1}-{seq}.{ext}}）与占位符 URL 均需要 documentId。
 * 通过接口默认方法扩展，非 PDF 解析器零改动。
 *
 * @param documentId 文档 ID（图片 key 与占位符 URL 的事实源）
 * @param bucket     MinIO bucket（后台图片提取重新下载用）
 * @param objectKey  MinIO 对象 key
 * @param fileName   原始文件名（诊断用）
 */
public record ParseContext(Long documentId, String bucket, String objectKey, String fileName) {
}
