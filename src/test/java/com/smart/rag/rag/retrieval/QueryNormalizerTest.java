package com.smart.rag.rag.retrieval;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * QueryNormalizer 单元测试。
 * <p>
 * 验证全角→半角转换、NFC 归一化、空白压缩、边界条件。
 * </p>
 */
class QueryNormalizerTest {

    private final QueryNormalizer normalizer = new QueryNormalizer();

    @Nested
    @DisplayName("全角→半角转换")
    class FullwidthToHalfwidth {

        @ParameterizedTest(name = "\"{0}\" → \"{1}\"")
        @DisplayName("全角 ASCII 字符转半角")
        @CsvSource({
                "Ｈello, Hello",
                "ＲＡＧ检索, RAG检索",
                "１２３, 123",
                "ＡＢＣｄｅｆ, ABCdef",
                "！＠＃, ！＠＃"
        })
        void fullwidthAscii_toHalfwidth(String input, String expected) {
            assertEquals(expected, normalizer.normalize(input));
        }

        @Test
        @DisplayName("全角空格转半角")
        void fullwidthSpace_toHalfwidthSpace() {
            assertEquals("hello world", normalizer.normalize("hello　world"));
        }

        @Test
        @DisplayName("混合全角半角统一为半角")
        void mixedFullHalfwidth() {
            assertEquals("RAG v2．0", normalizer.normalize("ＲＡＧ　v２．０"));
        }
    }

    @Nested
    @DisplayName("空白压缩")
    class WhitespaceCompression {

        @Test
        @DisplayName("多个连续空格压缩为单个")
        void multipleSpaces_toSingle() {
            assertEquals("hello world", normalizer.normalize("hello    world"));
        }

        @Test
        @DisplayName("混合空白字符（空格、制表、换行）压缩")
        void mixedWhitespace() {
            assertEquals("hello world foo", normalizer.normalize("hello \t world\n\nfoo"));
        }

        @Test
        @DisplayName("首尾空白去除")
        void leadingTrailingWhitespace() {
            assertEquals("hello", normalizer.normalize("  hello  "));
        }
    }

    @Nested
    @DisplayName("NFC 归一化")
    class NfcNormalization {

        @Test
        @DisplayName("组合字符合并")
        void combiningCharacters_merged() {
            // e + U+0301 (combining acute) → é (U+00E9)
            String decomposed = "e\u0301";
            String normalized = normalizer.normalize(decomposed);
            assertEquals("é", normalized);
        }
    }

    @Nested
    @DisplayName("中文保留")
    class ChinesePreserved {

        @Test
        @DisplayName("中文字符不做转换")
        void chineseCharacters_preserved() {
            assertEquals("你好世界", normalizer.normalize("你好世界"));
        }

        @Test
        @DisplayName("中文标点保留")
        void chinesePunctuation_preserved() {
            assertEquals("你好，世界！", normalizer.normalize("你好，世界！"));
        }

        @Test
        @DisplayName("中英混排归一化")
        void mixedChineseEnglish() {
            assertEquals("Spring Boot 3.2 框架", normalizer.normalize("Ｓpring　Boot ３.2　框架"));
        }
    }

    @Nested
    @DisplayName("边界条件")
    class EdgeCases {

        @Test
        @DisplayName("null 输入返回空字符串")
        void nullInput_returnsEmpty() {
            assertEquals("", normalizer.normalize(null));
        }

        @Test
        @DisplayName("空字符串返回空字符串")
        void emptyInput_returnsEmpty() {
            assertEquals("", normalizer.normalize(""));
        }

        @Test
        @DisplayName("纯空白字符串返回空字符串")
        void blankInput_returnsEmpty() {
            assertEquals("", normalizer.normalize("   \t\n  "));
        }

        @Test
        @DisplayName("已经是归一化的文本不变")
        void alreadyNormalized_unchanged() {
            assertEquals("hello world 123", normalizer.normalize("hello world 123"));
        }
    }
}
