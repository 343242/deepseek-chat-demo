package com.demo.chat.common.upload;

import com.demo.chat.rag.dto.DocumentUploadResponse;
import org.jspecify.annotations.Nullable;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * 文档上传策略接口 — OCP 合规
 * <p>
 * 根据 teamId 是否为 null 路由到不同的上传策略：
 * <ul>
 *   <li>teamId = null → PersonalUploadStrategy（现有个人上传逻辑）</li>
 *   <li>teamId ≠ null → TeamUploadStrategy（团队上传 + 额度校验 + 审批）</li>
 * </ul>
 * <p>
 * 新团队功能通过新增策略类实现，无需修改现有代码。
 */
public interface UploadStrategy {

    /**
     * 单文件上传
     *
     * @param file   上传文件
     * @param teamId 团队 ID（null = 个人上传）
     * @param userId 当前用户 ID
     * @return 上传结果
     */
    DocumentUploadResponse upload(MultipartFile file, @Nullable Long teamId, Long userId);

    /**
     * 批量文件上传
     *
     * @param files  上传文件列表
     * @param teamId 团队 ID（null = 个人上传）
     * @param userId 当前用户 ID
     * @return 上传结果列表
     */
    List<DocumentUploadResponse> uploadBatch(List<MultipartFile> files, @Nullable Long teamId, Long userId);
}
