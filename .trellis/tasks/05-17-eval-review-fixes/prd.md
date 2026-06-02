# RAG Evaluation 模块审查修复

> 审查时间: 2026-05-17
> 基于: evaluation 子模块 28 源文件 code review
> 分支: eval-rag-dev

## P0 — 必须修复（spec 违规 + 安全）

- [x] #1 `EvaluationExecutionService.executeRun` 移除 `@Transactional`（无原子性需求，逐条 insert + 最后单条 UPDATE）
- [x] #2 `application-evaluation.yml` API Key 硬编码默认值 → 改为 `${ZHIPU_API_KEY:CHANGE_ME}` 占位符
- [x] #3 `EvaluationItemStatus.fromValue` / `EvaluationRunStatus.fromValue` 抛 `IllegalArgumentException` → 改 `IllegalStateException`
- [x] #4 `DatasetExporter.exportAsJson` 抛 `IllegalArgumentException` → 改 `BusinessException`

## P1 — 短期改进

- [ ] #5 `EvaluationExecutionService.executeRun` 串行长执行风险 → 改异步执行
- [x] #6 `DatasetGenerator.sampleChunks` JSON 字符串拼接 → 改用 ObjectMapper
- [ ] #7 `EvaluationRunController.compareRuns` + `DatasetController.generateDataset` Map 入参 → 改 record DTO
- [x] #8 `StageSnapshot.extractDocIds()` 死代码 → 删除
