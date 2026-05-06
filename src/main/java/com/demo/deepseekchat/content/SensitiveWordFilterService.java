package com.demo.deepseekchat.content;

import com.github.houbb.sensitive.word.core.SensitiveWordHelper;

import java.util.List;

/**
 * 基于 sensitive-word 的内容过滤实现
 * <p>
 * 使用 DFA 算法，14W+ QPS，纯内存操作。
 * 可通过 SensitiveWordBs 自定义词库、替换策略等。
 */
public class SensitiveWordFilterService implements ContentFilterService {

    @Override
    public boolean containsSensitiveContent(String text) {
        return SensitiveWordHelper.contains(text);
    }

    @Override
    public List<String> findAll(String text) {
        return SensitiveWordHelper.findAll(text);
    }

    @Override
    public String replace(String text) {
        return SensitiveWordHelper.replace(text);
    }
}
