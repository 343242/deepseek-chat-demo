# 七大设计原则逐条审查

> **文档类型**：设计原则遵循度深度分析（含工程取舍论证）
> **所属审查**：[前端工程化审查 README](./README.md) §3 速查表的展开
> **审查日期**：2026-08-12
> **判定标准**：每条原则给出 ① 遵循度 ② 正向证据 ③ 违背处 ④ **工程取舍论证**（"看似违背但可接受" vs "真问题"）

---

## 阅读约定

- **遵循度** ★ 数越高越好（满分 5）。
- "违背处"统一在 [issues.md](./issues.md) 落 `FE-0xx` ID，本文只给位置与论证，不重复完整建议。
- 前端语境下，七原则解读为：**SRP / OCP / LSP / ISP / DIP**（OO 五原则）+ **DRY / KISS**（两条通用法则）。LoD（迪米特）/ CARP（合成复用）在前端价值有限，本文不单列，相关观察并入 ISP/SRP。

---

## 1. 单一职责原则（SRP）— ★★★☆☆

> 一个模块/函数只有一个变更理由。

### 正向证据（做得好）

- `lib/api-fetch.ts` 聚焦"传输"：Cookie 凭证、`GlobalResponse` 解包、401 refresh 单例重放都集中在一处，是教科书级的跨切面收敛，全工程最稳的模块之一。
- `lib/format.ts`（时间/文件大小/耗时）、`lib/status-meta.ts`（状态 → 徽标元数据）、`lib/constants.ts`（权限码/错误码/上传限制/storage keys）各司其职，**状态元数据表驱动**（`ETL_STATUS_META: Record<EtlStatus, StatusMeta>` 等），新增状态只加一行、不动逻辑。
- `components/common/*`（`EmptyState`/`ConfirmDialog`/`StatusBadge`/`PagePlaceholder`）真正业务无关，可在任意模块复用。

### 违背处

| ID | 位置 | 现象 |
|----|------|------|
| **FE-006** | `stores/chat-store.ts` `send()`（约 90 行） | 上帝函数：乐观 UI 构造 + raw/isolated id 转换 + 临时 id 生成 + 流式编排 + **7 个 SSE 事件各自的 state mutation** + 完成后 `queryClient.invalidateQueries`。≥6 个变更理由，且不可单测。 |
| **FE-013** | `pages/app/chat-page.tsx`（`AgentSummary` 内联） | 页面混了路由参数同步 + 数据加载 + 布局 + 内联子组件 `AgentSummary`（chat-page.tsx:88-110）。 |
| **FE-002** | `components/chat/{message-list,reference-card,conversation-list}.tsx` 尾部 | 三处"伪 re-export"（`export { Button }` / `export { Badge }` / `export { convKeys }`）——用再导出压制 lint 的 unused 报错，掩盖了死引入（`reference-card` 引入 `Badge` 后从未使用）。 |

### 工程取舍论证

- **`send()` 的取舍是"可辩护但已到临界"**：把流式副作用集中在 store action，换来了组件层极薄（`ChatInput` 只调 `send`、`ChatPage` 只读 state），这是"厚 store / 薄 view"的有意取舍，在流式场景下确实降低了 prop drilling。**但在没有测试网兜底下**，一个不可测的 90 行编排函数是技术债固化点——判为**真问题**（P1），需抽 `createStreamReducer` 纯函数。
- **伪 re-export 无任何可辩护理由**——是对 tree-shaking 的误解（`conversation-list.tsx` 注释"保持 convKeys 引用避免 tree-shake 误删"），未使用的 import 根本不会被"误删"。判为**真问题**（P0），直接清理。

---

## 2. 开闭原则（OCP）— ★★★★☆

> 对扩展开放，对修改封闭。

### 正向证据（达到前端最佳实践）

- **状态元数据全表驱动**：`ETL_STATUS_META` / `CONVERSATION_STATUS_META` / `MESSAGE_STATUS_META` 等 7 张 `Record`，新增枚举值 = 加一行，**不修改任何 switch**。这是前端能做到的最干净 OCP。
- **设计 token 经 CSS 变量 + `@theme inline` 暴露**（`app.css`）：换肤、加色阶、调字号 = 改 CSS 变量，**不改组件**。
- **`api/` 每资源一文件 + key factory**（`docKeys`/`convKeys`/`modelKeys`/`teamKeys`/`authKeys`）：新增资源 = 加文件，不改既有层。

### 违背处

