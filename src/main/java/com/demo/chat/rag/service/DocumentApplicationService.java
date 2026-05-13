package com.demo.chat.rag.service;

import com.demo.chat.rag.dto.DocumentDTO;
import com.demo.chat.rag.dto.DocumentUploadResponse;
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

    /** 批量上传文档（个人） */
    List<DocumentUploadResponse> uploadBatch(List<MultipartFile> files);

    /** 批量上传文档（指定团队） */
    List<DocumentUploadResponse> uploadBatch(MultipartFile[] files, @Nullable Long teamId);

    /** 获取当前用户全部个人文档列表 */
    List<DocumentDTO> listAll();

    /** 获取指定团队的文档列表 */
    List<DocumentDTO> listByTeam(Long teamId);

    DocumentDTO getById(Long id);

    boolean delete(Long id);

    DocumentUploadResponse retry(Long id);
}
