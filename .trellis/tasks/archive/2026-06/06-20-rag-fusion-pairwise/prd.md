# PR-B: fusionTopK 解耦 + pairwise 阈值联动

**Parent**: `06-20-rag-rerank-mmr-reorder`（技术设计见其 `design.md` §6.5/§3）
**依赖**: PR-A（`rerankTopN=20`）
**复杂度**: 中等

## 背景

`HybridSearchService.rrfFusion`（L220）`.limit(properties.vectorTopK())` 把「向量召回量」配置当「融合后最终召回量」复用，职责耦合 → 两路 vector30+bm2530 融合后被 limit 回 30。解耦为独立 `fusionTopK=60` 让召回达 ~60；同步把 `pairwiseCosineDistance` 截断阈值联动到 `max(50, fusionTopK)=60`（否则召回 60 > `MAX_PAIRWISE_DOCS=50` 触发截断，MMR distance 不完整）。详见 parent `design.md` §6.5/§3。

## 需求

- **R1 fusionTopK 提参**：加到 `RagRetrievalProperties`（默认 60）+ 校验 `fusionTopK >= rerankTopN`；`withOverrides` 透传。注意 withOverrides 5 参破坏性 —— 优先 builder 模式或保留旧 3 参方法委托新方法（架构 review M3）。
- **R2 rrfFusion 解耦**：L220 `.limit(vectorTopK)` → `.limit(fusionTopK)`；`vector-top-k` 回归只管 vectorSearch。先 `impact rrfFusion`。
- **R3 pairwise 阈值联动**：`VectorStoreMapper` 的 `MAX_PAIRWISE_DOCS` 从硬编码 50 改为可配置 `max(50, fusionTopK)`；**必须改 mapper 常量本身**，不能只在 MMR 层 subList（会被 mapper 内部 50 二次截断 → 死循环，架构 review M4）。先 `impact MAX_PAIRWISE_DOCS`（memory R1-L5，确认无其他引用）。
- **R4 配置**：`application-dev.yml` 加 `fusion-top-k: 60`。

## 执行前 gate

- `impact rrfFusion`、`impact pairwiseCosineDistance / MAX_PAIRWISE_DOCS`。

## 验收

- [ ] `fusion-top-k=60` 生效，`rrfFusion` 不再复用 `vector-top-k`，召回达 ~60。
- [ ] pairwise 覆盖全 60 条（无 50 截断），MMR 查 distance 无 key miss。
- [ ] A/B（召回 30 vs 60）质量不退化；Rerank 处理 60 条（用户确认 OK）。

## 执行步骤

见 parent `design.md` §6.5 与 `implement.md` PR-B 节。改动文件：`RagRetrievalProperties`、`HybridSearchService`、`VectorStoreMapper`、`application-dev.yml`。
