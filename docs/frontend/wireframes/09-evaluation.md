# 09 · 评估工作台线框

> **页面类型**：工作台（数据集管理 + 评测运行 + 结果对比，后台）
> **路由**：`/admin/evaluation`
> **权限**：`evaluation:manage` + `evaluation` profile 开启（双重门禁，IA 2.3）
> **前置文档**：DESIGN-SYSTEM.md v0.4.0 · INFORMATION-ARCHITECTURE.md v0.3.0
> **状态**：v0.1.1 ASCII 线框（待确认后转 HTML）

> 本页是 RAG 质量闭环：生成数据集 → 人工审核条目 → 启动评测（可调检索/生成配置）→ SSE 实时进度 → 查看逐条指标 → 多 Run 对比。⚠️ 本模块与其他模块有**三处契约差异**（EVAL-2）：响应为**裸 JSON**（无 GlobalResponse 包装）、**分页 page 从 0 起**、启动 Run 返回 **HTTP 202**。另：数据集**只能 LLM 生成**，无手工创建/删除/重命名。
>
> **v0.1.1 打磨**：① AdminShell 外壳对齐 08 篇（TopBar 内容、侧栏全项、折叠钮）；② 启动评测 Modal 补并发上限帮助文本（EVAL-6 要求明示而原图缺失）；③ 对比表基线列补齐 MRR/NDCG 值（基线列不应空缺）；④ "重排 Rerank" 标签去英文冗余（DS 13.1 术语表）。

---

## 1. 整体布局（AdminShell + 左右分栏）

```
┌─ TopBar 56px ───────────────────────────────────────────────────────────┐
│ [≡] [SR] Smart RAG                ← 返回前台              🌓 👤▾        │
├─ Sidebar 240px ─────┬─ Content Area ────────────────────────────────────┤
│  ← 返回前台         │  后台管理 / 评估                                  │
│  ──────────        │ ┌─ 数据集 ───────────┬─ 工作区 ──────────────────┐│
│  📝 系统提示词      │ │ [生成数据集]        │ [数据集详情] [评测运行] [结果对比]│
│  👤 用户管理        │ │ ┌────────────────┐ │                            ││
│  🛡️ 角色权限        │ │ │ rag-qa-50      │ │  （右侧工作区内容）         ││
│  🧪 评估 ●          │ │ │ 100 条 · 8-01  │ │                            ││
│  ──────────        │ │ └────────────────┘ │                            ││
│  (底部)折叠按钮      │ │ ┌────────────────┐ │                            ││
│                     │ │ │ doc-qa-v2      │ │                            ││
│                     │ │ │ 64 条 · 8-10   │ │                            ││
│                     │ │ └────────────────┘ │                            ││
│                     │ └────────────────────┴────────────────────────────┘│
└─────────────────────┴──────────────────────────────────────────────────┘
```

- 左栏数据集列表（选中态 `--bg-selected` + 左侧 3px 指示条）+ 右侧工作区三 Tabs：**数据集详情 / 评测运行 / 结果对比**
- 选中数据集 id 存 URL query（`?dataset=3&tab=runs`），刷新可恢复
- ⚠️ 评估 profile 未开启时后端 404：页面捕获后显示"评估模块未启用 / 需要服务端开启 evaluation profile"占位（IA-1 的 flag 无数据源，只能请求探测）

---

## 2. 数据集管理

### 2.1 生成数据集（唯一创建方式）

`[生成数据集]` → Modal：

| 字段 | 必填 | 说明 |
|------|------|------|
| name | 否 | 缺省后端生成 `dataset-时间戳` |
| userId | ✅ | 采样该用户的知识库文档（LLM 从中出题；默认建议填测试账号 id） |

- 提交 `POST /api/evaluation/datasets/generate` → 生成"50 个分块 × 每块 2 问"共约 100 条（`application-evaluation.yml` 配置）；生成耗时较长，按钮 loading + 完成后刷新列表
- 列表 `GET /api/evaluation/datasets?page=0&size=20`（**page 从 0**），项显示：name、itemCount、version、createdAt

