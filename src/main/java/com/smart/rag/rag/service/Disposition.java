package com.smart.rag.rag.service;

/** 响应 Content-Disposition 语义（设计 §8）：preview 用 inline，download 用 attachment */
public enum Disposition {
    INLINE,
    ATTACHMENT
}
