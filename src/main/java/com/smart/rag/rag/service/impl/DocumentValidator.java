package com.smart.rag.rag.service.impl;

import com.smart.rag.common.errorcode.ErrorCode;
import com.smart.rag.exception.BusinessException;
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
     * @throws BusinessException 校验不通过
     */
    public void validate(MultipartFile file) {
        if (file.isEmpty()) {
            throw new BusinessException(ErrorCode.UPLOAD_FILE_EMPTY);
        }

        long maxBytes = DataSize.parse(documentProperties.getMaxFileSize()).toBytes();
        if (file.getSize() > maxBytes) {
            throw new BusinessException(ErrorCode.UPLOAD_FILE_TOO_LARGE,
                    String.format("文件大小超出限制: %s > %s",
                            DataSize.ofBytes(file.getSize()).toMegabytes() + "MB",
                            documentProperties.getMaxFileSize()));
        }

        String declaredMimeType = file.getContentType();
        Set<String> allowed = getAllowedMimeTypes();
        if (declaredMimeType == null || !allowed.contains(declaredMimeType)) {
            throw new BusinessException(ErrorCode.UPLOAD_MIME_UNSUPPORTED, "不支持的文件类型: " + declaredMimeType);
        }

        String detectedMimeType = detectMimeType(file);
        if (detectedMimeType != null && !allowed.contains(detectedMimeType)
                && !isZipBasedOfficeDocument(declaredMimeType, detectedMimeType)) {
            throw new BusinessException(ErrorCode.UPLOAD_MIME_UNSUPPORTED,
                    String.format("文件实际类型(%s)与声明类型(%s)不匹配", detectedMimeType, declaredMimeType));
        }
    }

    /**
     * 通过文件头部魔数探测真实 MIME 类型
     */
    String detectMimeType(MultipartFile file) {
        try (InputStream is = file.getInputStream()) {
            byte[] header = new byte[8];
            int read = is.readNBytes(header, 0, 8);
            if (read < 4) return null;

            String headerStr = new String(header, 0, Math.min(read, 4));

            if (headerStr.startsWith("%PDF")) {
                return "application/pdf";
            }

            if (header[0] == 0x50 && header[1] == 0x4B && header[2] == 0x03 && header[3] == 0x04) {
                String originalName = file.getOriginalFilename();
                if (originalName != null) {
                    String lower = originalName.toLowerCase();
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
