package com.smart.rag.evaluation.metrics.generation;

import java.util.HashMap;
import java.util.Map;

/**
 * BLEU 评分器（翻译 ragas StringMetric bleu 语义，字符级 1-4 gram）。
 * <p>
 * 与 {@link RougeLScorer} 同理：中文按字符 n-gram，零分词依赖。
 * score = BP × exp(Σ w_n × log p_n)，w=1/4，p_n 为 clipped n-gram 精度；
 * 平滑：缺失的 n-gram 精度按 +1 平滑（避免 log(0)）。任一侧过短（&lt; n）返回 0。
 * </p>
 */
public final class BleuScorer {

    private static final int MAX_N = 4;

    private BleuScorer() {
    }

    public static double score(String answer, String groundTruth) {
        if (answer == null || groundTruth == null
                || answer.length() < MAX_N || groundTruth.length() < MAX_N) {
            return 0.0;
        }
        var answerChars = answer.toCharArray();
        var gtChars = groundTruth.toCharArray();

        double logPrecisionSum = 0.0;
        for (int n = 1; n <= MAX_N; n++) {
            var answerGrams = nGrams(answerChars, n);
            var gtGrams = nGrams(gtChars, n);
            int clipped = 0;
            int total = answerChars.length - n + 1;
            for (var entry : answerGrams.entrySet()) {
                int gtCount = gtGrams.getOrDefault(entry.getKey(), 0);
                clipped += Math.min(entry.getValue(), gtCount);
            }
            // +1 平滑
            double precision = (clipped + 1.0) / (total + 1.0);
            logPrecisionSum += Math.log(precision) / MAX_N;
        }

        double brevity = brevityPenalty(answer.length(), groundTruth.length());
        return brevity * Math.exp(logPrecisionSum);
    }

    private static double brevityPenalty(int answerLength, int gtLength) {
        if (answerLength >= gtLength) {
            return 1.0;
        }
        return Math.exp(1.0 - (double) gtLength / answerLength);
    }

    private static Map<String, Integer> nGrams(char[] chars, int n) {
        var grams = new HashMap<String, Integer>(chars.length * 2);
        for (int i = 0; i + n <= chars.length; i++) {
            grams.merge(new String(chars, i, n), 1, Integer::sum);
        }
        return grams;
    }
}
