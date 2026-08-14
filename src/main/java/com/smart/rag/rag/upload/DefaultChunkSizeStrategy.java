package com.smart.rag.rag.upload;

import org.springframework.stereotype.Component;

/**
 * 默认分片大小策略。
 * <p>
 * 策略表：
 * <ul>
 *   <li>&lt; 5 MB：不分片（返回 fileSize）</li>
 *   <li>5 MB ~ 100 MB：5 MB</li>
 *   <li>100 MB ~ 500 MB：10 MB</li>
 *   <li>&gt; 500 MB：20 MB</li>
 * </ul>
 * <p>
 * 约束：totalChunks ≤ 10000（S3 限制），分片大小是 1 MB 整数倍。
 */
@Component
public class DefaultChunkSizeStrategy implements ChunkSizeStrategy {

    private static final int MB = 1024 * 1024;
    private static final int MAX_TOTAL_CHUNKS = 10_000;

    /** 不分片阈值：小于此大小整体作为一个分片 */
    private static final long NO_CHUNK_THRESHOLD = 5L * MB;
    /** 小文件上限：此范围内使用 5 MB 分片 */
    private static final long SMALL_FILE_MAX = 100L * MB;
    /** 中文件上限：此范围内使用 10 MB 分片 */
    private static final long MEDIUM_FILE_MAX = 500L * MB;

    private static final int SMALL_FILE_CHUNK_SIZE = 5 * MB;
    private static final int MEDIUM_FILE_CHUNK_SIZE = 10 * MB;
    private static final int LARGE_FILE_CHUNK_SIZE = 20 * MB;

    @Override
    public int calculateChunkSize(long fileSize) {
        int chunkSize = resolveChunkSize(fileSize);

        // 保证 totalChunks ≤ 10000
        while ((fileSize + chunkSize - 1) / chunkSize > MAX_TOTAL_CHUNKS) {
            chunkSize += MB;
        }

        return chunkSize;
    }

    private int resolveChunkSize(long fileSize) {
        if (fileSize < NO_CHUNK_THRESHOLD) {
            return (int) fileSize;  // 不分片
        }
        if (fileSize <= SMALL_FILE_MAX) {
            return SMALL_FILE_CHUNK_SIZE;
        }
        if (fileSize <= MEDIUM_FILE_MAX) {
            return MEDIUM_FILE_CHUNK_SIZE;
        }
        return LARGE_FILE_CHUNK_SIZE;
    }
}
