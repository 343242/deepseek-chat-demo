package com.smart.rag.evaluation.testset.transforms;

/**
 * pgvector 文本格式解析：{@code [0.123,0.456,...]} → {@code double[]}。
 * vector_store.embedding 列经 JdbcTemplate 读取为该格式的字符串。
 */
public final class PgVectorParser {

    private PgVectorParser() {
    }

    /**
     * 解析 pgvector 的文本表示。
     *
     * @throws IllegalArgumentException 空值、非 [..] 包裹或含非数字片段
     */
    public static double[] parse(String vectorText) {
        if (vectorText == null || vectorText.isBlank()) {
            throw new IllegalArgumentException("pgvector 文本为空");
        }
        var trimmed = vectorText.trim();
        if (trimmed.length() < 2 || trimmed.charAt(0) != '['
                || trimmed.charAt(trimmed.length() - 1) != ']') {
            throw new IllegalArgumentException("pgvector 文本必须以 [ ] 包裹: " + preview(trimmed));
        }
        var body = trimmed.substring(1, trimmed.length() - 1).trim();
        if (body.isEmpty()) {
            return new double[0];
        }
        var parts = body.split(",");
        var result = new double[parts.length];
        for (int i = 0; i < parts.length; i++) {
            try {
                result[i] = Double.parseDouble(parts[i].trim());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(
                        "pgvector 第 " + i + " 维不是数字: " + preview(trimmed), e);
            }
        }
        return result;
    }

    private static String preview(String s) {
        return s.length() <= 40 ? s : s.substring(0, 40) + "...";
    }
}
