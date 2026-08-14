package com.smart.rag.rag.service.impl;

import com.smart.rag.infrastructure.exception.ClientException;
import com.smart.rag.infrastructure.exception.RemoteException;
import com.smart.rag.infrastructure.exception.errorcode.ClientErrorCode;
import com.smart.rag.infrastructure.exception.errorcode.RemoteErrorCode;
import com.smart.rag.rag.config.DocumentProperties;
import com.smart.rag.rag.service.DocumentMimePolicy;
import org.apache.poi.openxml4j.opc.OPCPackage;
import org.apache.poi.openxml4j.opc.PackageAccess;
import org.apache.poi.openxml4j.opc.PackagePart;
import org.apache.poi.openxml4j.opc.PackagePartName;
import org.apache.poi.openxml4j.opc.PackageRelationship;
import org.apache.poi.openxml4j.opc.PackageRelationshipCollection;
import org.apache.poi.openxml4j.opc.PackageRelationshipTypes;
import org.apache.poi.openxml4j.opc.PackagingURIHelper;
import org.apache.tika.Tika;
import org.apache.tika.io.TikaInputStream;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.unit.DataSize;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 文档上传校验器 — 服务端规范 MIME 的产出点（原文件预览与下载设计 §3）。
 * <p>
 * 校验流程：
 * <ol>
 *   <li>非空 + 大小上限</li>
 *   <li>内容落临时文件后经 Tika 做文件支撑探测（流式探测无法兑现 OOXML 包关系校验）</li>
 *   <li>OOXML 再经 POI {@code OPCPackage.open} 确认主关系、目标 part 与 content type</li>
 *   <li>{@link DocumentMimePolicy} 对「探测结果 × 扩展名」做一致性二次校验</li>
 * </ol>
 * 客户端声明的 Content-Type 只可作为诊断信息，不参与类型决策。
 * 加密、损坏、结构不完整或触发 ZIP 安全限制的 OOXML 一律拒绝。
 */
@Component
public class DocumentValidator {

    private static final Logger log = LoggerFactory.getLogger(DocumentValidator.class);

    private final DocumentProperties documentProperties;
    private final DocumentMimePolicy mimePolicy;
    private final Tika tika = new Tika();

    public DocumentValidator(DocumentProperties documentProperties, DocumentMimePolicy mimePolicy) {
        this.documentProperties = documentProperties;
        this.mimePolicy = mimePolicy;
    }

    /**
     * 校验上传文件并返回服务端规范元数据。
     *
     * @param file 上传文件
     * @return 含规范 MIME 的校验结果
     * @throws ClientException 校验不通过（空文件、超限、类型不支持、内容与扩展名不符）
     */
    public ValidatedDocumentFile validate(MultipartFile file) {
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
        try (InputStream is = file.getInputStream()) {
            return validate(is, file.getOriginalFilename(), file.getSize());
        } catch (IOException e) {
            throw new ClientException(ClientErrorCode.UPLOAD_FAILED, "读取上传文件失败");
        }
    }

    /**
     * 对任意内容流执行类型校验（分片上传合并后的 MinIO 对象复用同一入口）。
     * <p>
     * 输入在大小上限内落临时文件支撑 Tika 探测，校验完成（无论成败）后删除。
     *
     * @param content  已打开的内容流（本方法负责关闭）
     * @param fileName 原始文件名（扩展名参与一致性判定，不可为 null）
     * @param fileSize 声明的文件大小（仅回填结果，不作为上限依据；上限始终按实际拷贝字节数强制）
     * @return 含规范 MIME 的校验结果
     */
    public ValidatedDocumentFile validate(InputStream content, String fileName, long fileSize) {
        long maxBytes = DataSize.parse(documentProperties.getMaxFileSize()).toBytes();
        Path tempFile = null;
        try {
            tempFile = copyToTempFile(content, maxBytes);
            String canonical = detectCanonicalMime(tempFile, fileName);
            if (mimePolicy.isOoxml(canonical)) {
                confirmOoxmlStructure(tempFile, canonical);
            }
            return new ValidatedDocumentFile(fileName, fileSize, canonical);
        } catch (ClientException e) {
            throw e;
        } catch (IOException e) {
            // 内容流读取中断（MinIO 对象流/上传暂存流故障）按存储不可用翻译，不是客户端类型错误
            log.warn("MIME validation failed to read content: file={}", fileName, e);
            throw new RemoteException(RemoteErrorCode.FILE_STORAGE_UNAVAILABLE, "文件存储暂不可用", e);
        } catch (Exception e) {
            log.warn("MIME validation failed unexpectedly: file={}", fileName, e);
            throw new ClientException(ClientErrorCode.UPLOAD_MIME_UNSUPPORTED, "文件类型校验失败");
        } finally {
            if (tempFile != null) {
                deleteQuietly(tempFile);
            }
        }
    }

