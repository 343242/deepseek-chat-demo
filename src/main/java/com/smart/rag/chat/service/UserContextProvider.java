package com.smart.rag.chat.service;

/**
 * 当前用户上下文提供者 — 封装用户 ID 获取逻辑，便于测试替换。
 */
@FunctionalInterface
public interface UserContextProvider {

    /**
     * 获取当前认证用户的 ID
     *
     * @return 用户 ID
     */
    Long getCurrentUserId();
}
