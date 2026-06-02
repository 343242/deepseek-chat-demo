# PPT + XLSX Parser 实现 — 复盘

**日期**: 2026-05-16
**分支**: rag-dev
**Commits**: f735573 → 58771c0 → c133d6b → 6900005 → de8caca

---

## 做对了什么

1. **先设计后编码** — 设计文档经 spec 审查发现 9 个问题（P0x1 P1x5 P2x3），提前排除了 API 不存在、异常策略不完整等风险
2. **严格代码审查** — 对照 spec 逐项检查实现代码，又发现 6 个问题（P0x2 P1x3 P2x1），全部修复
3. **分阶段迭代** — 设计审查 → 实现 → 代码审查 → P0 修复 → 流式优化，每步有明确产出

## 出了什么问题

1. **子代理首次实现质量不够** — 双次 InputStream、null class 强转、remove(0) 这些基础 API 误用/性能问题，实现时就该避免
2. **Fesod API 不熟悉** — 第一次实现用了 ReadListener + synchronizedList 全量收集，后来才改为 doReadSync 流式分块
3. **Task 状态未及时闭环** — 完成后 task.json 仍是 in_progress

## 以后怎么做

### 第三方库实现前必须先看官方文档
- Fesod 的 headRowNumber(0)、doReadSync() 是文档明确写的 API
- 不能靠猜测 API，不能靠“应该差不多”的心态
- 实现前至少读完 quickstart + 核心 API 文档

### Resource.getInputStream() 可能是网络调用
- MinIO 等 OSS 场景下，每次 getInputStream() 可能触发一次网络下载
- N 个 Sheet = N+1 次文件打开 = N+1 次网络请求，不可接受
- 原则：输入流只打开一次，复用 reader

### 子代理实现需要人工审查把关
- 子代理擅长快速生成大量代码
- 但 API 误用、资源管理、性能问题需要人工对照 spec 逐项检查
- 不能信任子代理的首次实现，必须审查

## 数据

- 设计文档审查：9 个问题，1 轮修复
- 代码审查：6 个问题，2 轮修复
- 总 commit：5 个
- 变更量：约700 行新增 + 约200 行修复
- 时间：约3 小时（设计 1h + 实现 1h + 审查修复 1h）