### 2.2 数据集详情 Tab

数据 `GET /api/evaluation/datasets/{id}`（含 items）：

```
┌─ 数据集详情 · rag-qa-50 ────────────────────────────────────────────────┐
│ 100 条 · v1 · LLM 生成 · 2026-08-01        [导出 JSON] [启动评测]        │
│  状态分布: [draft 62] [approved 35] [rejected 3]                          │
│  #  问题                          标准答案        相关分块  状态    操作  │
│  1 什么是混合检索？               混合检索是…      3 个     [已通过] ⋮   │
│  2 MMR 的作用是？                 多样性重排…      2 个     [草稿]   ⋮   │
│                                < 1 2 3 … >           （前端分页/滚动）    │
└──────────────────────────────────────────────────────────────────────────┘
```

**条目字段**（`EvaluationDatasetItem`）：seq、question、groundTruthAnswer（ellipsis + Tooltip）、relevantChunkIds（显示个数，点开列表）、tags（Badge）、status（draft 草稿 neutral / approved 已通过 success / rejected 已拒绝 error）

**条目审核编辑**（点行或"编辑"）→ Modal 部分编辑表单：question / groundTruthAnswer / relevantContent / tags / **status（草稿→已通过/已拒绝 三选）**。提交 `POST /api/evaluation/datasets/{datasetId}/items/{itemId}`——**部分更新语义**（null 字段保留旧值，未改动字段不提交）

**导出 JSON**：`GET /api/evaluation/datasets/{id}/export` 直接下载（人工审核离线用）

> ⚠️ 无"手工新建条目/删除数据集/重命名"端点——不画这些入口（EVAL-4）。工作流是"生成 → 审核 → 用于评测"。

---

## 3. 评测运行 Tab

### 3.1 启动评测（Modal 表单）

```
┌─ 启动评测 · rag-qa-50 ─────────────────────────────┐
│ 运行名称  [ rag-qa-50-topk5            ]（选填）     │
│                                                    │
│ 检索配置                                            │
│  Top K                    [ 10  ]  （1-50）         │
│  重排                     [●] 开                    │
│  MMR 多样性               [●] 开                    │
│  父子分块                 [●] 开                    │
│  查询改写                 [●] 开                    │
│ 生成侧                                              │
│  生成答案并评分            [●] 开（关闭则无生成指标）│
│ 测试用户 ID            [ 1 ]                        │
│ ⓘ 最多同时运行 2 个评测，超出将启动失败             │
│                        [取消]  [启动评测]           │
└─────────────────────────────────────────────────────┘
```

- `configOverride` 键：topK（同时覆盖 vector/bm25/rrf 三路）/ rerankEnabled / mmrEnabled / parentChildEnabled / queryRewriteEnabled / generationEnabled / testUserId——全部可省略走后端默认
- 提交 `POST /api/evaluation/runs` → **202 Accepted** `{runId, status:"running"}` → 自动切到该 Run 的进度视图
- ⚠️ 后端并发上限 **2 个 Run**（超时 60s 抢不到信号量直接 FAILED，summary.error="concurrency limit exceeded"）；**无停止/删除 Run 端点**（EVAL-3）

### 3.2 Run 列表

数据 `GET /api/evaluation/runs/dataset/{datasetId}`（createdAt 倒序）：

| 列 | 来源 EvaluationRun | 备注 |
|----|--------------------|------|
| 运行名 | name | 点行 → 结果视图 |
| 状态 | status（小写枚举） | Badge：pending 待启动 neutral / running 运行中 info（旋转点）/ completed 已完成 success / failed 失败 error |
| 配置摘要 | configSnapshot | 折叠为"topK=10 · 重排 · MMR"式标签 |
| 结果 | summary | "35 通过 · 0 失败 · 平均 2.1s"；failed 显示 error 摘要 |
| 时间 | startedAt/completedAt | DS 13.3 |

- running 行提供"查看进度"→ 打开 §3.3 进度面板（并订阅 SSE）
- 兜底提示：运行超过 30 分钟未结束会被后端 sweeper 标记 FAILED（帮助文本，不必前端计时）

