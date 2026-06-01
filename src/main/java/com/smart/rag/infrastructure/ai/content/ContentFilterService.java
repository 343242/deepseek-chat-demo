package com.smart.rag.infrastructure.ai.content;

import java.util.List;

/**
 * 内容过滤服务接口
 * <p>
 * 抽象内容安全检测，解耦具体实现（sensitive-word、第三方 API 等）。
 * <p>
 * 契约：实现类应安全处理 null 输入，返回 false / 空列表 / 原文。
 */
public interface ContentFilterService {

    /**
     * 检测文本是否包含敏感内容
     *
     * @param text 待检测文本，null 视为不包含
     */
    boolean containsSensitiveContent(String text);

    /**
     * 查找文本中所有敏感词
     *
     * @param text 待检测文本，null 返回空列表
     */
    List<String> findAll(String text);

    /**
     * 替换文本中的敏感词
     *
     * @param text 待处理文本，null 返回 null
     */
    String replace(String text);
}
