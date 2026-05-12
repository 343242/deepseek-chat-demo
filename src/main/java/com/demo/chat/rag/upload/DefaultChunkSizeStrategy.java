package com.demo.chat.rag.upload;

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
        if (fileSize < 5L * MB) {
            return (int) fileSize;  // 不分片
        }
        if (fileSize <= 100L * MB) {
            return 5 * MB;
        }
        if (fileSize <= 500L * MB) {
            return 10 * MB;
        }
        return 20 * MB;
    }
}
