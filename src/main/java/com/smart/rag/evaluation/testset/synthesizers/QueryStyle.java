package com.smart.rag.evaluation.testset.synthesizers;

/**
 * 问题风格（对应 ragas {@code QueryStyle}，值保留英文原串供提示词使用）。
 */
public enum QueryStyle {
    MISSPELLED("Misspelled queries"),
    PERFECT_GRAMMAR("Perfect grammar"),
    POOR_GRAMMAR("Poor grammar"),
    WEB_SEARCH_LIKE("Web search like queries");

    private final String value;

    QueryStyle(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }
}
