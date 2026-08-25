package com.smart.rag.rag.upload;

import java.util.List;

/**
 * 批量签发分片 presigned URL 响应。
 *
 * @param urls 分片 URL 列表（与请求 partNumbers 一一对应）
 */
public record DirectUploadPartUrlsResult(long expiresAt, List<PartUrl> urls) {

    public record PartUrl(int partNumber, String url) {}
}
