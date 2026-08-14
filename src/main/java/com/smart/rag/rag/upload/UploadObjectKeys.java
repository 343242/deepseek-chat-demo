package com.smart.rag.rag.upload;

/**
 * 上传模块 MinIO 对象路径常量。
 * <p>
 * 单一数据源：临时分片前缀（{@code chunks/}）、文档前缀（{@code documents/}）、
 * 分片对象后缀（{@code /part-{index}}）统一在此定义，供
 * {@code ChunkUploadServiceImpl}、{@code ChunkMergeService} 与
 * {@code OrphanChunkCleaner} 共享，禁止在业务代码中硬编码。
 */
public final class UploadObjectKeys {

    private UploadObjectKeys() {}

    /** 临时分片对象前缀：chunks/{userId}/{uploadId}/part-{index} */
    public static final String CHUNKS_PREFIX = "chunks/";

    /** 合并后文档对象前缀：documents/{userId}/{shortId}_{fileName} */
    public static final String DOCUMENTS_PREFIX = "documents/";

    /** 分片对象 key 后缀（拼接在会话 objectName 基路径之后） */
    public static final String PART_SUFFIX = "/part-";

    /**
     * 构建分片对象 key：{basePath}/part-{chunkIndex}
     */
    public static String chunkObjectKey(String basePath, int chunkIndex) {
        return basePath + PART_SUFFIX + chunkIndex;
    }
}
