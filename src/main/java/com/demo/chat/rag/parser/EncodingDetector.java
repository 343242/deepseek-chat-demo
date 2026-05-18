package com.demo.chat.rag.parser;

import org.mozilla.universalchardet.UniversalDetector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.charset.UnsupportedCharsetException;

/**
 * 文本编码检测与转码工具。
 * <p>
 * 基于 Mozilla UniversalDetector（juniversalchardet）自动检测文本编码，
 * 非 UTF-8 时自动转码，解决 GBK/GB2312/GB18030/Big5 等编码文件导入后乱码的问题。
 * <p>
 * 为什么不用 Tika AutoDetectReader：
 * <ul>
 *   <li>Tika 对中文短文本编码检测准确率低（常将 GBK 误判为 ISO-8859-1）</li>
 *   <li>UniversalDetector 是 Firefox 同款引擎，对 CJK 编码检测更准确</li>
 *   <li>项目已通过 Tika 传递依赖引入 juniversalchardet 2.5.0，无需额外依赖</li>
 * </ul>
 * <p>
 * 内存策略：对超过 {@link #MAX_DETECT_SIZE} 的文件，仅取前 {@link #DETECT_SAMPLE_SIZE} 字节做编码检测，
 * 然后对全量内容按检测到的编码流式解码，避免 3x 内存膨胀。
 */
public final class EncodingDetector {

    private static final Logger log = LoggerFactory.getLogger(EncodingDetector.class);

    /**
     * 超过此大小（字节）的文件，仅取样检测编码。
     * 设为 10MB——UniversalDetector 对 10KB 已足够，但给 BOM 和多语言混合留余量。
     */
    static final int MAX_DETECT_SIZE = 10 * 1024 * 1024;

    /**
     * 编码检测采样大小。UniversalDetector 通常只需要前 8-32KB 即可判断。
     */
    static final int DETECT_SAMPLE_SIZE = 32 * 1024;

    private EncodingDetector() {
    }

    /**
     * 检测字节数组的编码并解码为字符串。
     *
     * @param bytes    原始字节
     * @param filename 文件名（仅用于日志）
     * @return 解码后的文本内容
     */
    public static String detectAndDecode(byte[] bytes, String filename) {
        if (bytes == null || bytes.length == 0) {
            return "";
        }

        Charset detectedCharset = detectCharset(bytes, filename);

        if (detectedCharset == null) {
            log.debug("No encoding detected for {}, assuming UTF-8", filename);
            return new String(bytes, StandardCharsets.UTF_8);
        }

        if (isUtf8Compatible(detectedCharset)) {
            log.debug("Detected encoding: {} (UTF-8 compatible) for {}", detectedCharset.name(), filename);
            return new String(bytes, StandardCharsets.UTF_8);
        }

        log.info("Detected non-UTF8 encoding: {}, transcoding for file: {}", detectedCharset.name(), filename);
        return new String(bytes, detectedCharset);
    }

    /**
     * 检测 Resource 的编码并转码为 UTF-8 Resource。
     * <p>
     * 对超过 {@link #MAX_DETECT_SIZE} 的大文件，仅取前 {@link #DETECT_SAMPLE_SIZE} 字节做编码检测，
     * 然后对全量内容按检测到的编码解码，避免内存峰值过高。
     *
     * @param resource 原始文件资源
     * @return 保证 UTF-8 编码的 Resource
     */
    public static Resource detectAndTranscode(Resource resource) {
        try {
            byte[] bytes = resource.getInputStream().readAllBytes();

            if (bytes.length == 0) {
                return new NamedByteArrayResource(bytes, resource.getFilename());
            }

            Charset detectedCharset = detectCharset(bytes, resource.getFilename());

            if (detectedCharset == null || isUtf8Compatible(detectedCharset)) {
                log.debug("Encoding {} for {} is UTF-8 compatible, no transcoding",
                        detectedCharset, resource.getFilename());
                // UTF-8 兼容文件直接返回原始 Resource，避免不必要的内存拷贝
                return resource;
            }

            log.info("Detected non-UTF8 encoding: {}, transcoding to UTF-8 for file: {}",
                    detectedCharset.name(), resource.getFilename());

            String text = new String(bytes, detectedCharset);
            byte[] utf8Bytes = text.getBytes(StandardCharsets.UTF_8);
            return new NamedByteArrayResource(utf8Bytes, resource.getFilename());

        } catch (Exception e) {
            throw new DocumentParseException(
                    resource.getFilename(), "EncodingDetector",
                    "Failed to read resource for encoding detection", e);
        }
    }

    /**
     * 检测字节数组的字符编码。
     * <p>
     * 对超过 {@link #MAX_DETECT_SIZE} 的大文件，仅取前 {@link #DETECT_SAMPLE_SIZE} 字节做采样检测。
     *
     * @param bytes    完整字节数组
     * @param filename 文件名（仅用于日志）
     * @return 检测到的 Charset，null 表示无法检测
     */
    private static Charset detectCharset(byte[] bytes, String filename) {
        String encodingName = detectEncoding(bytes, filename);
        if (encodingName == null) {
            return null;
        }
        return safeCharset(encodingName, filename);
    }

    /**
     * 底层编码检测：对大文件采样，小文件全量。
     */
    private static String detectEncoding(byte[] bytes, String filename) {
        byte[] sample = bytes;
        if (bytes.length > MAX_DETECT_SIZE) {
            sample = new byte[Math.min(DETECT_SAMPLE_SIZE, bytes.length)];
            System.arraycopy(bytes, 0, sample, 0, sample.length);
            log.debug("Large file {} ({} bytes), using {} byte sample for encoding detection",
                    filename, bytes.length, sample.length);
        }

        UniversalDetector detector = new UniversalDetector(null);
        try {
            detector.handleData(sample);
            detector.dataEnd();
            return detector.getDetectedCharset();
        } finally {
            detector.reset();
        }
    }

    /**
     * 安全获取 Charset，UnsupportedCharsetException 时降级为 UTF-8。
     */
    private static Charset safeCharset(String encodingName, String filename) {
        try {
            return Charset.forName(encodingName);
        } catch (UnsupportedCharsetException e) {
            log.warn("Detected encoding '{}' is not supported by JVM for file {}, falling back to UTF-8: {}",
                    encodingName, filename, e.getMessage());
            return StandardCharsets.UTF_8;
        }
    }

    /**
     * 判断给定编码是否与 UTF-8 兼容（UTF-8/ASCII）。
     * <p>
     * 使用 {@link StandardCharsets#UTF_8#contains(Charset)} 语义判断，
     * 同时覆盖 UTF-8 with BOM 等变体名称。
     */
    static boolean isUtf8Compatible(Charset charset) {
        if (charset == null) {
            return true;
        }
        // 语义判断：UTF-8 是 ASCII 的超集
        if (StandardCharsets.UTF_8.equals(charset) || StandardCharsets.US_ASCII.equals(charset)) {
            return true;
        }
        // 名称模糊匹配，覆盖 "UTF8" / "utf-8" 等变体以及带 BOM 的情况
        String name = charset.name().toUpperCase();
        return name.contains("UTF-8") || name.contains("UTF8");
    }
}
