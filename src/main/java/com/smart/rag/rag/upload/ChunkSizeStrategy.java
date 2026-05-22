package com.smart.rag.rag.upload;

/**
 * 分片大小策略接口。
 * <p>
 * 根据文件总大小计算每片大小。OCP：新增策略只需实现此接口，不改旧类。
 */
@FunctionalInterface
public interface ChunkSizeStrategy {

    /**
     * 根据文件大小计算分片大小（bytes）。
     *
     * @param fileSize 文件总大小（bytes）
     * @return 分片大小（bytes）。若返回值 >= fileSize，表示不分片。
     */
    int calculateChunkSize(long fileSize);
}
