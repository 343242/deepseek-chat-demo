package com.smart.rag.evaluation.metrics.generation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("字符串指标（字符级，零 LLM）")
class StringMetricsTest {

    @Nested
    @DisplayName("RougeLScorer")
    class RougeL {

        @Test
        @DisplayName("完全一致为 1，完全无关为 0，空串安全")
        void basics() {
            assertThat(RougeLScorer.score("多屏协同需要华为账号", "多屏协同需要华为账号")).isEqualTo(1.0);
            assertThat(RougeLScorer.score("多屏协同", "畅连通话")).isEqualTo(0.0);
            assertThat(RougeLScorer.score("", "多屏协同")).isEqualTo(0.0);
            assertThat(RougeLScorer.score(null, "多屏协同")).isEqualTo(0.0);
        }

        @Test
        @DisplayName("部分重叠按 LCS F1 计分（中文样本）")
        void partialOverlap() {
            // LCS = "多屏协同"+"连接" = 6，两侧等长 → F = 6/8
            double score = RougeLScorer.score("如何多屏协同连接", "多屏协同怎么连接");
            assertThat(score).isCloseTo(0.75, org.assertj.core.data.Offset.offset(0.001));
            // 冗长答案（16 字 vs 4 字，LCS=4）：P=0.25、R=1.0 → F1=0.4（F1 惩罚冗余）
            assertThat(RougeLScorer.score("要使用多屏协同功能请登录华为账号", "多屏协同"))
                    .isCloseTo(0.4, org.assertj.core.data.Offset.offset(0.001));
        }

        @Test
        @DisplayName("LCS 动态规划正确性（经典用例）")
        void lcsAlgorithm() {
            assertThat(RougeLScorer.longestCommonSubsequence(
                    "abcde".toCharArray(), "ace".toCharArray())).isEqualTo(3);
            assertThat(RougeLScorer.longestCommonSubsequence(
                    "abc".toCharArray(), "abc".toCharArray())).isEqualTo(3);
            assertThat(RougeLScorer.longestCommonSubsequence(
                    "abc".toCharArray(), "def".toCharArray())).isEqualTo(0);
        }
    }

    @Nested
    @DisplayName("BleuScorer")
    class Bleu {

        @Test
        @DisplayName("完全一致接近 1（平滑上限），完全无关为低分，短串安全返回 0")
        void basics() {
            assertThat(BleuScorer.score("多屏协同需要登录同一个华为账号",
                    "多屏协同需要登录同一个华为账号")).isGreaterThan(0.9);
            assertThat(BleuScorer.score("多屏协同需要登录同一个华为账号",
                    "畅连通话可以免费拨打视频电话")).isLessThan(0.2);
            assertThat(BleuScorer.score("短的", "多屏协同需要登录同一个华为账号")).isEqualTo(0.0);
            assertThat(BleuScorer.score(null, "多屏协同")).isEqualTo(0.0);
        }

        @Test
        @DisplayName("答案比标准答案短时施加简洁惩罚（BP < 1）")
        void brevityPenaltyApplied() {
            String gt = "多屏协同需要登录同一个华为账号并且开启蓝牙和无线网络";
            String shortAnswer = "多屏协同需要登录华为账号";
            String fullAnswer = "多屏协同需要登录同一个华为账号并且开启蓝牙和无线网络";
            assertThat(BleuScorer.score(shortAnswer, gt))
                    .isLessThan(BleuScorer.score(fullAnswer, gt));
        }
    }
}
