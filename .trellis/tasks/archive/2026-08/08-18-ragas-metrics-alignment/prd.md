# Ragas 指标对齐（4 个 LLM Scorer + 字符串指标）

## Goal
以 ragas 0.4.3 legacy metrics（ragas.evaluate() 实际路径）提示词为准，为 evaluation 模块补齐缺失指标：
AnswerCorrectness、NoiseSensitivity、ContextPrecisionLlm、FactualCorrectness 四个 LLM Scorer
+ RougeL/Bleu 字符级确定性指标（中文按字符 n-gram，零分词依赖、零 LLM 成本）。

## Requirements
1. 四个新 Scorer 遵循现有 Scorer 模式（LlmJudge/ChatClient + JsonExtractorUtil + 本地计算，-1 哨兵约定）
2. RougeL/BleuScorer：纯 Java 字符级实现（中文字符即 token，英文落回字符 n-gram 亦可），无新依赖
3. 挂进 GenerationMetricsCalculator 编排与 GenerationMetrics record；DB 无迁移（JSONB 自然扩展）
4. 现有 4 个 Scorer（faithfulness/contextRecall/answerRelevance/contextRelevance）行为不动

## Non-goals
- HHEM（离线 NLI 模型）、agent 指标、多模态；rouge/bleu 之外的字符串指标（chrf 等）

## Acceptance Criteria
- [ ] 4 个 LLM Scorer 有 Mockito 罐头 JSON 测试（成功 + 解析失败路径）
- [ ] RougeL/Bleu 纯算法单测（含中文样本、边界：空串/全同/全异）
- [ ] GenerationMetricsCalculator 接线后聚合 SQL 兼容（-1 过滤约定沿用）
- [ ] mvn test 全绿；detect_changes 影响面在 evaluation 模块内
