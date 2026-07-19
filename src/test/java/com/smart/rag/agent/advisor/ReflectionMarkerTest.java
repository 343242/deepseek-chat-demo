package com.smart.rag.agent.advisor;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ReflectionMarker} 单元测试 — 标记提取/剥离的边界 case。
 * <p>
 * 验证 LLM 不可靠输出下的解析鲁棒性：嵌套、未闭合、多行 JSON、大小写、缺失。
 */
class ReflectionMarkerTest {

    @Nested
    @DisplayName("extract")
    class Extract {

        @Test
        @DisplayName("标准单标记提取")
        void extract_standardSingleMarker() {
            String text = "检索完成。\n<reflection>\n{\"isRelevant\": true, \"isSufficient\": false}\n</reflection>\n继续。";
            Optional<String> json = ReflectionMarker.REFLECTION.extract(text);

            assertThat(json).isPresent();
            assertThat(json.get()).isEqualTo("{\"isRelevant\": true, \"isSufficient\": false}");
        }

        @Test
        @DisplayName("多行 JSON 跨行提取（DOTALL）")
        void extract_multilineJson() {
            String text = """
                <reflection>
                {
                  "isRelevant": true,
                  "isSufficient": false,
                  "missingAspects": ["a", "b"]
                }
                </reflection>""";
            Optional<String> json = ReflectionMarker.REFLECTION.extract(text);

            assertThat(json).isPresent();
            assertThat(json.get()).contains("\"isRelevant\": true");
            assertThat(json.get()).contains("\"missingAspects\"");
        }

        @Test
        @DisplayName("标记内有解释文字也能提取 JSON")
        void extract_explanatoryTextAroundJson() {
            String text = """
                <reflection>
                我评估了一下：
                {"isRelevant": false}
                以上是我的判断。
                </reflection>""";
            Optional<String> json = ReflectionMarker.REFLECTION.extract(text);

            assertThat(json).isPresent();
            assertThat(json.get()).isEqualTo("{\"isRelevant\": false}");
        }

        @Test
        @DisplayName("同一标记多次出现只取第一个")
        void extract_multipleMarkers_takesFirst() {
            String text = "<reflection>{\"isRelevant\": true}</reflection> 中间文字 <reflection>{\"isRelevant\": false}</reflection>";
            Optional<String> json = ReflectionMarker.REFLECTION.extract(text);

            assertThat(json).isPresent();
            assertThat(json.get()).isEqualTo("{\"isRelevant\": true}");
        }

        @Test
        @DisplayName("标记未闭合返回 empty")
        void extract_unclosedTag_returnsEmpty() {
            String text = "<reflection>{\"isRelevant\": true} 但没有闭合标记";
            Optional<String> json = ReflectionMarker.REFLECTION.extract(text);

            assertThat(json).isEmpty();
        }

        @Test
        @DisplayName("标记内无 JSON 返回 empty")
        void extract_noJsonInside_returnsEmpty() {
            String text = "<reflection>这里没有任何 JSON</reflection>";
            Optional<String> json = ReflectionMarker.REFLECTION.extract(text);

            assertThat(json).isEmpty();
        }

        @Test
        @DisplayName("标记不存在返回 empty")
        void extract_markerAbsent_returnsEmpty() {
            String text = "这是一段没有标记的纯文本响应";
            Optional<String> json = ReflectionMarker.REFLECTION.extract(text);

            assertThat(json).isEmpty();
        }

        @Test
        @DisplayName("null / blank 文本返回 empty")
        void extract_nullOrBlank_returnsEmpty() {
            assertThat(ReflectionMarker.REFLECTION.extract(null)).isEmpty();
            assertThat(ReflectionMarker.REFLECTION.extract("")).isEmpty();
            assertThat(ReflectionMarker.REFLECTION.extract("   ")).isEmpty();
        }

        @Test
        @DisplayName("不同标记类型互不干扰")
        void extract_differentMarkerTypes() {
            String text = """
                <atomic_decision>{"decision": "retrieve"}</atomic_decision>
                <reflection>{"isRelevant": true}</reflection>
                <intermediate_answer>{"answer": "x"}</intermediate_answer>""";

            assertThat(ReflectionMarker.ATOMIC_DECISION.extract(text)).hasValue("{\"decision\": \"retrieve\"}");
            assertThat(ReflectionMarker.REFLECTION.extract(text)).hasValue("{\"isRelevant\": true}");
            assertThat(ReflectionMarker.INTERMEDIATE_ANSWER.extract(text)).hasValue("{\"answer\": \"x\"}");
        }
    }

    @Nested
    @DisplayName("strip")
    class Strip {

        @Test
        @DisplayName("剥离单个标记")
        void strip_singleMarker() {
            String text = "前文。<reflection>{\"x\":1}</reflection>后文。";
            String stripped = ReflectionMarker.REFLECTION.strip(text);

            assertThat(stripped).isEqualTo("前文。后文。");
        }

        @Test
        @DisplayName("剥离多个同类标记（保留中间非标记文本）")
        void strip_multipleMarkers() {
            String text = "<reflection>{\"a\":1}</reflection>keep<reflection>{\"b\":2}</reflection>";
            String stripped = ReflectionMarker.REFLECTION.strip(text);

            assertThat(stripped).isEqualTo("keep");
        }

        @Test
        @DisplayName("无标记时原样返回")
        void strip_noMarker_returnsOriginal() {
            String text = "纯文本";
            assertThat(ReflectionMarker.REFLECTION.strip(text)).isEqualTo("纯文本");
        }

        @Test
        @DisplayName("null 文本原样返回")
        void strip_null_returnsNull() {
            assertThat(ReflectionMarker.REFLECTION.strip(null)).isNull();
        }

        @Test
        @DisplayName("剥离跨行标记（DOTALL）")
        void strip_multilineMarker() {
            String text = "前文\n<reflection>\n{\n  \"x\": 1\n}\n</reflection>\n后文";
            String stripped = ReflectionMarker.REFLECTION.strip(text);

            assertThat(stripped).isEqualTo("前文\n\n后文");
        }
    }

    @Test
    @DisplayName("openTag / closeTag 命名正确")
    void tagNames() {
        assertThat(ReflectionMarker.ATOMIC_DECISION.openTag()).isEqualTo("<atomic_decision>");
        assertThat(ReflectionMarker.ATOMIC_DECISION.closeTag()).isEqualTo("</atomic_decision>");
        assertThat(ReflectionMarker.REFLECTION.openTag()).isEqualTo("<reflection>");
        assertThat(ReflectionMarker.REFLECTION.closeTag()).isEqualTo("</reflection>");
        assertThat(ReflectionMarker.INTERMEDIATE_ANSWER.openTag()).isEqualTo("<intermediate_answer>");
        assertThat(ReflectionMarker.INTERMEDIATE_ANSWER.closeTag()).isEqualTo("</intermediate_answer>");
    }
}
