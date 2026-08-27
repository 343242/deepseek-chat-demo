package com.smart.rag.rag.etl;

import com.smart.rag.rag.entity.RagDocument;
import com.smart.rag.rag.mapper.DocumentImageMapper;
import com.smart.rag.rag.mapper.RagDocumentMapper;
import com.smart.rag.rag.service.FileStorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 图片资产清理（design §6.8）——文档删除与 supersede 路径的同一实现
 * （v1.5 严重-2：新版本=新 documentId，旧文档图片处置与删除路径同构）。
 * <p>
 * best-effort 两步：① DELETE document_image 行；② 清理 {@code images/{documentId}/}
 * 前缀对象。任一步失败不阻断主流程，计数告警，等 P3 对账兜底。
 */
@Service
public class ImageCleanupService {

    private static final Logger log = LoggerFactory.getLogger(ImageCleanupService.class);

    private final DocumentImageMapper documentImageMapper;
    private final RagDocumentMapper ragDocumentMapper;
    private final FileStorageService fileStorageService;
    private final ImageMetrics imageMetrics;

    public ImageCleanupService(DocumentImageMapper documentImageMapper,
                               RagDocumentMapper ragDocumentMapper,
                               FileStorageService fileStorageService,
                               ImageMetrics imageMetrics) {
        this.documentImageMapper = documentImageMapper;
        this.ragDocumentMapper = ragDocumentMapper;
        this.fileStorageService = fileStorageService;
        this.imageMetrics = imageMetrics;
    }

    /**
     * 清理文档全部图片资产（行 + 前缀对象）。best-effort：失败计数告警不抛出。
     */
    public void cleanupByDocumentId(Long documentId) {
        try {
            documentImageMapper.deleteByDocumentId(documentId);
        } catch (Exception e) {
            log.error("Failed to delete document_image rows for docId={}", documentId, e);
            imageMetrics.orphanCleanFailed();
        }
        try {
            RagDocument doc = ragDocumentMapper.selectById(documentId);
            if (doc != null && doc.getBucket() != null) {
                fileStorageService.deleteByPrefix(doc.getBucket(), imagePrefix(documentId));
            }
        } catch (Exception e) {
            log.error("Failed to delete image prefix objects for docId={}", documentId, e);
            imageMetrics.orphanCleanFailed();
        }
    }

    public static String imagePrefix(Long documentId) {
        return "images/" + documentId + "/";
    }
}