### 3.3 运行进度（SSE 实时）

**EvalRunProgress**（DS 11.17，v0.4.0 落地）。启动后或点"查看进度"时打开（页内面板或 Modal）：

```
┌─ 评测进度 · rag-qa-50-topk5 ─────────────────────┐
│ ▓▓▓▓▓▓▓▓▓░░░░░░░░░░░░░░░  38 / 100               │
│ ✅ 成功 36   ⛔ 失败 2   ⏱ 已运行 1m 12s          │
│ ┌─ 实时日志（最近条目）─────────────────────────┐ │
│ │ #38 ✓ 什么是混合检索？      1.8s             │ │
│ │ #37 ✗ MMR 的作用是？  error: 超时            │ │
│ │ …                                            │ │
│ └──────────────────────────────────────────────┘│
│          [完成后自动跳转结果]  [后台运行]         │
└───────────────────────────────────────────────────┘
```

- 订阅 `GET /api/evaluation/runs/{runId}/events`（SSE，`event:progress` → `{processed, total, successCount, failCount, itemId, status, error, elapsedMs}`；`event:done` → 完成收尾；`event:error` → 连接异常提示 + 手动刷新兜底）
- 断线重连：EventSource 自动重连；迟到订阅后端 replay 最近 20 条
- 完成后（done）→ 失效 runs 列表 → 自动展开结果视图；"后台运行"关闭面板不中断评测（SSE 只是观察窗，不承载执行）

---

## 4. 结果视图与对比

### 4.1 单 Run 结果表

数据 `GET /api/evaluation/runs/{runId}/results?page=0&size=50`（**page 从 0**，裸 JSON）：

| 列 | 来源 | 说明 |
|----|------|------|
| # | item_id → seq 快照 | |
| 问题 | item_question_snapshot | ellipsis + Tooltip |
| 检索指标 | retrieval_metrics | `recall · precision · mrr · ndcg · ctxP`（小数 2 位，数值低于阈值可红色弱化） |
| 生成指标 | generation_metrics | `faith · ctxRecall · ansRel · ctxRel`；⚠️ **-1 为哨兵值**（未启用生成侧）→ 显示"—"，不可当 0 参与均值 |
| 错误 | error | 非空时 error 色 ellipsis |
| 耗时 | latency_ms | DS 13.8 |

页头显示 Run 级 summary（totalItems / success / fail / avgLatencyMs）+ judge/generation 模型 id（mono）

> ⚠️ 结果行**不含 generatedAnswer / 检索快照全文**（EVAL-5）——想看答案全文需查库或后端补详情端点，前端不臆造入口。

### 4.2 多 Run 对比 Tab

```
┌─ 结果对比 ──────────────────────────────────────────────────────┐
│ 选择 Run: ☑ baseline  ☑ topk5  ☑ no-rerank      [开始对比]      │
│ ┌──────────────┬──────────┬──────────┬──────────┐               │
│ │ 指标          │ baseline │ topk5    │ no-rerank│               │
│ │ Recall@K      │ 0.72     │ 0.81 ▲   │ 0.58 ▼   │               │
│ │ Precision@K   │ 0.64     │ 0.70 ▲   │ 0.52 ▼   │               │
│ │ MRR           │ 0.66     │ 0.74 ▲   │ 0.55 ▼   │               │
│ │ NDCG          │ 0.61     │ 0.69 ▲   │ 0.50 ▼   │               │
│ │ Faithfulness  │ 0.85     │ 0.88 ▲   │ —        │               │
│ │ 条目/失败数    │ 100/0    │ 100/2    │ 100/0    │               │
│ └──────────────┴──────────┴──────────┴──────────┘               │
└──────────────────────────────────────────────────────────────────┘
```

