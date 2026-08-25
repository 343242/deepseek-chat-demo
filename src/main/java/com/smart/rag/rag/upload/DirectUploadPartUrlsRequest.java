package com.smart.rag.rag.upload;

import jakarta.validation.constraints.NotEmpty;

import java.util.List;

/**
 * 批量签发分片 presigned URL 请求。
 * <p>
 * partNumbers 须非空、去重、∈ [1, totalChunks]，且单批 ≤ 上限（默认 20），
 * 越界/超量按参数错误拒绝（见设计文档「已定决策」1）。
 */
public record DirectUploadPartUrlsRequest(@NotEmpty List<Integer> partNumbers) {
}
