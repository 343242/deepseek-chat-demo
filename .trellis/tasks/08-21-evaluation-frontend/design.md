# Design — 评估模块前端工作台

设计源头：`docs/frontend/wireframes/09-evaluation.md` v0.1.1（布局/交互/状态全集）+ `DESIGN-SYSTEM.md` §11.17（EvalRunProgress）+ EVAL-1..6 决策。本文只记录技术落地决策与偏差。

## 1. 传输层：apiFetch raw 模式（EVAL-2）

评估控制器返回裸 JSON（无 GlobalResponse），但异常路径经 GlobalExceptionHandler 返回 **HTTP 200 + 错误信封**，控制器自身错误返回 **4xx + `{"error": "..."}`**。三种形态并存。

方案：`ApiFetchOptions` 增加可选 `raw?: boolean`（api-fetch.ts JSDoc 第 80 行已承诺"raw 模式"但未实现，本次补全）：

```
raw 分支（JSON 响应）：
  isGlobalResponse(body) → code!==0 抛 ApiError（覆盖 200 信封错误）；code===0 返回 data（防御，正常不出现）
  裸 JSON + res.ok       → 原样返回（覆盖 202/200）
  裸 JSON + !res.ok      → 提取 body.error 抛 ApiError
非 raw 路径逐字节不变（GitNexus impact HIGH：8 个直接调用方，均为加参不改行为）
```

复用 401 refresh 单例（IA-6）、buildUrl、credentials。**否决独立 eval-fetch.ts**：会造出第二个 refresh 锁，违反 IA-6 并发 401 单 refresh 约定。

## 2. SSE 策略

- **Run 进度 = SSE**（DS §11.17 硬性要求）：`lib/sse.ts` 新增 `subscribeEvalRunEvents(runId, handlers)`——原生 EventSource（GET 端点，规范允许自动重连），分发 progress/done/error，终态后主动 close，返回 disposer。onerror 由组件兜底转轮询 run 状态。规范限定 SSE 代码只能在 lib/sse.ts，故扩展该文件而非新文件。
- **生成任务 = 轮询**（偏差记录）：wireframe §2.1 只要求"按钮 loading + 完成后刷新"，未要求 SSE；任务状态持久在库，轮询（3s refetchInterval）跨刷新天然恢复，少维护一条重连通路。后端 SSE 端点保留不用。

## 3. 分页 0/1 基

后端 0 基（EVAL-2），全站 UI 1 基。换算收口在 `api/evaluation.ts` 的 queryFn（page - 1），组件无感知。datasets 响应 `{datasets,total,page,size}` 非 PagedResult，API 层适配为统一形状。

## 4. jsonb 防御解包

`/runs/{id}/results` 行的 retrieval_metrics / generation_metrics 为 JdbcTemplate 读 jsonb：pg 驱动可能给 PGobject（序列化为 `{"type":"jsonb","value":"<json>"}`）、JSON 字符串或已解对象三种形态。`parseMetricField` 三态归一（lib/eval-metrics.ts 纯函数 + 单测）。

## 5. compare 键控防御

`POST /runs/compare` 按 **run name** 键控（后端现状，重名互相覆盖）。前端以选中顺序的 runId 列表为准渲染列，从 comparison 里按 runId 反查条目；查不到（重名被覆盖）显示"—"。

## 6. URL 状态

`?dataset=<id>&tab=detail|runs|compare&run=<runId>`（useSearchParams）。runs Tab 内结果子视图由 `run` 参数承载；进度面板为临时浮层不入 URL。

## 7. 文件布局

```
types/evaluation.ts                      DTO 镜像（对齐 backend record 字段）
lib/api-fetch.ts                         +raw 模式
lib/sse.ts                               +subscribeEvalRunEvents
lib/status-meta.ts                       +3 组状态元数据（item/run/generation job）
lib/constants.ts                         +EVALUATION 常量组（并发 2、对比 10、页大小）
lib/eval-metrics.ts                      纯函数：parseMetricField/formatMetric/summarizeConfig/buildCompareRows
api/evaluation.ts                        evalKeys + fetcher + hooks
components/evaluation/dataset-list.tsx   左栏
components/evaluation/generate-dataset-dialog.tsx
components/evaluation/item-edit-dialog.tsx
components/evaluation/dataset-detail-tab.tsx
components/evaluation/start-run-dialog.tsx
components/evaluation/run-progress-panel.tsx   （DS §11.17）
components/evaluation/runs-tab.tsx
components/evaluation/results-view.tsx
components/evaluation/compare-tab.tsx
pages/admin/evaluation-page.tsx          编排（重写占位符）
```

组件遵循 usage-page / knowledge-page 模式：PageContainer、plain `<table>`、Dialog/Sheet、StatusBadge、EmptyState、Skeleton、toast(sonner)。

## 8. 测试

- `lib/__tests__/eval-metrics.test.ts`：三态解包 / -1 哨兵 / config 摘要 / 对比差值（表驱动中文用例）
- `api/__tests__/evaluation.test.ts`：mock apiFetch，断言 raw 调用路径、0/1 基换算、请求体形状
