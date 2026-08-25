package com.smart.rag.rag.upload;

import com.smart.rag.rag.dto.DocumentUploadResponse;
import org.jspecify.annotations.Nullable;

/**
 * Presigned URL 直传控制面服务（数据面浏览器直达 MinIO）。
 * <p>
 * 两阶段提交：init（鉴权/白名单/秒传/额度 → 签发）→ 浏览器 PUT 直传 → commit
 * （Complete + 事实校验 + copy + 落库 + ETL）。会话端点统一校验 owner 与 teamId 一致性。
 * <p>
 * expectedTeamId 约定：null = 个人端点；非 null = 团队端点（须与会话 teamId 一致），
 * 对齐 ChunkUploadServiceImpl.validateTeamScope 惯例。
 */
public interface DirectUploadService {

    /** 灰度开关（app.upload.direct.enabled，阶段 1 默认 false）。 */
    boolean isEnabled();

    /** init：秒传命中 / single presigned URL / 创建 MPU 三态。 */
    DirectUploadInitResult init(DirectUploadInitRequest request);

    /** 批量签发分片 presigned URL（单批 ≤ 上限）。 */
    DirectUploadPartUrlsResult partUrls(String sessionId, DirectUploadPartUrlsRequest request,
                                        @Nullable Long expectedTeamId);

    /** 会话状态查询（断点续传元数据）。 */
    DirectUploadStatusResponse status(String sessionId, @Nullable Long expectedTeamId);

    /** commit：校验 + 合并/复核 + copy + 落库 + ETL 投递。 */
    DocumentUploadResponse commit(String sessionId, DirectUploadCommitRequest request,
                                  @Nullable Long expectedTeamId);

    /** 取消：AbortMultipartUpload / 删 pending + 会话清理。 */
    void abort(String sessionId, @Nullable Long expectedTeamId);
}
