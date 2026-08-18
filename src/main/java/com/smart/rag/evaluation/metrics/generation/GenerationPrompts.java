package com.smart.rag.evaluation.metrics.generation;

/**
 * 生成侧各 LLM 指标的 prompt 模板集中定义。
 * <p>
 * 原先分散在各 Scorer 方法内的文本块（含重复的声明提取/验证 prompt），
 * 集中后便于统一审校措辞与输出约束。占位符用 {@code %s}，调用方 {@code .formatted(...)} 填充。
 * </p>
 */
public final class GenerationPrompts {

    private GenerationPrompts() {
    }

    /** 声明提取（AnswerCorrectness 的陈述版，语义与 CLAIM_EXTRACTION 相同） */
    static final String STATEMENT_EXTRACTION = """
            给定以下文本，提取其中所有独立的事实性陈述，每条一个可验证的原子事实。

            文本：
            %s

            输出 JSON 数组（不要输出其他内容）：
            ["陈述1", "陈述2"]
            """;

    /** 声明提取（Faithfulness / ContextRecall 共用） */
    static final String CLAIM_EXTRACTION = """
            给定以下回答，提取其中所有事实性声明。
            每个声明应是一个独立的、可验证的事实陈述。

            回答：
            %s

            输出 JSON 数组（不要输出其他内容）：
            [
              "声明1",
              "声明2"
            ]
            """;

    /** 声明验证（Faithfulness / ContextRecall 共用） */
    static final String CLAIM_VERIFICATION = """
            给定以下上下文（检索到的文档片段）和一组声明。
            判断每个声明是否可以从上下文中推导出来。

            上下文：
            %s

            声明：
            %s

            输出 JSON（只包含 verifications 数组，不要输出汇总分数）：
            {
              "verifications": [
                {"claim": "...", "supported": true},
                {"claim": "...", "supported": false}
              ]
            }
            """;

    /** TP/FP/FN 分类（AnswerCorrectness） */
    static final String TP_FP_FN_CLASSIFICATION = """
            给定问题、回答的陈述列表与标准答案的陈述列表，对回答的每条陈述分类：
            - TP（真阳性）：回答中的陈述，且被标准答案的一条或多条陈述直接支撑
            - FP（假阳性）：回答中的陈述，但不被标准答案任何陈述支撑
            - FN（假阴性）：标准答案中的陈述，但回答中未出现
            每条陈述只属于一个类别。

            问题：%s
            回答陈述：%s
            标准答案陈述：%s

            输出 JSON（不要输出其他内容）：
            {"TP": ["陈述", ...], "FP": ["陈述", ...], "FN": ["陈述", ...]}
            """;

    /** 片段有用度评分（ContextRelevance，Few-Shot） */
    static final String CHUNK_USEFULNESS = """
            给定以下用户问题和检索到的文档片段，评估每个片段对回答问题的有用程度。

            示例：
            问题：什么是 RAG？
            片段1："RAG（Retrieval-Augmented Generation）是一种结合检索和生成的 AI 技术。"
            → usefulness: 5（直接包含答案）
            片段2："Transformer 架构由 Google 在 2017 年提出。"
            → usefulness: 1（与问题无关）

            ---

            问题：%s

            文档片段：
            %s

            请评估每个文档片段的有用程度。
            评分标准：
            - 5分：直接包含答案
            - 4分：高度相关，提供重要线索
            - 3分：部分相关，提供背景信息
            - 2分：轻微相关
            - 1分：完全无关

            输出 JSON（只包含 chunk_scores 数组，不要输出汇总比例）：
            {
              "chunk_scores": [
                {"chunk_index": 0, "usefulness": 4, "reason": "..."},
                {"chunk_index": 1, "usefulness": 1, "reason": "..."}
              ]
            }
            """;

    /** 片段对参考答案有用性判决（ContextPrecisionLlm） */
    static final String CHUNK_VERDICT_FOR_REFERENCE = """
            给定问题、标准答案与一组检索片段。逐片段判断：该片段对推导出标准答案是否有用。
            有用输出 "1"，无用输出 "0"。

            问题：%s
            标准答案：%s

            %s
            输出 JSON（不要输出其他内容，每个片段一项，按顺序）：
            {"verdicts": [{"index": 1, "verdict": 1, "reason": "..."}, ...]}
            """;

    /** 片段有用性 + 回答支撑双判决（NoiseSensitivity） */
    static final String CHUNK_DUAL_VERDICT = """
            给定问题、生成的回答与一组检索片段。逐片段判断两项：
            - useful：该片段对回答该问题是否有用
            - supports_answer：仅凭该片段能否支撑生成的回答（回答内容可由该片段推导）

            问题：%s
            生成的回答：%s

            %s
            输出 JSON（不要输出其他内容，每个片段一项，按顺序）：
            {"chunks": [{"index": 1, "useful": true, "supports_answer": false}, ...]}
            """;

    /** 回答相对标准答案正确性判决（NoiseSensitivity） */
    static final String ANSWER_CORRECTNESS_CHECK = """
            给定生成的回答与标准答案。判断：生成的回答是否完整覆盖了标准答案的全部要点，
            且没有与标准答案矛盾的内容。满足则 correct=true。

            生成的回答：%s
            标准答案：%s

            输出 JSON（不要输出其他内容）：
            {"correct": true, "reason": "..."}
            """;

    /** 标准答案分解为原子主张（FactualCorrectness） */
    static final String CLAIM_DECOMPOSITION = """
            把以下标准答案分解为原子主张列表：每条主张是一个独立的、最小粒度的事实陈述。

            标准答案：
            %s

            输出 JSON 数组（不要输出其他内容）：
            ["主张1", "主张2"]
            """;

    /** 主张对照回答验证（FactualCorrectness） */
    static final String CLAIM_VS_ANSWER_VERIFICATION = """
            给定一个回答与一组主张。逐主张判断：该主张能否从回答中直接推导出来。

            回答：
            %s

            主张：
            %s

            输出 JSON（不要输出其他内容）：
            {"verifications": [{"index": 1, "supported": true, "reason": "..."}, ...]}
            """;

    /** 反向问题生成（AnswerRelevance，经 LlmJudge.generateQuestions） */
    public static final String REVERSE_QUESTION_GENERATION = """
            给定以下回答，生成 3 个该回答可能回应的问题。
            问题应该简洁、具体。

            回答：
            %s

            输出 JSON 数组（不要输出其他内容）：
            [
              "问题1",
              "问题2",
              "问题3"
            ]
            """;

    /** RAG 回答生成（EvaluationRunner 的生成阶段） */
    public static final String RAG_ANSWER = """
            基于以下检索到的文档片段回答用户的问题。
            如果文档片段中没有相关信息，请如实说明。

            文档片段：
            %s

            用户问题：%s

            回答：""";
}