| ID | 位置 | 现象 |
|----|------|------|
| — | `lib/sse.ts` `mapFrame`（sse.ts:38-66）/ `dispatch`（sse.ts:84-104） | 7 类帧用 `switch` 处理，新增 event 类型需改两处 switch。 |

### 工程取舍论证

- **SSE 的 switch 是"可接受的 OCP 让步"**：因为 `SseFrame` 是判别联合（discriminated union），TypeScript 会强制 `switch` 穷尽所有 `type`——**漏处理任一帧类型即编译失败**。在小规模（7 类、且后端契约稳定）下，类型安全 + 可读性 > 注册表的开放性。判为**可接受**，不单独立 issue；仅当帧类型开始频繁新增时再重构为 `Record<EventType, Handler>`。

---

## 3. 里氏替换原则（LSP）— ★★★★☆

> 子类型必须能替换其基类型而不破坏程序正确性。

### 正向证据

- `RenderMessage extends MessageVO`（chat-store.ts:11）是**扩展而非收窄**（只新增 `references`/`agentMetadata`/`reasoning`/`pending`），凡是接受 `MessageVO[]` 的地方都能接受 `RenderMessage[]`。
- shadcn 原子组件被 common / feature 层无差别组合，未出现"子组件要求额外 props 才能工作"的违反。
- 全工程**无一处用 `any` 破坏契约**；`as unknown as T` 仅出现在 `api-fetch.ts` 的 `raw` 模式与 `sse.ts` 的 `safeJson` 兜底，边界明确、可辩护。

### 违背处

**无实质违反。** 这是本工程最干净的一条原则。

---

## 4. 接口隔离原则（ISP）— ★★★☆☆

> 调用方不应被迫依赖它不使用的方法。

### 违背处

| ID | 位置 | 现象 |
|----|------|------|
| **FE-012** | `hooks/use-auth.ts` `useAuth()` | 返回胖对象（`user` + `initials` + `username` + `setUser` + `clear` + 全部权限方法）。任何只想要 `initials` 的组件（如 `ChatMessage`、`AppSidebar`）订阅它会**因任意 auth 字段变化而重渲染**。 |
| — | `components/guards/permission-guard.tsx` `PermissionGuardProps` | 同一接口承载"路由级（`redirect`）"和"组件级（`fallback`）"两种语义，调用方需理解模式切换（见 FE-004 的死代码）。 |

### 工程取舍论证

- **`useAuth` 的胖返回有"已知缓解"**：工程里多数地方已直接用细粒度 selector `useAuthStore((s) => s.user)`（如 `RequireAuth`、`AppDataLoader`），说明作者知道重渲染问题。但 `ChatMessage`/`AppSidebar` 仍用了 `useAuth()` 而非 selector——**风格不一致**，不是设计盲点。判为 P2（当前无性能热点，可延后）。
- **`PermissionGuardProps` 双语义**可接受——通过 `redirect` 与 `fallback` 是否提供来区分模式，且文档注释说明了。仅在 FE-004 修复死代码时一并收敛即可。

---

## 5. 依赖倒置原则（DIP）— ★★★★☆

> 高层模块不应依赖低层模块，二者都应依赖抽象。

### 正向证据

- pages / components 依赖 `api/*` hooks 这个**抽象层**（`useDocuments`/`useConversations`/...），而非 `apiFetch` 具体实现。`api-fetch.ts` 本身是稳定的薄抽象，全局依赖方向正确。
- 组件不直接 import store 实现细节，走 `hooks/` 聚合（`useAuth`/`usePermission`/`useTheme`）。

### 违背处

| ID | 位置 | 现象 |
|----|------|------|
| **FE-014** | `components/chat/reference-card.tsx`（调 `fetchChunk`）、`components/chat/message-list.tsx`（调 `fetchMessages`） | 特性组件**绕过 React Query 缓存**直接命令式 fetch。后者游标分页用 imperative 控制 `prepend` 可理解，但前者（按需展开 chunk 全文）完全可走 RQ——同一 chunk 第二次展开仍会发请求。 |
| — | `stores/chat-store.ts` 直接 import 具体的 `streamChat`/`cancelChat`/`queryClient`/`useAuthStore` | 未注入依赖。 |

### 工程取舍论证

