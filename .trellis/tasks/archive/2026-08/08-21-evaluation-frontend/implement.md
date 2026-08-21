# Implement — 评估模块前端工作台

执行顺序（每步可独立 typecheck 验证）：

## Step 1 — 传输层
- [x] `lib/api-fetch.ts`：ApiFetchOptions + `raw`；raw 分支（信封错误/裸成功/裸 4xx error）
- [x] 既有测试回归确认无破坏

## Step 2 — 类型与元数据
- [x] `types/evaluation.ts`：EvalDataset / EvalDatasetItem / GenerationJob / EvalRun / ResultRow / RunSummary / AggregateMetrics / CompareResponse / EvalConfigOverride / 进度事件
- [x] `lib/status-meta.ts`：+EVALUATION_ITEM_STATUS_META / EVALUATION_RUN_STATUS_META / GENERATION_JOB_STATUS_META
- [x] `lib/constants.ts`：+EVALUATION 常量组

## Step 3 — API 层
- [x] `api/evaluation.ts`：evalKeys 工厂；raw fetcher；0/1 基换算；datasets 形状适配
- [x] hooks：useDatasets（infinite）/ useDataset / useGenerationJob（3s 轮询）/ useUpdateItem / useStartRun / useRuns / useResults / useCompareRuns
- [x] exportDataset blob 下载

## Step 4 — SSE
- [x] `lib/sse.ts`：+subscribeEvalRunEvents（EventSource，progress/done/error，终态 close，disposer）

## Step 5 — 纯函数 + 单测
- [x] `lib/eval-metrics.ts`：parseMetricField / formatMetric / summarizeConfig / buildCompareRows
- [x] `lib/__tests__/eval-metrics.test.ts`
- [x] `api/__tests__/evaluation.test.ts`

## Step 6 — 组件与页面
- [x] `components/evaluation/dataset-list.tsx`
- [x] `generate-dataset-dialog.tsx`（表单 → 202 → 轮询进度 → 刷新 + toast）
- [x] `item-edit-dialog.tsx`（部分更新语义）
- [x] `dataset-detail-tab.tsx`（状态分布 + 条目表前端分页 + 导出/启动入口）
- [x] `start-run-dialog.tsx`（configOverride 表单 + EVAL-6 帮助文本）
- [x] `run-progress-panel.tsx`（DS §11.17）
- [x] `runs-tab.tsx`（run 列表 + 结果子视图）
- [x] `results-view.tsx`（summary 头 + 结果表 0 基分页）
- [x] `compare-tab.tsx`（多选 + 基线对比表）
- [x] `pages/admin/evaluation-page.tsx` 编排（URL query 持久化）

## Step 7 — 收尾
- [x] `bun run typecheck && lint && test:run && build` 四门全绿
- [x] GitNexus detect_changes 影响面核对
- [x] trellis-update-spec（data-and-state.md：raw 模式契约 + SSE 双路径）
- [ ] 批量提交计划一次性确认（不 push）

## 验证基准
- 线框 09 §1–§5 逐条对照（布局/字段/状态全集/空态文案）
- DS §11.17 EvalRunProgress 组件规范
- 后端契约以 `DatasetController` / `EvaluationRunController` 实测响应为准（裸 JSON、0 基、202）

## 完成记录（2026-08-21）

- 四门全绿：typecheck ✓ / lint 0 error（5 个存量 warning：api-fetch TEMP-DEBUG console ×2、slider-captcha ×2、login-page ×1）/ test 115 通过（新增 24：eval-metrics 21 + evaluation api 3）/ build ✓（evaluation-page 39.77kB 懒加载分块）
- detect_changes：LOW，10 个 changed 符号（api-fetch 系 + status-meta + evaluation-page），0 affected
- 实现偏差记录：
  - 生成任务终态不再自动关闭对话框（留在终态展示由用户关闭），以满足 react-hooks/set-state-in-effect 规则；"后台生成"路径保留轮询
  - ItemEditDialog 改为 key 重挂载回填（key={item.id}），替代 effect 回填，同因 lint 规则
  - RunProgressPanel 手动"刷新"重置终态/断连标记并重订阅（状态清理收口在事件处理器）
  - compare 列以选中顺序 + runId 反查构造（后端按 run name 键控），重名覆盖丢失的列整列 '—'
- ⚠️ 工作树存在非本任务变更：`frontend/src/components/common/page-placeholder.tsx`（PageContainer 加 overflow-y-auto + 注释），提交计划中单独列出待用户确认
