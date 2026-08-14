package com.smart.rag.rag.upload;

import java.security.SecureRandom;
import java.util.Random;

/**
 * 存储对象 key 生成工具（{@code ChunkUploadServiceImpl} 与
 * {@code PersonalUploadStrategy} 共享，消除重复实现）。
 */
public final class StorageKeys {

    private static final char[] NANOID_CHARS =
            "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ".toCharArray();
    private static final Random RANDOM = new SecureRandom();

    private StorageKeys() {}

    /**
     * 生成指定长度的随机字母数字短 ID（Nanoid 风格）。
     */
    public static String generateShortId(int length) {
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(NANOID_CHARS[RANDOM.nextInt(NANOID_CHARS.length)]);
        }
        return sb.toString();
    }

    /**
     * 净化文件名为安全的 MinIO object key 片段。R1-L3: 仅替换路径分隔符（/ \）与 NUL，
     * 刻意保留 {@code ..} —— object key 是扁平字符串而非文件系统路径，{@code ..} 不会触发
     * 目录穿越；实际存储路径为 {@code documents/{userId}/{shortId}_{name}}，userId/shortId
     * 由服务端生成，用户无法借此逃逸到他人目录。
     */
    public static String sanitizeFilename(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return "unnamed";
        }
        return fileName.replace("/", "_").replace("\\", "_").replace("\0", "_");
    }

    /**
     * 构建文档对象 key：documents/{userId}/{shortId}_{原始文件名}。
     * <p>
     * shortId 为 8 位随机字母数字，用于避免同名文件冲突。
     */
    public static String documentObjectKey(Long userId, String originalFilename) {
        return UploadObjectKeys.DOCUMENTS_PREFIX + userId + "/"
                + generateShortId(8) + "_" + sanitizeFilename(originalFilename);
    }
}