- **chat-store 直接 import 具体模块是"业界共识的务实取舍"**：前端 store 注入依赖（DI）仪式感重、收益低，React 生态普遍直接 import。判为**可接受**，不立 issue。
- **绕过 RQ 的两处是"局部倒退"**：游标分页（`message-list`）脱离缓存有合理性（命令式控制 prepend、避免缓存键爆炸）；但 `reference-card` 的 `fetchChunk` 脱离缓存无收益。判为 P2——建议接回 RQ 或在代码显式标注"故意脱离缓存"。

---

## 6. DRY（不重复）— ★★★☆☆

> 每一处知识都应有单一、明确、权威的表示。

### 正向证据

- 核心跨切面**无重复**：元数据表、常量、format 工具、apiFetch 单点、状态映射——每一处都只有一个权威源。

### 违背处

| ID | 位置 | 现象 |
|----|------|------|
| **FE-001a** | `components/chat/message-list.tsx:45-63` 与 `conversation-list.tsx:547-555` | IntersectionObserver 无限滚动逻辑**几乎逐行重复**（`new IntersectionObserver` + `entries[0].isIntersecting && !loading` + `ob.observe` + `disconnect`）。应抽 `hooks/use-infinite-scroll.ts`。 |
| **FE-001b** | `components/chat/chat-message.tsx:338` 与 `markdown-viewer.tsx` `CodeBlock` | 复制到剪贴板逻辑**逐字重复**（`navigator.clipboard.writeText` + `setCopied(true)` + `setTimeout(reset, 1500)`）。应抽 `hooks/use-copy.ts`。 |
| **FE-003** | `api/documents.ts`、`components/knowledge/upload-button.tsx`、`components/knowledge/document-detail-drawer.tsx | `queryClient.invalidateQueries({ queryKey: ['documents'] })` 裸 key 散落 3+ 处，而 key factory 产出的是 `['documents', teamId ?? 'personal']`。 |
| — | `components/chat/chat-input.tsx:154-155` | `MAX_LEN` / `LAST_MODEL_KEY` 内联定义，未纳入 `lib/constants.ts`（后者已有 `STORAGE_KEYS` 命名空间却漏了 `srag.lastModel`）。见 FE-015。 |

### 工程取舍论证

- **裸 `['documents']` 不会出 bug**：React Query 失效按前缀匹配，`['documents']` 会覆盖 `['documents', 'personal']` / `['documents', 1, 'chunks']` 等所有子键。但风格不统一（key factory 存在却被绕过）会让新人误判失效范围。判为 P0 治理项（仅风格统一，无功能风险）。
- 其余 DRY 违背均为**真问题**，直接抽 hook 即可消除。

---

## 7. KISS（保持简单）— ★★★☆☆

> 用最简单可行的方案。

### 正向证据

- 整体代码风格克制、命名一致、注释密度恰当（中文注释 + DS 章节引用），可读性好。
- 路由表（`App.tsx`）扁平直观，守卫组合（`RequireAuth` + `PermissionGuard`）清晰。

### 违背处 / 可疑处

| ID | 位置 | 现象 |
|----|------|------|
| **FE-007** | `lib/md5.ts`（100 行手写 MD5） + `upload-button.tsx:62`（主线程同步 `md5(buf)`） | 注释解释了动机（SubtleCrypto 不支持 MD5）。但**它在主线程同步计算 50MB 文件**，会阻塞 UI 数百毫秒；且位运算 `>>`/`<<` 对大输入有 32 位截断隐患（50MB 内尚可）。 |
| **FE-006** | `stores/chat-store.ts` `send()` | 同 SRP，复杂度本身是 KISS 反例。 |

### 工程取舍论证

- **手写 MD5 的"零依赖"取舍部分成立**：避免引 `spark-md5` 一个依赖。但代价（主线程阻塞）**不值得**——分片上传的 MD5 计算正是 Web Worker 的标准用例。判为 P1：保留实现，但迁入 Worker。
- **`send()` 复杂度**同 SRP 取舍论证，P1。

---

## 附：判定汇总

| 判定 | 数量 | 含义 |
|------|------|------|
| **真问题**（需修） | 13 | 见 [issues.md](./issues.md) FE-001 ~ FE-015 中除"可接受"标注外 |
| **可接受的取舍** | 3 | SSE switch（OCP）、chat-store 直接 import（DIP）、`PermissionGuardProps` 双语义（ISP） |
| **无违反** | 1 | LSP 全工程 |

> 三处"可接受的取舍"不进入 issues.md 行动项，但记录在此，供未来规模变化时重新评估（如 SSE 帧类型频繁新增、或 chat-store 需并发）。
