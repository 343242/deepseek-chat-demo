# 评估模块前端工作台

## Goal

按 `docs/frontend/wireframes/09-evaluation.md`（v0.1.1）落地 `/admin/evaluation` 页面：数据集管理（LLM 生成 + 条目审核）→ 评测运行（configOverride + SSE 实时进度）→ 结果查看与多 Run 对比，打通 RAG 质量闭环的前端侧。

后端（evaluation 模块 Java 侧，08-18 两个 ragas 任务）已全部就绪并验证（97 测试绿）。

## Requirements

1. **布局**：AdminShell 内左右分栏——左栏数据集列表（选中态 + URL query `?dataset=&tab=&run=` 持久化）+ 右侧工作区三 Tab（数据集详情 / 评测运行 / 结果对比）
2. **数据集管理**：生成数据集 Modal（name 选填 + userId 必填）→ 202 → 轮询任务进度 → 完成刷新列表；条目审核编辑（部分更新语义，null 保留旧值）；导出 JSON 下载；不画手工创建/删除/重命名入口（EVAL-4）
3. **评测运行**：启动 Modal（topK 1–50 + 重排/MMR/父子分块/查询改写/生成答案 5 开关 + testUserId + 并发上限帮助文本 EVAL-6）→ 202 → SSE 实时进度面板（DS §11.17：进度条 + 成功/失败计数 + 最近 ≤50 条日志 + 后台运行不断评测 + done 后自动跳结果）
4. **结果视图**：Run 级 summary 头 + 逐条结果表（检索 5 指标 + 生成 4 指标，-1 哨兵显示"—"）；不臆造"查看答案全文"入口（EVAL-5）
5. **多 Run 对比**：≥2、≤10 个 Run 多选 → 检索侧/生成侧分组对比表，首列基线，其余列 ▲▼ 差值着色
6. **契约适配**（EVAL-2）：评估接口为裸 JSON（无 GlobalResponse）+ 分页 0 基 + 202 —— 传输层需独立适配，不能复用现有 code 判定路径
7. **状态全集**：403 权限占位、加载骨架、空状态文案（线框 §5）、SSE 断流"进度流中断 + 刷新"兜底

## Non-goals

- 后端任何改动（端点、信封统一、run 删除/停止等留待 EVAL-3/4/5 后续）
- feature flag 显隐（IA-1：仍按权限码显示入口）
- echarts 图表（线框未定义，对比为纯表格）

## Acceptance Criteria

- [x] `/admin/evaluation` 全流程可用：生成数据集 → 审核条目 → 启动评测 → SSE 进度 → 结果表 → 多 Run 对比
- [x] URL query 持久化选中数据集/Tab/Run，刷新可恢复
- [x] apiFetch raw 模式落地且现有调用方行为不变（全量前端测试回归绿）
- [x] 生成指标 -1 哨兵显示"—"，不参与 0 值展示
- [x] SSE done/error/断流三态均有收尾与兜底；"后台运行"不中断评测
- [x] jsonb 指标字段三态（对象/字符串/PGobject）防御解包有单测
- [x] `bun run typecheck && lint && test:run && build` 四门全绿
- [x] GitNexus detect_changes 影响面仅 frontend（+api-fetch raw 分支）
