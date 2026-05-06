package com.demo.deepseekchat.content;

import java.util.List;

/**
 * 内容过滤服务接口
 * <p>
 * 抽象内容安全检测，解耦具体实现（sensitive-word、第三方 API 等）。
 */
public interface ContentFilterService {

    /**
     * 检测文本是否包含敏感内容
     */
    boolean containsSensitiveContent(String text);

    /**
     * 查找文本中所有敏感词
     */
    List<String> findAll(String text);

    /**
     * 替换文本中的敏感词
     */
    String replace(String text);
}
