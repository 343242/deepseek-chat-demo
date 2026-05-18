package com.demo.chat.rag.parser;

import org.mozilla.universalchardet.UniversalDetector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

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
 */
public final class EncodingDetector {

    private static final Logger log = LoggerFactory.getLogger(EncodingDetector.class);

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

        String detectedEncoding = detectEncoding(bytes);

        if (detectedEncoding == null) {
            log.debug("No encoding detected for {}, assuming UTF-8", filename);
            return new String(bytes, StandardCharsets.UTF_8);
        }

        Charset detectedCharset = Charset.forName(detectedEncoding);

        if (isUtf8Compatible(detectedCharset)) {
            log.debug("Detected encoding: {} (UTF-8 compatible) for {}", detectedEncoding, filename);
            return new String(bytes, StandardCharsets.UTF_8);
        }

        log.info("Detected non-UTF8 encoding: {}, transcoding for file: {}", detectedEncoding, filename);
        return new String(bytes, detectedCharset);
    }

    /**
     * 检测 Resource 的编码并转码为 UTF-8 Resource。
     *
     * @param resource 原始文件资源
     * @return 保证 UTF-8 编码的 Resource
     */
    public static Resource detectAndTranscode(Resource resource) {
        try {
            byte[] bytes = resource.getInputStream().readAllBytes();

            if (bytes.length == 0) {
                return new ByteArrayResource(bytes);
            }

            String detectedEncoding = detectEncoding(bytes);

            if (detectedEncoding == null || isUtf8Compatible(Charset.forName(detectedEncoding))) {
                log.debug("Encoding {} for {} is UTF-8 compatible, no transcoding",
                        detectedEncoding, resource.getFilename());
                return new ByteArrayResource(bytes);
            }

            Charset detectedCharset = Charset.forName(detectedEncoding);
            log.info("Detected non-UTF8 encoding: {}, transcoding to UTF-8 for file: {}",
                    detectedEncoding, resource.getFilename());

            String text = new String(bytes, detectedCharset);
            byte[] utf8Bytes = text.getBytes(StandardCharsets.UTF_8);
            return new ByteArrayResource(utf8Bytes);

        } catch (Exception e) {
            throw new DocumentParseException(
                    resource.getFilename(), "EncodingDetector",
                    "Failed to read resource for encoding detection", e);
        }
    }

    private static String detectEncoding(byte[] bytes) {
        UniversalDetector detector = new UniversalDetector(null);
        detector.handleData(bytes);
        detector.dataEnd();
        return detector.getDetectedCharset();
    }

    private static boolean isUtf8Compatible(Charset charset) {
        if (charset == null) {
            return true;
        }
        String name = charset.name().toUpperCase();
        return name.equals("UTF-8") || name.equals("UTF8") || name.equals("ASCII") || name.equals("US-ASCII");
    }
}
