package com.smart.rag.rag.service;

/** 拒绝预览的原因（设计 §4） */
public enum DenyReason {
    /** 类型不支持在线预览（OOXML 只允许下载） */
    UNSUPPORTED_TYPE,
    /** 文本对象超出预览大小上限 */
    PREVIEW_TOO_LARGE
}