- 数据 `POST /api/evaluation/runs/compare { runIds: [...] }` → `{comparison: {runName: {runId, summary, metrics}}}`，metrics 键为 `avg_recall / avg_precision / avg_mrr / avg_ndcg / avg_context_precision / avg_faithfulness / avg_context_recall / avg_answer_relevance / avg_context_relevance / total_items / error_items`
- 展示：指标分**检索侧 / 生成侧**两组行；首个选中 Run 为基线，其余列显示 ▲▼ 差值（`--success-600` / `--error-600`，DS 12.6 色彩不作唯一载体，箭头+数字）；null（该 Run 未开生成侧）显示"—"
- 至少选 2 个 Run 才能对比

---

## 5. 状态全集

| 状态 | 表现 |
|------|------|
| 模块未启用 | 请求 404 → 占位"评估模块未启用 / 需要服务端开启 evaluation profile"（IA-1 无 flag 数据源） |
| 加载 | 数据集列表/表格骨架 |
| 生成中 | 按钮内联 loading（生成数十秒级，完成后 Toast） |
| 运行中 | run 行 info Badge 旋转点 + "查看进度" |
| 失败 | run 行 error Badge，summary.error 在详情/Tooltip 展示 |
| SSE 异常 | 进度面板提示"进度流中断"+ [刷新] 兜底（列表轮询结果） |
| 空状态 | 无数据集："还没有数据集 / 生成数据集即可开始评估"+ [生成数据集]；无运行："还没有评测运行 / 从数据集启动一次评测" |

---

## 6. 引用的设计系统组件

| 组件 | 出处 | 用于 |
|------|------|------|
| EvalRunProgress | DS 11.17（v0.4.0 落地） | SSE 实时进度 |
| Progress | DS 10.17 | 进度条 |
| Badge | DS 10.8 + 4.4 | 条目/运行状态、指标涨跌箭头 |
| Table | DS 10.10 | 条目表 / 结果表 / 对比表（数字右对齐） |
| Tabs | DS 10.13 | 工作区三 Tab |
| Switch | DS 10.7 | configOverride 开关组 |
| Modal | DS 10.11 | 生成数据集 / 启动评测 / 条目编辑 |
| Empty | DS 10.20 + 13.6 | 空状态 |

---

## 7. 待确认事项

| 编号 | 事项 | 影响 | 后续动作 |
|------|------|------|---------|
| EVAL-1 | **feature flag 无数据源**（IA-1） | 入口只能按 `evaluation:manage` 显示，非评估环境点击得 404 | 页面已做 404 占位兜底；建议后端在 `/api/auth/me` 加 `features.evaluation`（IA-1 原议），届时侧栏项精确显隐 |
| EVAL-2 | **三处契约差异**：裸 JSON（无 GlobalResponse）、page 从 0、启动返回 202 | API 层需独立适配，不能复用 `apiFetch` 的 code 判定 | 前端评估模块单独 fetch 封装（判定 HTTP 状态而非 code；分页换 0 基）；建议后端长期统一 |
| EVAL-3 | **无停止/删除 Run 端点** | 失败/误启的 Run 永久留在列表 | 后端补 `POST /runs/{id}/delete`（+可选 cancel）后，run 行菜单加删除 |
| EVAL-4 | **数据集无手工创建/删除/重命名** | 错误生成的数据集无法清理 | 工作流按"生成→审核→评测"设计；后端补 CRUD 后加管理入口 |
| EVAL-5 | **结果行不含 generatedAnswer/检索快照** | 无法在线看失败条目的答案全文 | 逐条 debug 需查库；建议后端补 `GET /runs/{runId}/results/{itemId}` 详情端点 |
| EVAL-6 | **并发上限 2 + 30 分钟 sweeper** | 第 3 个并发 Run 直接失败 | 启动 Modal 帮助文本明示"最多同时运行 2 个评测"；失败 Toast 显示后端 error |

---

**—— 评估工作台线框 v0.1.1 完 ——**

> 上一页：[08-admin.md](./08-admin.md) 后台管理
> 本批线框（04–09）覆盖 IA v0.3.0 全部剩余页面。下一阶段：按 IA 6 优先级排期实现（团队 → 账号 → 用量 → 后台三页 → 模型配置 → 评估）。
