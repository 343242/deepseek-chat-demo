# 前端审查问题清单

> **文档类型**：可追踪问题清单（Issue Tracker）
> **所属审查**：[前端工程化审查 README](./README.md)
> **审查日期**：2026-08-12（第一轮·工程化）｜2026-08-16（第二轮·TypeScript 类型系统专项，见文末）
> **条目数**：21（`FE-001` ~ `FE-021`）
> **配套**：原则判定与取舍论证见 [principles.md](./principles.md)

---

## 约定

- **ID**：`FE-0xx`，稳定不变，便于在 commit / PR / 看板中引用。
- **严重度**：🔴 P0（一周内可清、低风险高收益）｜🟡 P1（需设计、收益高）｜🟢 P2（架构演进、当前非阻塞）。
- **状态**：⚪ 待处理（默认）｜🔵 进行中 ｜✅ 已修复 ｜❌ 已拒绝（附理由）。
- **证据**：`path:行号` 均为本次审查时（提交 `08d6db8`）的实际位置，修复时以当时代码为准。
- 每条含：现象 / 证据 / 影响 / 建议修复方案。

---

## 按严重度索引

| ID | 标题 | 严重度 | 涉及原则 | 状态 |
|----|------|--------|----------|------|
| [FE-001](#fe-001) | 抽公共 hook：无限滚动 + 复制逻辑重复 | 🔴 P0 | DRY | ✅ |
| [FE-002](#fe-002) | 清理三处"伪 re-export"与死引入 | 🔴 P0 | SRP | ✅ |
| [FE-003](#fe-003) | 统一 `['documents']` 裸 key → `docKeys` | 🔴 P0 | DRY | ✅ |
| [FE-004](#fe-004) | `PermissionGuard` feature 门控为死代码 | 🔴 P0 | ISP / 正确性 | ✅ |
| [FE-005](#fe-005) | 路由零代码分割 + 无错误边界 | 🔴 P0 | 可扩展性 | ✅ |
| [FE-006](#fe-006) | 拆 `chat-store.send()` 上帝函数 | 🟡 P1 | SRP / KISS / 可测 | ✅ |
| [FE-007](#fe-007) | 校验和 MD5→SHA-256（hash-wasm + Web Worker，中性名契约） | 🟡 P1 | KISS / 性能 / DIP | ✅ |
| [FE-008](#fe-008) | `LandingRedirect`/`AuthRedirect` 非响应式读 store | 🟡 P1 | 正确性 | ✅ |
| [FE-009](#fe-009) | 零测试基建 | 🟡 P1 | 可测性 | ✅ |
| [FE-010](#fe-010) | `useMe` 在 queryFn 内写 store（副作用耦合） | 🟡 P1 | SRP / DIP | ✅ |
| [FE-011](#fe-011) | chat-store 全局单例不支持并发会话 | 🟢 P2 | 可扩展性 | ⚪ |
| [FE-012](#fe-012) | `useAuth` 胖 hook 触发多余重渲染 | 🟢 P2 | ISP | ⚪ |
| [FE-013](#fe-013) | `chat-page` 内联 `AgentSummary` 应抽离 | 🟢 P2 | SRP | ✅ |
| [FE-014](#fe-014) | 特性组件绕过 React Query 缓存直接 fetch | 🟢 P2 | DIP | ✅ |
| [FE-015](#fe-015) | `chat-input` 常量未纳入 `lib/constants.ts` | 🟢 P2 | DRY | ✅ |
| [FE-016](#fe-016) | catch 边界 `as` 断言应改类型守卫（登录页有行为缺陷） | 🟡 P1 | 类型安全 / 正确性 | ✅ |
| [FE-017](#fe-017) | `chat-store.detail` 胖联合 → 真·判别联合 | 🟡 P1 | 类型收窄 | ✅ |
| [FE-018](#fe-018) | 边界 JSON 无形状校验，`safeJson` 命名与语义不符 | 🟡 P1 | 类型安全 / 契约 | ✅ |
| [FE-019](#fe-019) | 非空断言 `!` 改 const 局部收窄 | 🟢 P2 | 类型安全 | ✅ |
| [FE-020](#fe-020) | 三处冗余/脆弱类型构造 | 🟢 P2 | KISS | ✅ |
| [FE-021](#fe-021) | 类型测试缺位 + `readonly` 策略未明示 | 🟢 P2 | 可测性 | ✅ |

> **本轮修复（2026-08-12）**：FE-001~010、FE-013~015 共 13 条已修复（✅），FE-007 由「Worker 复用 MD5」升级为「**直接迁移 SHA-256、删除 MD5、不两者同存**」（前端先行，后端跟进）。FE-011/FE-012 触发条件未满足，按 YAGNI 暂缓。详见各条「修复说明」与提交记录。

---

## 🔴 P0

### FE-001

**抽公共 hook：无限滚动 + 复制逻辑重复**

- **涉及原则**：DRY
- **现象**：两处重复模式各写了两遍，且复制逻辑还会继续出现在未来组件中。
- **证据**：
  - 无限滚动：`components/chat/message-list.tsx:47` 与 `components/chat/conversation-list.tsx:72`，`new IntersectionObserver` + `entries[0].isIntersecting && !loading` + `observe` + `disconnect` 几乎逐行重复。
  - 复制：`components/chat/chat-message.tsx:338` 与 `components/chat/markdown-viewer.tsx` `CodeBlock`，`navigator.clipboard.writeText` + `setCopied(true)` + `setTimeout(reset, 1500)` 逐字重复。
- **影响**：行为漂移风险（两处无限滚动的清理、防抖策略一旦分叉，bug 难定位）；复制逻辑是跨组件通用能力。
- **建议**：
  - 新建 `hooks/use-infinite-scroll.ts`：签名 `useInfiniteScroll({ onLoadMore, hasMore, loading })` 返回 `sentinelRef`。
  - 新建 `hooks/use-copy.ts`：签名 `useCopy(timeoutMs?)` 返回 `{ copied, copy }`。
  - 两处组件替换为 hook 调用。
- **预估**：约 1 小时，零行为风险。

---

### FE-002

**清理三处"伪 re-export"与死引入**

- **涉及原则**：SRP / 整洁度
- **现象**：用"再导出"压制 lint 的 unused 报错，掩盖了真实死代码，且反映对 tree-shaking 的误解。
- **证据**：
  - `components/chat/message-list.tsx:135` — `export { Button }`（引入 `Button` 后再导出，无消费方）。
  - `components/chat/reference-card.tsx:86` — `export { Badge }`（引入 `Badge` 后**组件内从未使用**，纯死引入）。
  - `components/chat/conversation-list.tsx:218` — 注释 `// 保持 convKeys 引用避免 tree-shake 误删` 后 `export { convKeys }`。未使用的 import 不会被"误删"，注释本身是误解。
- **影响**：降低信噪比；新人会误以为这些导出有消费方；掩盖真实的未使用 import。
- **建议**：
  - `message-list.tsx`：若 `Button` 确实未在组件内用，删除 import + 删除 re-export；若用了（如"回到最新"按钮），仅删 re-export。
  - `reference-card.tsx`：删除未使用的 `Badge` import 及其 re-export。
  - `conversation-list.tsx`：删除 `convKeys` import 与 re-export 及误导性注释。
- **预估**：15 分钟。

---

### FE-003

**统一 `['documents']` 裸 key → `docKeys`**

- **涉及原则**：DRY
- **现象**：key factory `docKeys` 已存在，却有多处直接写裸字符串 `['documents']`，风格分裂。
- **证据**：裸 key 散落 4 处——
  - `components/knowledge/upload-button.tsx:105`
  - `components/knowledge/document-detail-drawer.tsx:148`
  - `api/documents.ts:76`（`useDeleteDocument`）
  - `api/documents.ts:84`（`useRetryDocument`）
  - 而 `api/documents.ts:14` 定义了 `docKeys.list = (teamId) => ['documents', teamId ?? 'personal']`。
- **影响**：**无功能 bug**（React Query 失效按前缀匹配，`['documents']` 覆盖所有子键），但风格不统一会让新人误判失效范围，且 `docKeys` 形同虚设。
- **建议**：
  - 列表场景失效改用 `queryClient.invalidateQueries({ queryKey: ['documents'] })` 的语义等价写法，但**统一**走 `docKeys` 提供的辅助：在 `docKeys` 增加 `all: ['documents'] as const` 根键，失效处统一写 `{ queryKey: docKeys.all }`。
  - 或保留裸 `['documents']` 但在 `docKeys` 旁注释"失效用根 `['documents']` 做前缀匹配"，二选一，**不要混用**。
- **预估**：20 分钟。

---

### FE-004

**`PermissionGuard` feature 门控为死代码**

- **涉及原则**：ISP / 正确性（语义已坏）
- **现象**：`feature` 入参看似实现，实际恒真，未来加真 feature flag 时会被误判为"已实现"。
- **证据**：`components/guards/permission-guard.tsx:27` ——
  ```ts
  const okFeature = !feature || feature !== 'evaluation' || true
  ```
  `|| true` 使表达式恒真；`okFeature` 随后只在 `if (okPerm && okFeature)` 中使用，永不为 false。
- **影响**：当前无功能危害（所有 feature 都放行），但**语义已坏**——这是典型的"占位代码忘删"，是 bug 温床。
- **建议**（二选一）：
  - **方案 A（推荐）**：`feature` 能力尚未就绪，删除 `feature` 入参与 `okFeature`，简化为纯权限判定；待真 feature flag 系统就绪再加回。
  - **方案 B**：保留入参，改为真读取（如 `useFeatureFlag(feature)`），并接入 IA-1 提到的 evaluation 门控数据源。
- **预估**：方案 A 10 分钟。

---

### FE-005

**路由零代码分割 + 无错误边界**

- **涉及原则**：可扩展性（首屏性能 + 健壮性）
- **现象**：
  1. `App.tsx` 顶部**全量静态 import** 所有页面（login/register/chat/knowledge/teams/admin 各页），首屏加载整个应用；`vite.config.ts` 的 `manualChunks` 只拆了 vendor（echarts/shiki/katex），业务页未拆。
  2. 全工程**无 `ErrorBoundary`**，路由表也无 `errorElement`；任意 render 异常或流式渲染崩溃 → 整个 App 白屏（`pages/error/*` 存在却无人导航过去）。
- **证据**：`App.tsx:7-22`（import 块）、`main.tsx`（无 ErrorBoundary 包裹）、全仓 `grep ErrorBoundary` 无结果。
- **影响**：
  - 体积随模块数线性增长，首屏 LCP 劣化。
  - 单点崩溃无兜底，用户体验灾难。
- **建议**：
  - **分包**：`App.tsx` 内页面改 `React.lazy(() => import(...))`，在 `<Routes>` 外包 `<Suspense fallback={<RouteSkeleton/>}>`。admin 页与 chat 页（重）优先拆。
  - **错误边界**：新建 `components/guards/root-error-boundary.tsx`（class 组件），在 `main.tsx` 包裹 `<App>`；路由级可在重页面（chat）再加一个，使崩溃局部化。捕获后导航到 `/500` 或就地恢复 UI。
- **预估**：半天。

---

## 🟡 P1

### FE-006

**拆 `chat-store.send()` 上帝函数**

- **涉及原则**：SRP / KISS / 可测性
- **现象**：`send()` 单方法承担 ≥6 个职责（乐观 UI 构造 + raw/isolated id 转换 + 临时 id 生成 + 流式编排 + 7 个 SSE 事件各自的 state mutation + 完成后失效 query），约 100 行、不可单测。
- **证据**：`stores/chat-store.ts:73-173`（`send` 函数体）；`streamChat` 调用在 `:106`，7 个 handler 内联在 `:107-172`，`queryClient.invalidateQueries` 在 `:169`。
- **影响**：
  - 无法单测（store action 内嵌 fetch + 闭包 `abortController`）。
  - 扩展新 SSE 帧类型需改动这个巨型函数。
  - 重构高风险 → 技术债固化。
- **建议**：
  - 抽出**纯函数** `lib/chat/stream-reducer.ts`：`(prevMessages, frame) => nextMessages`，处理 7 类 `SseFrame` → 新消息数组。纯函数可单测。
  - `send()` 只做编排：构造乐观消息 → 调 `streamChat` → 每个 handler 调 `set((s) => ({ messages: streamReducer(s.messages, frame, assistantId) }))`。
  - 临时 id 生成（`tempIdSeq` 模块级变量）一并抽到工具函数。
- **预估**：1-2 天（含测试）。

---

### FE-007

**校验和：MD5 → SHA-256（hash-wasm + Web Worker，根因迁移）** ✅

- **涉及原则**：KISS / 性能 / DIP
- **原现象**：手写 `md5.ts`（100 行）在 `upload-button` 主线程**同步**计算 50MB 文件 MD5，阻塞 UI 数百毫秒；分片上传每个 chunk 还各算一次。
- **修复说明（2026-08-12，升级为根因修复）**：不做「MD5 进 Worker」的补丁，而是**直接迁移到 SHA-256 并彻底删除 MD5（不两者同存）**。后端将同步弃用 MD5，前端先行。
  - 新增依赖 `hash-wasm`，删除 `lib/md5.ts`。
  - `lib/checksum-core.ts`：纯逻辑 `sha256Hex(Blob|ArrayBuffer|Uint8Array)`，hash-wasm 增量 API + `Blob.stream()` 分块（50MB 不整块入内存），导出 `CHECKSUM_ALGORITHM='SHA-256'`。
  - `lib/checksum.worker.ts` + `lib/checksum.ts`：单例 Worker、promise 化 `computeChecksum(blob)`，主线程不阻塞。
  - **契约改名（中性名，前端先行 → 后端跟进）**：`fileMd5 → fileChecksum`、`X-Chunk-MD5 → X-Chunk-Checksum`、`uploadChunk` 形参 `md5 → checksum`（`types/document.ts`、`api/documents.ts`、`upload-button.tsx`）。
  - **回归等价校验**：`checksum-core.test.ts` 以 NIST/公开 SHA-256 标准向量断言（空串/abc/fox/多块），并验证 Uint8Array/ArrayBuffer/Blob 三路输入等价。
  - 构建验证：`checksum.worker-*.js`（~19KB，WASM 内联）独立分包，`knowledge-page` 引用之，无需 `worker.format:'es'`。
- **过渡期注意**：后端未跟进前，分片/秒传的校验和校验会失败——属已知「前端先行」预期。

---

### FE-008

**`LandingRedirect`/`AuthRedirect` 非响应式读 store**

- **涉及原则**：正确性（隐藏 bug 源）
- **现象**：在 render 内用 `useAuthStore.getState().user`（一次性快照），组件**不会随登录态变化重渲染**，仅靠后续导航副作用侥幸工作。
- **证据**：`App.tsx:33`（`LandingRedirect`）、`App.tsx:41`（`AuthRedirect`）—— 均为 `const user = useAuthStore.getState().user`。
- **影响**：当前因登录/登出都伴随 `navigate` 而"碰巧正确"；但任何"store 变化但未触发本组件重渲染"的场景（如 `AppDataLoader` 异步写 user 时已在此组件挂载后）都会导致分流 stale，是难定位的 bug。
- **建议**：改为响应式订阅 ——
  ```ts
  const user = useAuthStore((s) => s.user)
  ```
  与 `RequireAuth`/`AppDataLoader` 的读取方式保持一致。
- **预估**：10 分钟。

---

### FE-009

**零测试基建**

- **涉及原则**：可测性（**最大短板**）
- **现象**：`package.json` devDeps 无任何测试框架（vitest/jest/playwright），全仓无 `*.test.*` / `*.spec.*` 文件，`scripts` 无 `test`。
- **证据**：`package.json`（devDependencies 仅含 typescript/vite/tailwind 等）；`find . -name '*.test.*'` 无结果。
- **影响**：`chat-store.send()`、`sse.mapFrame`、`api-fetch` 401 重放等核心逻辑**无任何回归保护**；FE-006 等重构"不敢动"。技术债会随代码量指数级固化。
- **建议**：
  - 引入 `vitest`（与 vite 同构、零配置、最快落地）+ `@testing-library/react`。
  - 首批单测目标（纯逻辑、高价值）：
    1. `lib/sse.ts` `mapFrame` / `parseEventBlock` / `safeJson`（7 类帧 + 边界）。
    2. `lib/conversation-id.ts` `toRawConversationId` / `buildIsolatedId`（含兜底正则）。
    3. FE-006 拆出的 `stream-reducer` 纯函数。
    4. `lib/format.ts` `time`/`formatFileSize`/`formatDuration`。
  - 后续补 `api-fetch` 的 401 refresh 单例（mock fetch）。
  - `package.json` 加 `"test": "vitest"`、`"test:run": "vitest run"`。
- **预估**：基建半天 + 首批用例 1-2 天。

---

### FE-010

**`useMe` 在 queryFn 内写 store（副作用耦合）**

- **涉及原则**：SRP / DIP / 可测性
- **现象**：`useMe` 的 `queryFn` 在取数后直接 `setUser(data)`，把"网络请求"与"store 写入"耦合在一个函数里；每次 refetch / window focus（虽已关）/ staleTime 过期重取都会重复写。
- **证据**：`api/auth.ts:15-18` ——
  ```ts
  queryFn: async () => {
    const data = await api.get<UserInfo>('/auth/me')
    setUser(data)   // :17 副作用
    return data
  },
  ```
  另 `useLogin:39` 也有 `setUser({...data.user, ...})`，多处写同一 store。
- **影响**：queryFn 不再是纯取数（测试需 mock store）；双写风险（login 写一次、紧接着 me 又写一次）；缓存与 store 状态来源不清。
- **建议**：
  - queryFn 保持**纯取数**（只 `return data`）。
  - 用 `useEffect` 订阅 `me.data`：`useEffect(() => { if (me.data) setUser(me.data) }, [me.data])`，放在 `AppDataLoader` 内（已是 /me 的消费方）。
  - `useLogin` 的 onSuccess 改为只 `qc.invalidateQueries({ queryKey: authKeys.me })`，由订阅链路统一写 store。
- **预估**：半天（注意登录态过渡期不闪烁）。

---

## 🟢 P2（架构演进，当前非阻塞）

### FE-011

**chat-store 全局单例不支持并发会话**

- **现象**：`useChatStore` 是全局单例，`messages`/`conversationId`/`abortController` 闭包变量（`chat-store.ts:67` 模块级 `let abortController`）只支持一个活跃会话。
- **影响**：当前单聊天视图无碍；**封死了**未来"多标签 / 多会话并行"扩展。
- **建议**：按 `conversationId` keyed（`Map<id, ChatSlice>` 或用 `zustand` 的 family store 模式）。**触发条件**：产品出现多会话并发需求时再做，当前不必。
- **取舍**：单例是当前规模的正确取舍，P2 记录备查。

---

### FE-012

**`useAuth` 胖 hook 触发多余重渲染**

- **现象**：`useAuth()`（`hooks/use-auth.ts`）返回胖对象（user + initials + username + setUser + clear + 权限方法）。`ChatMessage`、`AppSidebar` 只想要 `initials`/`username` 却订阅了全部，任一 auth 字段变化都重渲染。
- **证据**：`hooks/use-auth.ts`（`useAuth` 返回聚合）；消费方 `components/chat/chat-message.tsx:332` `const { initials } = useAuth()`、`components/shell/app-sidebar.tsx` `useAuth()`。
- **影响**：当前无性能热点；数据量大或频繁更新时可能成为重渲染源。
- **注**：工程多数处已用细粒度 selector `useAuthStore((s) => s.user)`，作者知道该问题——此处是**风格不一致**，非设计盲点。
- **建议**：消费方按需改用 selector；或保留 `useAuth` 但内部用细粒度 selector 聚合（不影响外部 API）。
- **触发条件**：Profiler 发现 auth 相关重渲染热点时。

---

### FE-013

**`chat-page` 内联 `AgentSummary` 应抽离**

- **现象**：`pages/app/chat-page.tsx` 内联了 `AgentSummary` 组件（`:88-110`），页面文件混了路由同步 + 数据加载 + 布局 + 子组件定义。
- **影响**：SRP 轻微违反；`AgentSummary` 无法被他处复用；页面文件膨胀。
- **建议**：抽到 `components/chat/agent-summary.tsx`，类型从 `@/types/chat` 的 `AgentMetadata` 直接引入（而非 `NonNullable<ReturnType<typeof useChatStore.getState>...>` 这种脆弱类型）。
- **触发条件**：chat-page 持续膨胀或 AgentSummary 需复用时。

---

### FE-014

**特性组件绕过 React Query 缓存直接 fetch**

- **现象**：两处特性组件用命令式 `api.get` 绕过 RQ 的缓存/去重/失效体系。
- **证据**：
  - `components/chat/reference-card.tsx:23` —— `const chunk = await fetchChunk(ref.chunkId)`（同一 chunk 二次展开仍发请求）。
  - `components/chat/message-list.tsx:52` —— `const page = await fetchMessages(...)`（游标分页，命令式 `prepend`）。
- **影响**：缓存不一致；失去 RQ 的去重/loading/error 管理。
- **取舍**：`message-list` 的游标分页脱离缓存**有合理性**（命令式控制 prepend、避免缓存键爆炸）；`reference-card` 的 `fetchChunk` 脱离缓存**无收益**。
- **建议**：
  - `reference-card`：改用 `useQuery({ queryKey: ['chunks', ref.chunkId], queryFn: () => fetchChunk(ref.chunkId), enabled: expanded })`，享受缓存。
  - `message-list`：若保留命令式，在代码注释显式标注"故意脱离 RQ 缓存以控制 prepend 顺序"；或评估 `useInfiniteQuery` 替代。
- **触发条件**：出现缓存不一致 bug，或治理期统一数据访问。

---

### FE-015

**`chat-input` 常量未纳入 `lib/constants.ts`**

- **现象**：`MAX_LEN = 10000`、`LAST_MODEL_KEY = 'srag.lastModel'` 内联在组件，而 `lib/constants.ts` 已有 `STORAGE_KEYS` 命名空间（却漏了这个 key）。
- **证据**：`components/chat/chat-input.tsx:16-17`。
- **影响**：轻微 DRY/收口问题；`'srag.lastModel'` 字符串散落，重命名时易漏。
- **建议**：`MAX_LEN` 入 `constants.ts`（或 `UPLOAD_LIMITS` 同层的输入限制）；`LAST_MODEL_KEY` 并入 `STORAGE_KEYS`（如 `lastModel: 'srag.lastModel'`）。
- **触发条件**：constants 收口治理期。

---

## 第二轮：TypeScript 类型系统专项审查（2026-08-16）

> **依据**：TypeScript 类型规范（泛型 / 条件类型 / 映射类型 / 模板字面量类型 / 工具类型十条最佳实践、七类陷阱、类型测试与性能注意）。
> **基线验证**（提交 `bec3f62`）：`tsc -b --noEmit` 零错误；ESLint `recommendedTypeChecked` 零 error（仅 5 条 TEMP-DEBUG `no-console` warning）；vitest 79/79 通过；**全仓零显式 `any`、零 `@ts-ignore`、`as const` ×21**。证据行号均为本轮审查时位置。
>
> **本轮修复（2026-08-16）**：FE-016~021 共 6 条已修复（✅）。修复后验证：typecheck 零错误、lint 零 error、测试 82/82 通过（新增 3 条编译期类型护栏测试）。五条类型护栏约定（请求 DTO readonly、边界守卫、判别联合、禁非空断言、expectTypeOf 穷尽锁定）已记入 `.trellis/spec/frontend/quality-and-testing.md`「TypeScript 约定」。

### 正向记录（先说结论：类型纪律高于平均水平）

- **判别联合是全仓最强项**：`SseFrame` 七变体（`types/chat.ts:125`）在 `mapFrame`/`dispatch`/`applyFrame` 三处穷尽 switch，新增帧类型漏处理即编译失败；`EtlStatus` 11 值联合 × `Record<EtlStatus, StatusMeta>`（`lib/status-meta.ts:41`）用 Record 键穷尽性当编译期护栏。
- **`interface`（对象形状）/ `type`（联合）分工一致**，`types/*.ts` 无一例外；复杂导出均有 JSDoc。
- **类型守卫范本**：`markdown-viewer.tsx` `extractText` 用 `typeof`/`Array.isArray`/`in` 三连收窄 `unknown`，是「断言 → 守卫」的正确写法。
- **`unknown` 用于边界**：`api-fetch` 的 `json?: unknown`、`checksum.worker` 消息、`ChunkDTO.metadata: Record<string, unknown>`。
- **工具类型用而不过**：`Omit`（api 方法签名收窄可选面）、`Record`、`ReturnType<typeof setTimeout>`；无自建条件/映射/模板字面量类型——当前规模下「无滥用」即是正确答案，不立 issue。
- **边界情况的防御**：`flattenMessages` 显式处理 null/undefined/空数组；`conversation-id` 有兜底正则；`safeJson` 解析失败返回 null。
- 附件「性能注意」全项通过：无深嵌条件类型、无递归类型、无循环类型引用（`RenderMessage` 迁至 types 层避免 store 循环依赖，注释可证）。

### FE-016

**catch 边界 `as` 断言应改类型守卫（登录页有行为缺陷）** ✅

- **涉及原则**：类型安全（「避免断言、用类型守卫」）/ 正确性
- **修复说明（2026-08-16）**：登录页改 `e instanceof ApiError` 三分支判定，网络层异常统一「网络异常，请稍后重试」；`register-page.tsx` 同款 `(e as ApiError)` 一并修复；`sse.ts` 抽 `isAbortError`/`errorMessage` 守卫替换 4 处 `(err as Error)`。`ApiError` 相应改为值导入（instanceof 需要构造函数）。
- **现象**：`strict` 下 catch 变量为 `unknown`，代码用断言代替守卫。
- **证据**：
  - `pages/auth/login-page.tsx:103` — `const err = e as ApiError`。网络故障时抛的是 `TypeError`：`err.code` 为 undefined（不等于 `RATE_LIMIT`，走 else 分支），`err.message` 为英文 `"Failed to fetch"` 直接进弹窗文案。
  - `lib/sse.ts:135/139/146/150` — `(err as Error)` ×4。行为有 `|| '网络异常'` 兜底，属风格问题。
- **影响**：登录页在网络异常时向用户展示英文技术文案；非 Error 抛出物（字符串等）路径上类型系统全程失明。
- **建议**：登录页改 `const err = e instanceof ApiError ? e : null`，非 `ApiError` 走统一「网络异常，请稍后重试」；`sse.ts` 抽 `isAbortError(err)` 守卫（`err instanceof Error && err.name === 'AbortError'`）。
- **预估**：30 分钟。

### FE-017

**`chat-store.detail` 胖联合 → 真·判别联合** ✅

- **涉及原则**：判别联合收窄（附件陷阱 #4 的标准案例）
- **修复说明（2026-08-16）**：`ChatDetail` 判别联合定义在 `types/chat.ts`（供 store 与类型测试复用）；`chat-page.tsx` 收窄后删除 `detail.refs &&`/`detail.meta ?` 双守卫；顺带删除不可达的「Agent 推理中…」spinner 分支——`openAgent` 恒传对象、`{}` 是合法 `AgentMetadata`，该分支永不渲染。
- **现象**：`detail: { type: 'refs' | 'agent'; refs?: Reference[]; meta?: AgentMetadata } | null`——两个变体的载荷字段全部可选，type 与 payload 的对应关系只靠运行时约定。
- **证据**：`stores/chat-store.ts:32`（定义）；消费方 `pages/app/chat-page.tsx:68-79` 被迫写 `detail.refs && detail.refs.length > 0`、`detail.meta ?` 双重守卫。
- **影响**：消费端永远拿不到「type === 'refs' 时 refs 必有值」的编译期保证；未来新增详情类型时退化成 if-else 泥潭。
- **建议**：
  ```ts
  type ChatDetail =
    | { type: 'refs'; refs: Reference[] }
    | { type: 'agent'; meta: AgentMetadata }
  ```
  `openRefs`/`openAgent` 签名不变，`chat-page.tsx` 收窄后删掉两处手动守卫。
- **预估**：30 分钟。

### FE-018

**边界 JSON 无形状校验，`safeJson` 命名与语义不符** ✅

- **涉及原则**：类型安全（信任边界）/ 契约健壮性
- **修复说明（2026-08-16）**：`api-fetch` 新增 `isGlobalResponse` 运行时守卫（code/message/data 形状校验，`doRefresh` 同步接入）；删除零调用方的 `raw` 选项及其 `as unknown as T`；references 帧补 `Array.isArray` 形状校验；`safeJson` 更名 `parseJson`（不再暗示已校验形状）。剩余两个信任点已注释标明：text 路径（响应体无 JSON 形状可验）与 `envelope.data as T`（泛型内容信任）。
- **现象**：两条信任边界都靠 unchecked 断言穿过：
  - `lib/api-fetch.ts:117` — `(await res.json()) as GlobalResponse<T>`；另 `:114`（text 路径）与 `:120`（raw 模式）两处 `as unknown as T` 双重断言。
  - `lib/sse.ts:49-56` — `safeJson<T>` 只防 `JSON.parse` 抛错，不防形状错误；`mapFrame` 的 `safeJson<Reference[]>(data) ?? []`（`:27`）对「后端返回对象而非数组」无防护（当前恰好被消费方的 `.length > 0` 判断兜住，非设计使然）。
- **影响**：后端契约漂移时错误被推迟到渲染层且带着错误类型继续传播；「safe」前缀给读者错误的安全感。
- **建议**：最小改动——`isGlobalResponse(x): x is GlobalResponse<unknown>` 守卫 + references 帧 `Array.isArray` 检查；或复用已有依赖 **zod**（表单层 `login/register` 已在用）校验 `GlobalResponse` 与 SSE 终端帧。`as unknown as T` 建议拆 overload 或独立 `apiFetchRaw` 消除。
- **预估**：守卫版 1-2 小时；zod 版半天。

### FE-019

**非空断言 `!` 改 const 局部收窄** ✅

- **涉及原则**：类型安全
- **修复说明（2026-08-16）**：`chat-message` 用 const 局部收窄（`refs`/`agentMeta`，可穿透闭包）替代两处 `!`；`lib/utils` 新增 `getOrCreate<K, V>`（全仓首个带约束泛型工具），`model-selector`/`conversation-list` 两处 `map.get(k)!.push` 替换。`main.tsx` 启动惯例保留。
- **证据**：
  - `components/chat/chat-message.tsx:99` `message.references!`、`:116` `message.agentMetadata!`——守卫是 `const isAgent = !!message.agentMetadata`（`:28`），布尔变量对类型收窄不可见。
  - `components/chat/model-selector.tsx:29`、`components/chat/conversation-list.tsx:62` — `map.get(k)!.push(...)`。
- **影响**：`!` 让检查器闭眼；`isAgent` 一旦改为按 mode 判定（AGENT 模式未必有 metadata），`:116` 会静默传 `undefined` 给 `onOpenAgent`。
- **建议**：`const refs = message.references` 后在 JSX 用 `{refs && ... onClick={() => onOpenRefs?.(refs)}}`（const 局部收窄可穿透闭包）；Map 场景抽 `getOrCreate(map, key)` 小工具。`main.tsx:17` 的 `getElementById('root')!` 属启动惯用，可保留。
- **预估**：30 分钟。

### FE-020

**三处冗余/脆弱类型构造** ✅

- **涉及原则**：KISS / 可读性
- **修复说明（2026-08-16）**：冗余联合坍缩为 `readonly string[]`；扩展名校验改 `Set<string>.has`（零断言、O(1)）；register 页 `Field` 的双层 ReturnType 改为 `UseFormRegisterReturn`（实参是 `register('x')` 调用结果而非函数本身——审查建议中的 `UseFormRegister` 经编译验证不匹配，已修正）。
- **证据**：
  - `hooks/use-permission.ts:11` — `readonly string[] | string[]`：`string[]` 是 `readonly string[]` 的子类型，联合坍缩为 `readonly string[]`，写法暴露对变方向的不确定。
  - `components/knowledge/upload-button.tsx:61` — `as readonly string[]` 是 `as const` 元组与 `.includes(ext: string)` 参数变差的补丁；改 `Set<string>` 成员判定（`ALLOWED_EXTENSIONS.has(ext)`）更干净且零断言。
  - `pages/auth/register-page.tsx:150` — `ReturnType<ReturnType<typeof useForm<FormValues>>['register']>` 双层 ReturnType；react-hook-form 直接导出 `UseFormRegister<FormValues>`。
- **影响**：均为「能跑但难读」；双层 ReturnType 这类脆弱类型在后端 FE-013 修复时已被点名过同款。
- **预估**：30 分钟。

### FE-021

**类型测试缺位 + `readonly` 策略未明示** ✅

- **涉及原则**：可测性（附件「Test your types」条目）
- **修复说明（2026-08-16）**：新增 `src/types/__tests__/type-safety.test.ts`——`expectTypeOf` 锁定三契约（`ETL_STATUS_META` 穷尽绑定、`mapFrame` 返回契约、`ChatDetail` 判别联合），运行时 no-op、由 `tsc -b` 编译期把关（+3 测试）；`ChatRequest`/`ChunkUploadInitRequest` 字段加 `readonly`；五条类型护栏约定记入 `.trellis/spec/frontend/quality-and-testing.md`「TypeScript 约定」。
- **现象**：无 `expectTypeOf`/`assertType`；类型层仅 `ApiError.code/status`（`types/api.ts:33-34`）与一个参数用了 `readonly`，请求 DTO（`ChatRequest`、`ChunkUploadInitRequest`）全部可变，未见显式决策记录。
- **影响**：本轮确认的两大穷尽性护栏（`SseFrame` switch、`Record<EtlStatus, StatusMeta>`）是「靠使用点碰巧被检查」而非「被测试锁定」；readonly 缺席允许 `req.field = x` 这类意外变异。
- **建议**：补 2-3 条编译期断言（如 `ETL_STATUS_META` 键集 === `EtlStatus`、`mapFrame` 返回覆盖全部帧类型）；readonly 做一次显式决策——请求 DTO 加 `readonly` 或在 `.trellis/spec/frontend` 记「有意不加」及理由。
- **预估**：1 小时。

---

## 附录：按原则交叉索引

| 原则 | 相关 issue |
|------|-----------|
| SRP | FE-002, FE-006, FE-010, FE-013 |
| OCP | （SSE switch 判为可接受，未立 issue） |
| LSP | （无违反） |
| ISP | FE-004, FE-012 |
| DIP | FE-010, FE-014 |
| DRY | FE-001, FE-003, FE-015 |
| KISS | FE-006, FE-007 |
| 可扩展性 | FE-005, FE-011 |
| 可测性 | FE-006, FE-009, FE-010, FE-021 |
| 正确性 | FE-004, FE-008, FE-016 |
| 类型安全（第二轮） | FE-016, FE-017, FE-018, FE-019, FE-021 |
