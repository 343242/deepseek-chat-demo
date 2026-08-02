package com.smart.rag.rag.upload;

import com.smart.rag.rag.dto.DocumentUploadResponse;
import org.jspecify.annotations.Nullable;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * 文档上传策略接口 — OCP 合规。
 * <p>
 * 归属于 rag.upload 包：上传策略的核心契约围绕 rag 的文档域（返回类型
 * {@link DocumentUploadResponse}、实现 {@link PersonalUploadStrategy} 均在 rag），
 * 不应下沉到 common 造成 common 反向依赖 rag.dto。
 * <p>
 * 多个实现（个人 / 团队）通过 {@link com.smart.rag.rag.upload.UploadStrategyRouter}
 * 由 Spring 自动收集，并按 {@link #supports(Long)} 路由，rag 无需感知具体实现所在模块。
 * 新增上传场景只需新增策略实现，无需修改现有代码。
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

    /**
     * 判断本策略是否处理指定的 teamId。
     * <p>
     * 由 {@link com.smart.rag.rag.upload.UploadStrategyRouter} 在自动收集的全部实现中
     * 调用本方法选择唯一匹配的策略。
     *
     * @param teamId 团队 ID（null = 个人上传）
     * @return true 表示本策略处理该 teamId
     */
    boolean supports(@Nullable Long teamId);
}
