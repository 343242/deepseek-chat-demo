package com.demo.chat.rag.service;

import com.demo.chat.rag.dto.DocumentDTO;
import com.demo.chat.rag.dto.DocumentUploadResponse;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * 文档应用服务 — RAG 文档的业务门面
 * <p>
 * 编排文件存储、元数据持久化、ETL 处理等完整业务流程。
 * Controller 层仅处理 HTTP 协议 concerns，所有业务逻辑委托至此。
 * </p>
 */
public interface DocumentApplicationService {

    /**
     * 上传文档并触发 ETL 处理
     *
     * @param file 上传的文件
     * @return 上传响应（含文档 ID 和处理状态）
     */
    DocumentUploadResponse upload(MultipartFile file);

    /**
     * 获取全部文档列表（按创建时间倒序）
     *
     * @return 文档 DTO 列表
     */
    List<DocumentDTO> listAll();

    /**
     * 获取单个文档详情
     *
     * @param id 文档 ID
     * @return 文档 DTO，不存在时返回 null
     */
    DocumentDTO getById(Long id);

    /**
     * 删除文档（清理存储 + 元数据）
     *
     * @param id 文档 ID
     * @return true 表示成功删除，false 表示文档不存在
     */
    boolean delete(Long id);
}
