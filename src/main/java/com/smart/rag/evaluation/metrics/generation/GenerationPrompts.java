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

    /** 片段对生成回答有用性判决（ContextUtilization，ragas LLMContextPrecisionWithoutReference） */
    static final String CHUNK_VERDICT_FOR_ANSWER = """
            给定问题、生成的回答与一组检索片段。逐片段判断：该片段对得出该回答是否有用。
            有用输出 "1"，无用输出 "0"。

            问题：%s
            生成的回答：%s

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

    /** 原子主张分解（FactualCorrectness，双向服务 reference 与 response 的分解） */
    static final String CLAIM_DECOMPOSITION = """
            把以下文本分解为原子主张列表：每条主张是一个独立的、最小粒度的事实陈述。

            文本：
            %s

            输出 JSON 数组（不要输出其他内容）：
            ["主张1", "主张2"]
            """;

    /** 主张对照前提文本验证（FactualCorrectness 双向：response主张vs reference、reference主张vs response） */
    static final String CLAIM_VS_PREMISE_VERIFICATION = """
            给定一段前提文本与一组主张。逐主张判断：该主张能否从前提文本中直接推导出来。

            前提文本：
            %s

            主张：
            %s

            输出 JSON（不要输出其他内容）：
            {"verifications": [{"index": 1, "supported": true, "reason": "..."}, ...]}
            """;

    /** 反向问题生成（AnswerRelevance，经 LlmJudge.generateQuestionsWithFlags；单次一个问题 + noncommittal 判定） */
    public static final String REVERSE_QUESTION_WITH_FLAG = """
            给定以下回答，完成两件事：
            1. 生成一个该回答最可能在回应的问题（简洁、具体）
            2. 判断该回答是否为含糊回避型（noncommittal）：回答逃避问题、含糊其辞、模棱两可，
               或属于「我不知道 / 无法确定」这类未给出实质内容的表现则判定为 true

            示例：
            回答："抱歉，我不知道这个问题的答案。"
            → {"question": "导致这个现象的原因是什么？", "noncommittal": true}

            回答："RAG 是一种结合检索与生成的 AI 架构，先检索相关文档再基于文档生成回答。"
            → {"question": "什么是 RAG？", "noncommittal": false}

            回答：
            %s

            输出 JSON（不要输出其他内容）：
            {"question": "...", "noncommittal": false}
            """;

    /** 实体抽取（ContextEntityRecall，翻译 ragas ExtractEntitiesPrompt 指令与 4 个 few-shot） */
    static final String ENTITY_EXTRACTION = """
            给定一段文本，提取其中的唯一实体，不重复；同一实体的不同表述或指称视为一个实体。

            示例 1：
            文本："The Eiffel Tower, located in Paris, France, is one of the most iconic landmarks globally. Millions of visitors are attracted to it each year for its breathtaking views of the city. Completed in 1889, it was constructed in time for the 1889 World's Fair."
            → {"entities": ["Eiffel Tower", "Paris", "France", "1889", "World's Fair"]}

            示例 2：
            文本："The Colosseum in Rome, also known as the Flavian Amphitheatre, stands as a monument to Roman architectural and engineering achievement. Construction began under Emperor Vespasian in AD 70 and was completed by his son Titus in AD 80."
            → {"entities": ["Colosseum", "Rome", "Flavian Amphitheatre", "Vespasian", "AD 70", "Titus", "AD 80"]}

            示例 3：
            文本："The Great Wall of China, stretching over 21,196 kilometers from east to west, is a marvel of ancient defensive architecture. Built to protect against invasions from the north, its construction started as early as the 7th century BC. Today, it is a UNESCO World Heritage Site."
            → {"entities": ["Great Wall of China", "21,196 kilometers", "7th century BC", "UNESCO World Heritage Site"]}

            示例 4：
            文本："The Apollo 11 mission, which launched on July 16, 1969, marked the first time humans landed on the Moon. Astronauts Neil Armstrong, Buzz Aldrin, and Michael Collins made history, with Armstrong being the first man to step on the lunar surface."
            → {"entities": ["Apollo 11 mission", "July 16, 1969", "Moon", "Neil Armstrong", "Buzz Aldrin", "Michael Collins"]}

            文本：
            %s

            输出 JSON（不要输出其他内容，实体保持原文形式，不要翻译）：
            {"entities": ["实体1", "实体2"]}
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
