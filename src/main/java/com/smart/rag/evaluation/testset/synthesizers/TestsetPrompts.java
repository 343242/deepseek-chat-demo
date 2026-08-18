package com.smart.rag.evaluation.testset.synthesizers;

/**
 * 测试集生成提示词（翻译自 ragas v0.4.3 五个 prompt 类：Themes 在
 * {@link com.smart.rag.evaluation.testset.transforms.ThemesExtractor}，此处为其余四个）。
 * <p>
 * 指令保留 ragas 英文原文语义，统一追加中文输出约束（Python 参照实现验证过的方案）。
 * 输出一律 JSON，经 JsonExtractorUtil + Jackson 解析。
 * </p>
 */
final class TestsetPrompts {

    private TestsetPrompts() {
    }

    /** 中文输出约束（对应 Python 参照实现 generate_testset.py 的 ZH_INSTRUCTION_SUFFIX）。 */
    static final String ZH_SUFFIX = """


            重要约束：
            1. 无论以上说明使用什么语言，生成的 query 和 answer 必须使用简体中文。
            2. query 要贴近真实中文用户的提问口吻，自然直接，不要翻译腔，不要出现「根据文档」之类的表述。
            3. answer 必须完全来自给定 context，使用简体中文作答。""";

    /** persona × 主题匹配（翻译 ThemesPersonasMatchingPrompt）。 */
    static final String PERSONA_MATCHING = """
            Given a list of themes and personas with their roles, \
            associate each persona with relevant themes based on their role description.

            示例：
            themes: ["共情", "包容性", "远程办公"]
            personas: ["HR Manager（关注包容性与员工支持）", "Remote Team Lead（管理远程团队沟通）"]
            输出: {"mapping": {"HR Manager": ["包容性", "共情"], "Remote Team Lead": ["远程办公", "共情"]}}

            主题列表：
            %s

            persona 列表（名称与职责）：
            %s

            输出 JSON（不要输出其他内容，mapping 的值使用主题列表中的原文）：
            {"mapping": {"<persona名>": ["<主题>", ...]}}
            """;

    /** 单跳问答生成（翻译 single_hop/prompts.py QueryAnswerGenerationPrompt）。 */
    static final String SINGLE_HOP_QA = """
            Generate a single-hop query and answer based on the specified conditions \
            (persona, term, style, length) and the provided context. Ensure the answer is entirely \
            faithful to the context, using only the information directly from the provided context.
            ### Instructions:
            1. **Generate a Query**: Based on the context, persona, term, style, and length, create a question \
            that aligns with the persona's perspective and incorporates the term.
            2. **Generate an Answer**: Using only the content from the provided context, construct a detailed answer \
            to the query. Do not add any information not included in or inferable from the context.
            """ + ZH_SUFFIX + """

            persona：%s（%s）
            主题词 term：%s
            问题风格 query_style：%s
            问题长度 query_length：%s

            context：
            %s

            输出 JSON（不要输出其他内容）：
            {"query": "...", "answer": "..."}
            """;

    /** 多跳问答生成（翻译 multi_hop/prompts.py QueryAnswerGenerationPrompt）。 */
    static final String MULTI_HOP_QA = """
            Generate a multi-hop query and answer based on the specified conditions \
            (persona, themes, style, length) and the provided context. The themes represent a set of phrases \
            either extracted or generated from the context, which highlight the suitability of the selected \
            context for multi-hop query creation. Ensure the query explicitly incorporates these themes.
            ### Instructions:
            1. **Generate a Multi-Hop Query**: Use the provided context segments and themes to form a query that \
            requires combining information from multiple segments (e.g., `<1-hop>` and `<2-hop>`). Ensure the query \
            explicitly incorporates one or more themes and reflects their relevance to the context.
            2. **Generate an Answer**: Use only the content from the provided context to create a detailed and \
            faithful answer to the query. Avoid adding information that is not directly present or inferable \
            from the given context.
            3. **Multi-Hop Context Tags**:
               - Each context segment is tagged as `<1-hop>`, `<2-hop>`, etc.
               - Ensure the query uses information from at least two segments and connects them meaningfully.
            """ + ZH_SUFFIX + """


            persona：%s（%s）
            主题 themes：%s
            问题风格 query_style：%s
            问题长度 query_length：%s

            context（多段，带 hop 标签）：
            %s

            输出 JSON（不要输出其他内容）：
            {"query": "...", "answer": "..."}
            """;

    /** 概念组合（翻译 multi_hop/prompts.py ConceptCombinationPrompt）。 */
    static final String CONCEPT_COMBINATION = """
            Form combinations by pairing concepts from at least two different lists.
            **Instructions:**
            - Review the concepts from each node.
            - Identify concepts that can logically be connected or contrasted.
            - Form combinations that involve concepts from different nodes.
            - Each combination should include at least one concept from two or more nodes.
            - List the combinations clearly and concisely.
            - Do not repeat the same combination more than once.
            - 组合中的概念使用列表原文，不要翻译。

            各节点概念列表：
            %s

            最多生成 %d 个组合。

            输出 JSON（不要输出其他内容）：
            {"combinations": [["概念A", "概念B"], ...]}
            """;
}
