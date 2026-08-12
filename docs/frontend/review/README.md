# Smart RAG 前端工程化审查

> **文档类型**：前端架构与设计原则审查（Engineering Review）
> **本轮范围**：`frontend/` 全量源码（types/api/stores/hooks/lib/components/pages/config，约 80 文件）的分层架构、可扩展性、七大设计原则遵循度，以及可追踪问题清单
> **前置阅读**：[INFORMATION-ARCHITECTURE.md](../INFORMATION-ARCHITECTURE.md)、[DESIGN-SYSTEM.md](../DESIGN-SYSTEM.md)
> **审查日期**：2026-08-12
> **审查视角**：高级前端工程师
> **代码基线**：分支 `agentic-rag-dev`，最近提交 `08d6db8`（前端脚手架 + 三核心页面）

---

## 0. 如何阅读本文档

本目录是**审查发现的事实档案**，不是行动项看板。三份文档分工：

| 文档 | 内容 | 何时读 |
|------|------|--------|
| **[README.md](./README.md)**（本文） | 总评、成熟度雷达、原则速查、改进路线图 | 想快速了解结论 |
| **[principles.md](./principles.md)** | 七大原则逐条深度审查 + 工程取舍论证 | 想理解"为什么算违背 / 为什么可接受" |
| **[issues.md](./issues.md)** | 全部问题清单（`FE-0xx` ID、严重度、位置、证据、建议） | 动手修复前查清单 |

**本审查的立场**：诚实记录，不粉饰、不夸大。每条发现都给出可复现的代码位置与判定依据；对"看似违背但有合理工程取舍"的条目，明确标注取舍而非直接判错。

---

## 1. 总评

这是一个**分层清晰、约定优于配置的中型 SPA 骨架**，工程基线明显高于同期项目：

- ✅ 跨切面收敛到位——传输（`api-fetch`）、流式（`sse`）、设计 token（`app.css`）、状态元数据（`status-meta`）各有一个单一权威源。
- ✅ 对**水平扩张**（新增一个业务模块）非常友好——加模块 = 加 `types/api/components/pages` 各一个文件 + 一行路由，不污染既有层。
- ⚠️ 尚未"完美遵循"七大原则：在状态编排层（`chat-store`）和若干 DRY / 分层边界上有明确违背，多数是可辩护的工程取舍。
- 🔴 **三处会在功能扩张时率先劣化**：路由零代码分割、无错误边界、零测试基建。这才是扩张前必须补齐的"安全网"，而非原则本身。

**一句话**：分层与设计系统达到生产级水准；原则违背大多是"厚 store 薄 view""零依赖""小规模用 switch 换类型安全"等可辩护取舍；真正风险不在原则，在缺少测试/分包/错误边界这三根安全网——没有它们，现有的良好分层会在重构时变成包袱。

---

## 2. 成熟度雷达

5 分制，基于本次全量审查：

| 维度 | 评分 | 关键依据 |
|------|------|----------|
| 分层与职责划分 | ★★★★☆ | types/api/stores/hooks/components/pages 边界明确；chat-store 编排层扣分 |
| 设计系统抽象 | ★★★★☆ | token → `@theme inline` → shadcn → common → feature 四层到位 |
| 类型安全 | ★★★★☆ | `strict` + 判别联合 + `as const`；仅边界处 `as unknown` 桥接 |
| 状态管理 | ★★★☆☆ | Zustand 选型准；`chat-store.send()` 是编排黑洞、不可测 |
| 跨切面收敛 | ★★★★★ | transport / SSE / 401 重放 / token / 权限单点收敛 |
| 可测试性 | ★★☆☆☆ | **零测试基建**，最大短板，devDeps 无 vitest/jest |
| 可扩展性 | ★★★☆☆ | 水平扩张好；路由分包 / 错误边界 / 并发场景已埋隐患 |

---

## 3. 七大原则速查

> 逐条深度审查与取舍论证见 [principles.md](./principles.md)。

| 原则 | 遵循度 | 一句话结论 | 主要违背 |
|------|--------|------------|----------|
| **SRP** 单一职责 | ★★★☆☆ | 跨切面收敛优秀；`chat-store.send()` 是上帝函数 | `chat-store.send()`、三处伪 re-export |
| **OCP** 开闭 | ★★★★☆ | 状态元数据表驱动 + token 化达到最佳实践 | SSE `mapFrame`/`dispatch` 用 switch（有类型安全背书，可接受） |
| **LSP** 里氏替换 | ★★★★☆ | 全工程最干净的一条，无实质违反 | — |
| **ISP** 接口隔离 | ★★★☆☆ | `useAuth` 胖 hook 返回过多；多数处已用细粒度 selector 缓解 | `useAuth()` 胖返回、`PermissionGuardProps` 双语义 |
| **DIP** 依赖倒置 | ★★★★☆ | 组件依赖 `api/` 抽象而非 `apiFetch` | `reference-card`/`message-list` 绕过 RQ 直接 fetch |
| **DRY** 不重复 | ★★★☆☆ | 核心跨切面无重复；局部重复明显 | 无限滚动逻辑 ×2、复制逻辑 ×2、裸 `['documents']` key 散落 |
| **KISS** 保持简单 | ★★★☆☆ | 整体克制可读；`md5.ts` 主线程同步是真实代价 | 手写 MD5 + 主线程计算、`chat-store.send()` 复杂度 |

