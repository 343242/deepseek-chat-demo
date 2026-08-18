package com.smart.rag.evaluation.testset.synthesizers;

/**
 * 问题长度（对应 ragas {@code QueryLength}）。
 */
public enum QueryLength {
    LONG("long"),
    MEDIUM("medium"),
    SHORT("short");

    private final String value;

    QueryLength(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }
}
