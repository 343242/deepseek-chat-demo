package com.smart.rag.rag.upload;

import com.smart.rag.rag.dto.DocumentUploadResponse;
import org.jspecify.annotations.Nullable;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * 文档上传策略接口 — OCP 合规
 * <p>
 * 归属于 rag.upload 包：上传策略的核心契约围绕 rag 的文档域（返回类型
 * {@link DocumentUploadResponse}、实现 {@link PersonalUploadStrategy} 均在 rag），
 * 不应下沉到 common 造成 common 反向依赖 rag.dto。
 * <p>
 * 根据 teamId 是否为 null 路由到不同的上传策略：
 * <ul>
 *   <li>teamId = null → {@link PersonalUploadStrategy}（个人上传）</li>
 *   <li>teamId ≠ null → TeamUploadStrategy（团队上传 + 额度校验 + 审批，由 team 模块实现）</li>
 * </ul>
 * <p>
 * 新团队功能通过新增策略类实现，无需修改现有代码。
 */
public interface UploadStrategy {

    /**
     * 单文件上传
     *
     * @param file               上传文件
     * @param teamId             团队 ID（null = 个人上传）
     * @param replaceDocumentId  替换目标文档 ID（null = 新文档）
     * @param userId             当前用户 ID
     * @return 上传结果
     */
    DocumentUploadResponse upload(MultipartFile file, @Nullable Long teamId, @Nullable Long replaceDocumentId, Long userId);

    /**
     * 批量文件上传
     *
     * @param files              上传文件列表
     * @param teamId             团队 ID（null = 个人上传）
     * @param replaceDocumentId  替换目标文档 ID（null = 新文档）
     * @param userId             当前用户 ID
     * @return 上传结果列表
     */
    List<DocumentUploadResponse> uploadBatch(List<MultipartFile> files, @Nullable Long teamId, @Nullable Long replaceDocumentId, Long userId);
}
