package com.smart.rag.rag.service;

import com.smart.rag.infrastructure.response.PagedResult;
import com.smart.rag.rag.dto.ChunkDTO;
import com.smart.rag.rag.dto.DocumentDTO;
import com.smart.rag.rag.dto.DocumentUploadResponse;
import org.jspecify.annotations.Nullable;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * 文档应用服务 — RAG 文档的业务门面
 * <p>
 * 编排文件存储、元数据持久化、ETL 调度等完整业务流程。
 * Controller 层仅处理 HTTP 协议 concerns，所有业务逻辑委托至此。
 */
public interface DocumentApplicationService {

    /** 上传单个文档（个人） */
    DocumentUploadResponse upload(MultipartFile file);

    /** 上传单个文档（指定团队） */
    DocumentUploadResponse upload(MultipartFile file, @Nullable Long teamId);

    /** 上传单个文档（指定替换目标） */
    DocumentUploadResponse upload(MultipartFile file, @Nullable Long teamId, @Nullable Long replaceDocumentId);

    /** 批量上传文档（个人） */
    List<DocumentUploadResponse> uploadBatch(List<MultipartFile> files);

    /** 批量上传文档（指定团队） */
    List<DocumentUploadResponse> uploadBatch(MultipartFile[] files, @Nullable Long teamId);

    /**
     * 获取当前用户全部个人文档列表（分页）
     *
     * @param page 页码（从 1 开始，&lt; 1 归一化为 1）
     * @param size 每页大小（钳制到 [1, 100]）
     */
    PagedResult<DocumentDTO> listAll(int page, int size);

    /**
     * 获取指定团队的文档列表（分页）
     *
     * @param page 页码（从 1 开始，&lt; 1 归一化为 1）
     * @param size 每页大小（钳制到 [1, 100]）
     */
    PagedResult<DocumentDTO> listByTeam(Long teamId, int page, int size);

    DocumentDTO getById(Long id);

    boolean delete(Long id);

    DocumentUploadResponse retry(Long id);

    /** 获取文档版本历史 */
    List<DocumentDTO> getHistory(Long id);

    /**
     * 分页查询文档的所有 chunk（片段内容查看）。
     * <p>
     * 复用 {@link #getById} 的文档归属校验：个人文档需 owner；团队文档需成员
     * （非 owner/管理员仅 COMPLETED 可见，R1-M1 可见性分层）。
     *
     * @param documentId 文档 ID
     * @param page       页码（从 1 开始，&lt; 1 归一化为 1）
     * @param size       每页大小（钳制到 [1, 100]）
     * @return 分页 chunk 列表
     */
    PagedResult<ChunkDTO> listChunks(Long documentId, int page, int size);

    /**
     * 按 chunk UUID 查询单个 chunk（引用卡片点击查看内容）。
     * <p>
     * chunkId 全局唯一（vector_store.id UUID），归属校验通过其 metadata.documentId
     * 解析后复用 {@link #getById} 同一套文档权限逻辑。不存在或无权访问时抛相应异常。
     *
     * @param chunkId chunk UUID（vector_store.id）
     * @return chunk DTO
     */
    ChunkDTO getChunk(String chunkId);

    /**
     * 授权后的文件读取描述符（preview / download 共用，设计 §5）。
     * <p>
     * 复用 {@code DocumentAccessGuard.verifyAccess} 的统一权限语义（owner / manager /
     * uploader 放行；非 COMPLETED 团队文档对非管理者返回 DOCUMENT_NOT_FOUND）。
     * 仅在授权通过后返回；描述符携带的存储定位信息只在模块内部流转。
     */
    AuthorizedDocumentFile authorizeFileRead(Long id);
}
