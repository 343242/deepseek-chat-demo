package com.smart.rag.rag.service.impl;

/**
 * groupId CAS 冲突 — linkVersion 事务内的控制流信号。
 * <p>
 * {@code updateGroupIdCas} 返回 0（其他并发线程已为旧文档分配 groupId）时抛出，
 * 由 {@link DocumentSupersedeService#linkVersion} 的重试循环按类型捕获并重试。
 * 仅用于内部控制流分发，不对外暴露（参照 {@code DocumentParseException} 先例，
 * 直接继承 RuntimeException）。
 */
class GroupIdCasConflictException extends RuntimeException {

    GroupIdCasConflictException(Long oldDocId) {
        super("CAS groupId conflict for oldDocId=" + oldDocId);
    }
}