    // ==================== 内部步骤 ====================

    /** 有界拷贝到临时文件；超出大小上限立即拒绝（读取 maxBytes+1 以区分恰好等于上限的情况） */
    private Path copyToTempFile(InputStream content, long maxBytes) throws IOException {
        Path temp = Files.createTempFile("rag-validate-", ".tmp");
        try (InputStream in = content;
             java.io.OutputStream out = Files.newOutputStream(temp)) {
            byte[] buffer = new byte[8192];
            long copied = 0;
            int read;
            while ((read = in.read(buffer)) > 0) {
                copied += read;
                if (copied > maxBytes) {
                    throw new ClientException(ClientErrorCode.UPLOAD_FILE_TOO_LARGE,
                            String.format("文件大小超出限制: > %s", documentProperties.getMaxFileSize()));
                }
                out.write(buffer, 0, read);
            }
        }
        return temp;
    }

    /**
     * 文件支撑的 Tika 探测 + Policy 一致性校验。
     * <p>
     * 禁止对普通 InputStream 直接调用 {@code Tika.detect}：流式探测只解析
     * {@code [Content_Types].xml} 即可能给出 OOXML 类型，无法兑现包关系校验。
     * 探测不携带文件名——内容类别必须只由内容决定；扩展名仅经
     * {@link DocumentMimePolicy} 参与细分与一致性判定，避免 NameDetector
     * 让文件名影响内容类别。
     */
    private String detectCanonicalMime(Path tempFile, String fileName) throws IOException {
        String probed;
        try (TikaInputStream tis = TikaInputStream.get(tempFile)) {
            probed = tika.detect(tis);
        }
        String canonical = mimePolicy.canonicalForProbe(probed, fileName);
        if (canonical == null) {
            log.warn("MIME probe/extension mismatch: file={}, probed={}", fileName, probed);
            throw new ClientException(ClientErrorCode.UPLOAD_MIME_UNSUPPORTED,
                    String.format("文件实际类型与扩展名不符（检测为 %s）",
                            probed == null ? "未知" : probed));
        }
        return canonical;
    }

    /**
     * OOXML 包结构确认（设计 §3.2）：恰有一个 office document 主关系、目标 part 存在，
     * 且其 content type 与扩展名对应的规范 MIME 匹配。
     * <p>
     * POI 的 ZIP 安全限制（{@code ZipSecureFile} 阈值由 {@code ZipSecurityConfig} 启动期钉死）
     * 触发时以异常形式暴露，统一按类型不支持拒绝。加密包为 OLE2 容器，{@code OPCPackage.open}
     * 同样抛异常落入拒绝分支。
     */
    private void confirmOoxmlStructure(Path tempFile, String canonicalMime) {
        try (OPCPackage pkg = OPCPackage.open(tempFile.toFile(), PackageAccess.READ)) {
            PackageRelationshipCollection rels =
                    pkg.getRelationshipsByType(PackageRelationshipTypes.CORE_DOCUMENT);
            if (rels.size() != 1) {
                throw new ClientException(ClientErrorCode.UPLOAD_MIME_UNSUPPORTED,
                        "OOXML 包缺少唯一的 office document 主关系");
            }
            PackageRelationship mainRel = rels.getRelationship(0);
            String targetPath = mainRel.getTargetURI().getPath();
            PackagePartName partName = PackagingURIHelper.createPartName(
                    targetPath.startsWith("/") ? targetPath : "/" + targetPath);
            PackagePart mainPart = pkg.getPart(partName);
            if (mainPart == null) {
                throw new ClientException(ClientErrorCode.UPLOAD_MIME_UNSUPPORTED,
                        "OOXML 主关系目标 part 不存在");
            }
            String expected = DocumentMimePolicy.OOXML_MAIN_PART_CONTENT_TYPE.get(canonicalMime);
            if (!expected.equals(mainPart.getContentType())) {
                log.warn("OOXML main part content type mismatch: expected={}, actual={}",
                        expected, mainPart.getContentType());
                throw new ClientException(ClientErrorCode.UPLOAD_MIME_UNSUPPORTED,
                        "OOXML 包结构与扩展名不符");
            }
        } catch (ClientException e) {
            throw e;
        } catch (Exception e) {
            // OLE2/加密/损坏包、zip-bomb 阈值触发等结构异常统一拒绝
            log.warn("OOXML structure check failed: err={}", e.getMessage());
            throw new ClientException(ClientErrorCode.UPLOAD_MIME_UNSUPPORTED,
                    "文件不是有效的 OOXML 包或已损坏/加密");
        }
    }

    private static void deleteQuietly(@Nullable Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            log.warn("Failed to delete validation temp file: {}", e.getMessage());
        }
    }
}
