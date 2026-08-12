package com.smart.rag.common.util;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * SHA-256 校验和工具 —— 纯 JDK 实现（{@link MessageDigest} + {@link HexFormat}），无第三方依赖。
 * <p>
 * 用于文件秒传去重、分片上传完整性校验、消息幂等 key 等非密码学安全场景。
 * 输出统一为 64 字符小写十六进制串。
 */
public final class ChecksumUtils {

    private static final HexFormat HEX = HexFormat.of();
    private static final String SHA_256 = "SHA-256";

    private ChecksumUtils() {}

    /** 计算字节数组的 SHA-256（64 字符小写 hex）。 */
    public static String sha256Hex(byte[] data) {
        try {
            return HEX.formatHex(MessageDigest.getInstance(SHA_256).digest(data));
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 是 JDK 强制实现的算法，理论上不会缺失
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /** 计算字符串（UTF-8 编码）的 SHA-256（64 字符小写 hex）。 */
    public static String sha256Hex(String data) {
        return sha256Hex(data.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 流式计算 SHA-256（64 字符小写 hex）。
     * <p>
     * 逐块读取以支持大文件与 MinIO 对象流，避免一次性全量缓冲。不负责关闭流（由调用方
     * 通过 try-with-resources 管理）。
     *
     * @param in 输入流
     * @return SHA-256 hex
     * @throws IOException 读取失败
     */
    public static String sha256Hex(InputStream in) throws IOException {
        try {
            MessageDigest md = MessageDigest.getInstance(SHA_256);
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) {
                md.update(buf, 0, n);
            }
            return HEX.formatHex(md.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
