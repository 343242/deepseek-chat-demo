package com.smart.rag.rag.service.impl;

import com.smart.rag.infrastructure.exception.errorcode.ClientErrorCode;
import com.smart.rag.infrastructure.exception.ClientException;
import com.smart.rag.rag.config.DocumentProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.unit.DataSize;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.Set;

/**
 * 文档校验器（单一职责）
 * <p>
 * 封装文件上传的所有校验逻辑：
 * <ul>
 *   <li>非空校验</li>
 *   <li>大小限制</li>
 *   <li>MIME 白名单（客户端声明）</li>
 *   <li>服务端 MIME 探测（魔数校验，防止 Content-Type 伪造）</li>
 * </ul>
 * <p>
 * 从 DocumentApplicationServiceImpl 中提取，符合 SRP。
 */
@Component
public class DocumentValidator {

    private static final Logger log = LoggerFactory.getLogger(DocumentValidator.class);

    private final DocumentProperties documentProperties;

    /** 运行时解析的 MIME 白名单 */
    private volatile Set<String> cachedAllowedMimeTypes;

    private static final Map<String, String> EXTENSION_MIME_MAP = Map.of(
            ".docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            ".pptx", "application/vnd.openxmlformats-officedocument.presentationml.presentation"
    );

    public DocumentValidator(DocumentProperties documentProperties) {
        this.documentProperties = documentProperties;
    }

    /**
     * 校验上传文件：非空 + 大小限制 + MIME 白名单 + 服务端 MIME 校验
     *
     * @param file 上传文件
     * @throws ClientException 校验不通过
     */
    public void validate(MultipartFile file) {
        if (file.isEmpty()) {
            throw new ClientException(ClientErrorCode.UPLOAD_FILE_EMPTY);
        }

        long maxBytes = DataSize.parse(documentProperties.getMaxFileSize()).toBytes();
        if (file.getSize() > maxBytes) {
            throw new ClientException(ClientErrorCode.UPLOAD_FILE_TOO_LARGE,
                    String.format("文件大小超出限制: %s > %s",
                            DataSize.ofBytes(file.getSize()).toMegabytes() + "MB",
                            documentProperties.getMaxFileSize()));
        }

        String declaredMimeType = file.getContentType();
        Set<String> allowed = getAllowedMimeTypes();
        if (declaredMimeType == null || !allowed.contains(declaredMimeType)) {
            throw new ClientException(ClientErrorCode.UPLOAD_MIME_UNSUPPORTED, "不支持的文件类型: " + declaredMimeType);
        }

        String detectedMimeType = detectMimeType(file);
        if (detectedMimeType != null && !allowed.contains(detectedMimeType)
                && !isZipBasedOfficeDocument(declaredMimeType, detectedMimeType)) {
            throw new ClientException(ClientErrorCode.UPLOAD_MIME_UNSUPPORTED,
                    String.format("文件实际类型(%s)与声明类型(%s)不匹配", detectedMimeType, declaredMimeType));
        }
    }

    /**
     * 通过文件头部魔数探测真实 MIME 类型（MultipartFile 入口，保留向后兼容）。
     * <p>
     * 委托给 {@link #detectMimeType(InputStream, String)}，使魔数校验逻辑可被
     * 非分片上传路径（如分片合并后的 MinIO 对象）复用。
     */
    String detectMimeType(MultipartFile file) {
        try (InputStream is = file.getInputStream()) {
            return detectMimeType(is, file.getOriginalFilename());
        } catch (IOException e) {
            log.warn("Failed to open stream for MIME detection: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 通过流头部魔数探测真实 MIME 类型。
     * <p>
     * 仅消费流的前 8 字节用于嗅探，调用方负责流的生命周期与重读。
     * 用于分片上传合并后对 MinIO 对象的真实类型校验（R2-H1）。
     *
     * @param is          已打开的输入流，方法不关闭它
     * @param fileName    原始文件名（可为 null），用于 OOXML 子类型（docx/pptx）的扩展名判定
     * @return 探测到的 MIME 类型，无法识别时返回 null
     */
    public String detectMimeType(InputStream is, String fileName) {
        try {
            byte[] header = new byte[8];
            int read = is.readNBytes(header, 0, 8);
            if (read < 4) return null;

            String headerStr = new String(header, 0, Math.min(read, 4));

            if (headerStr.startsWith("%PDF")) {
                return "application/pdf";
            }

            if (header[0] == 0x50 && header[1] == 0x4B && header[2] == 0x03 && header[3] == 0x04) {
                if (fileName != null) {
                    String lower = fileName.toLowerCase();
                    for (Map.Entry<String, String> entry : EXTENSION_MIME_MAP.entrySet()) {
                        if (lower.endsWith(entry.getKey())) {
                            return entry.getValue();
                        }
                    }
                }
                return "application/zip";
            }

            boolean allPrintable = true;
            for (int i = 0; i < read; i++) {
                byte b = header[i];
                if (b < 0x09 || (b > 0x0D && b < 0x20 && b != 0x1B)) {
                    allPrintable = false;
                    break;
                }
            }
            if (allPrintable) {
                return "text/plain";
            }

            return null;
        } catch (IOException e) {
            log.warn("Failed to detect MIME type: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 校验「检测到的 MIME」是否落在允许白名单内，用于分片上传合并后的安全校验（R2-H1）。
     * <p>
     * 与 {@link #validate(MultipartFile)} 的差异：
     * <ul>
     *   <li>仅做检测类型 vs 白名单校验，不重复声明类型校验（声明类型已在 init 时校验）</li>
     *   <li>对 OOXML 子类型（声明 openxmlformats 但检测为 application/zip）放行</li>
     * </ul>
     *
     * @param detectedMimeType 检测到的真实类型（null 视为无法识别，返回 false）
     * @param declaredMimeType 客户端声明类型（用于 OOXML zip 兼容判定）
     * @return true 表示检测类型可信/可放行；false 表示需拒绝
     */
    public boolean isDetectedMimeTypeAcceptable(String detectedMimeType, String declaredMimeType) {
        if (detectedMimeType == null) {
            return false;
        }
        Set<String> allowed = getAllowedMimeTypes();
        if (allowed.contains(detectedMimeType)) {
            return true;
        }
        // OOXML 容器：真实魔数是 zip，声明为 office 子类型 → 放行（子类型由扩展名路由决定）
        return isZipBasedOfficeDocument(declaredMimeType, detectedMimeType);
    }

    private boolean isZipBasedOfficeDocument(String declared, String detected) {
        return "application/zip".equals(detected) && declared.contains("openxmlformats-officedocument");
    }

    private Set<String> getAllowedMimeTypes() {
        if (cachedAllowedMimeTypes == null) {
            synchronized (this) {
                if (cachedAllowedMimeTypes == null) {
                    cachedAllowedMimeTypes = Set.of(
                            documentProperties.getAllowedMimeTypes().split(","));
                }
            }
        }
        return cachedAllowedMimeTypes;
    }
}
