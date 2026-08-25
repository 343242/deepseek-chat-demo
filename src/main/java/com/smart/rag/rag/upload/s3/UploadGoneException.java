package com.smart.rag.rag.upload.s3;

import com.smart.rag.infrastructure.exception.ServiceException;
import com.smart.rag.infrastructure.exception.errorcode.ServiceErrorCode;

/**
 * 直传会话已失效（uploadId 消亡 / pending 对象不存在）。
 * <p>
 * 语义：不可续传、不可重试 commit，前端收到 {@code DIRECT_UPLOAD_UPLOAD_GONE}(204016)
 * 后必须重新 init。触发源：cleaner 主动 abort 与会话 TTL 的同窗竞态、并发 commit 已
 * Complete、MPU 24h 回收阈值到期、pending 对象被 cleaner 清除。绝不映射为「全部缺失」，
 * 否则前端会对死 uploadId 逐片 PUT 吃 404 死循环（见设计文档「断点续传与并发」）。
 */
public class UploadGoneException extends ServiceException {

    public UploadGoneException(String message, Throwable cause) {
        super(ServiceErrorCode.DIRECT_UPLOAD_UPLOAD_GONE, message, cause);
    }
}