---

## 4. 问题严重度统计

> 完整清单见 [issues.md](./issues.md)，共 **15 条**，每条带稳定 ID（`FE-0xx`）便于追踪。

| 严重度 | 数量 | 含义 | 代表问题 |
|--------|------|------|----------|
| 🔴 P0 | 5 | 一周内可清、收益高、低风险 | 伪 re-export 清理、裸 key 统一、死代码修复、路由懒加载 + 错误边界、抽公共 hook |
| 🟡 P1 | 5 | 一到两周、需设计、收益高 | 拆 `chat-store.send()`、MD5 进 Web Worker、引入测试、`useMe` 解耦、响应式守卫 |
| 🟢 P2 | 5 | 架构演进、当前非阻塞 | chat-store 按 id keyed、`useAuth` 拆细、`AgentSummary` 抽离、RQ 缓存补丁、constants 收口 |

---

## 5. 改进路线图

### P0（一周内，低风险高收益）

| ID | 动作 | 文件 |
|----|------|------|
| FE-002 | 删除三处"伪 re-export"（`Button`/`Badge`/`convKeys`）与未使用 import | `components/chat/{message-list,reference-card,conversation-list}.tsx` |
| FE-003 | 统一 `['documents']` → `docKeys`，删除裸 key 字符串 | `api/documents.ts`、`components/knowledge/{upload-button,document-detail-drawer}.tsx` |
| FE-004 | 修复 `PermissionGuard` feature 门控死代码（实现或删入参） | `components/guards/permission-guard.tsx` |
| FE-005 | 路由级 `React.lazy` + `<Suspense>` + 根级 `ErrorBoundary` | `App.tsx`、`main.tsx` |
| FE-001 | 抽 `hooks/use-infinite-scroll.ts`、`hooks/use-copy.ts` 消除重复 | 新建 hook 文件 |

### P1（一到两周，需设计）

| ID | 动作 | 价值 |
|----|------|------|
| FE-006 | 拆 `chat-store.send()`：抽出纯函数 `createStreamReducer` 处理 7 类帧 | 可测、可扩展新帧类型 |
| FE-007 | MD5 计算迁 Web Worker | 消除 50MB 文件主线程阻塞 |
| FE-009 | 引入 vitest，对 `sse.mapFrame` / `parseEventBlock` 与拆出的 reducer 写首批单测 | 重构安全网 |
| FE-010 | `useMe` 的 store 同步改为 `useEffect` 订阅 `query.data` | fetch 与 store 解耦、可测 |
| FE-008 | `LandingRedirect`/`AuthRedirect` 改响应式 `useAuthStore((s)=>s.user)` | 消除隐藏 bug 源 |

### P2（架构演进，当前非阻塞）

| ID | 动作 | 触发条件 |
|----|------|----------|
| FE-011 | chat-store 从全局单例改为按 `conversationId` keyed | 需多会话/多标签并发 |
| FE-012 | `useAuth` 拆细或改为按需 selector | 出现性能热点 |
| FE-013 | 抽离 `chat-page.tsx` 内联 `AgentSummary` 到 `components/chat/` | chat-page 持续膨胀 |
| FE-014 | `reference-card`/`message-list` 的命令式 fetch 接回 RQ（或显式标注脱离缓存） | 缓存一致性出问题 |
| FE-015 | `chat-input` 的 `MAX_LEN`/`LAST_MODEL_KEY` 纳入 `lib/constants.ts` | constants 收口治理 |

---

## 6. 本轮未覆盖

本次审查聚焦工程化与原则，以下**不在范围**，需独立审查：

- **视觉/交互还原度**：与 [DESIGN-SYSTEM.md](../DESIGN-SYSTEM.md) / [wireframes](../wireframes/) 的像素级一致性。
- **无障碍（a11y）**：键盘导航、ARIA、焦点管理的系统性审计。
- **性能实测**：首屏 LCP、流式渲染帧率、大数据量表格——需 Lighthouse / Profiler 实测，非静态审查可定论。
- **后端契约对齐**：仅核对前端类型注释引用的后端 DTO，未反向校验后端实现。
- **安全**：仅注意到 `rehype-sanitize` 白名单已配置（正确）；XSS/CSRF/CSP 深审不在本次范围。
