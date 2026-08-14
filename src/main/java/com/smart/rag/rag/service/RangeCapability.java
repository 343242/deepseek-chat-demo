package com.smart.rag.rag.service;

/** 响应的 Range 能力声明（设计 §8）：透传为 bytes，渲染路径为 none */
public enum RangeCapability {
    BYTES,
    NONE
}
