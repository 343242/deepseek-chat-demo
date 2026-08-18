package com.smart.rag.evaluation.metrics.generation;

/**
 * Rouge-L 评分器（翻译 ragas StringMetric rouge-l 语义，字符级实现）。
 * <p>
 * 中文字符即天然 token，字符级 LCS 对中文等价于词级效果且零分词依赖；
 * 英文退化为字符 LCS 仍是可用的确定性基线（与 LLM judge 互补，非替代）。
 * score = LCS 的 F1：P = LCS/len(answer)，R = LCS/len(groundTruth)，F = 2PR/(P+R)。
 * 任一侧为空返回 0。
 * </p>
 */
public final class RougeLScorer {

    private RougeLScorer() {
    }

    public static double score(String answer, String groundTruth) {
        if (answer == null || groundTruth == null
                || answer.isEmpty() || groundTruth.isEmpty()) {
            return 0.0;
        }
        int lcs = longestCommonSubsequence(answer.toCharArray(), groundTruth.toCharArray());
        if (lcs == 0) {
            return 0.0;
        }
        double precision = (double) lcs / answer.length();
        double recall = (double) lcs / groundTruth.length();
        return 2 * precision * recall / (precision + recall);
    }

    /** 经典 DP LCS（O(n·m)，评估样本量级下可接受）。 */
    static int longestCommonSubsequence(char[] a, char[] b) {
        int[] prev = new int[b.length + 1];
        int[] curr = new int[b.length + 1];
        for (int i = 1; i <= a.length; i++) {
            for (int j = 1; j <= b.length; j++) {
                curr[j] = a[i - 1] == b[j - 1]
                        ? prev[j - 1] + 1
                        : Math.max(prev[j], curr[j - 1]);
            }
            var swap = prev;
            prev = curr;
            curr = swap;
            java.util.Arrays.fill(curr, 0);
        }
        return prev[b.length];
    }
}
