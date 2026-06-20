# PR-A: Rerank→MMR 顺序调换 + topN 提参

**Parent**: `06-20-rag-rerank-mmr-reorder`（技术设计见其 `design.md` §3/§6）
**依赖**: 无（基础子任务）
**复杂度**: 轻量（prd-only 可行）

## 范围

postProcessor 链从 **MMR→Rerank→Parent** 调换为 **Rerank→MMR→Parent**（两个独立处理器**串行**，不复合）；Rerank topN 从硬编码 10 提参为 `rerank-top-n=20`。本 PR **不并行、不召回扩大**（召回仍 30，Rerank 30→20→MMR 10，链路可工作）。

## 需求

- **R1 顺序调换**：`RagAdvisorFactory.buildPostProcessors`（L179-201）+ `retrieve()`（L115-128）两路径顺序改 Rerank→MMR→Parent。MMR 跑在 Rerank 后，能取 `rerankScore` 作相关性（其设计本意，类注释 L16 + `resolveRelevanceScore` L151）。
- **R2 topN 提参**：`rerankTopN` 加到 `RagRetrievalProperties`（默认 20）；`RagConfig.rerankDocumentPostProcessor`（L80-86）读 properties，删硬编码 10；`withOverrides` 透传。
- **R3 候选池校验**：compact constructor 校验 `rerankTopN > mmrTopK`（否则 MMR 命中早退 L62 退化为 no-op），fail-fast。
- **R4 注释同步**：`MmrDocumentPostProcessor` 类注释、`resolveRelevanceScore` fallback 注释（L159）、`retrieve()` 注释（L111）更新为新顺序。

## 验收

- [ ] 两路径顺序均为 Rerank→MMR→Parent。
- [ ] `rerank-top-n` 可配（默认 20）；`rerankTopN <= mmrTopK` 启动即失败。
- [ ] `mvnw test` 绿。
- [ ] A/B（MMR→Rerank 旧 vs Rerank→MMR 新，均串行）召回质量不退化。

## 执行步骤

见 parent `design.md` §3（数据流）/§6（提参）与 `implement.md` PR-A 节。改动文件：`RagRetrievalProperties`、`RagConfig`、`application-dev.yml`、`RagAdvisorFactory`（顺序 + 注释）。
