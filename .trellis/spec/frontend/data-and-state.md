# Data & State Guidelines

> apiFetch 传输契约、类型镜像、TanStack Query、Zustand、SSE 流式。改任何数据获取或状态代码前必读。

---

## 状态分工（先想清楚放哪）

| 状态 | 归属 | 例子 |
|------|------|------|
| 服务端数据的缓存/失效/重试 | TanStack Query | 文档列表、会话列表、模型配置 |
| 会话级客户端状态 | Zustand | 当前会话 id、消息流（历史 + 流式）、侧栏折叠 |
| 持久化偏好 | Zustand + `STORAGE_KEYS`（theme-store 模式） | 主题、上次选中模型 |
| 当前用户（登录态） | `useAuthStore`（唯一归属，不经 Query 中转；写入点见"认证时序不变量"） | user / permissions / initialized |
| 组件内部 UI 态 | `useState` | 弹窗开关、密码可见性 |

**禁止**：把接口返回数据复制进 store"缓存"（Query 已做）；用 `useEffect` + `useState` 手拉数据（用 `useQuery`）。

---

## Effect 使用边界（先问能不能不用）

Effect 只用于**同步 React 之外的系统**（订阅浏览器/第三方 API、操作 DOM、改 document title）。很多想写 `useEffect` 的场景其实不该写：

| 场景 | 错误做法 | 正确做法 |
|------|----------|----------|
| 由 props/state 推导的数据 | 复制进 state，再用 effect 同步 | render 期间直接推导 |
| 用户操作引发的状态变更 | setState 后用 effect 接续处理 | 逻辑写进事件 handler |
| props 变化时重置内部 state | effect 里逐字段 setState | 传 `key` 重建组件，或 render 期比较后重置 |
| 拉取服务端数据 | `useEffect` + `useState` | `useQuery`（见下） |

判定口诀：触发者是**用户事件**就放 handler；是**渲染结果需要对齐外部世界**才进 effect。拿不准先读 react.dev《You Might Not Need an Effect》再动手。

---

## apiFetch 传输契约（`lib/api-fetch.ts`）

所有 HTTP 走 `apiFetch` / `api` 便捷方法，它集中处理四件事，**组件层不要再自行处理**：

1. **Cookie 凭证**：一律 `credentials: 'include'`（Token 在 HttpOnly Cookie，前端 JS 不可读）。
2. **GlobalResponse 双轨制**：HTTP 200 但 `code !== 0` 是业务错误；`code === 0` 自动解包返回 `data`。
3. **401 → refresh 单例锁 → 重放一次**：并发 401 共享同一 refresh Promise；失败派发 `srag:unauthorized` 事件（`AppDataLoader` 监听跳登录）。
4. **错误标准化**：业务/网络错误统一抛 `ApiError`（`types/api.ts`，带 `code` 业务码 + `status` HTTP 码），catch 侧按 `err instanceof ApiError && err.code` 分支。

用法约定：

```ts
import { api, apiFetch } from '@/lib/api-fetch'

api.get<PagedResult<DocumentDTO>>('/documents', { params: { page, size } })
api.post<void>(`/documents/${id}/delete`)
apiFetch<DocumentDTO>('/documents/upload', { method: 'POST', body: fd })   // FormData/Blob 用 body
```

- JSON 请求体传 `json`（自动序列化 + 401 重放可重建），**不要**自己 `JSON.stringify`。
- 裸 JSON 契约端点（无 GlobalResponse 信封，如评估模块）传 `raw: true`，见下节。
- 组件/页面**禁止**直接 `import { apiFetch }` 发请求——请求逻辑收口在 `api/` 领域模块。

### raw 模式（裸 JSON 契约端点，EVAL-2 场景）

**Scope/Trigger**：后端控制器直接返回裸对象（不包 `GlobalResponse.ok`），且分页 0 基、启动类端点返回 202。当前唯一用户是评估模块（`DatasetController` / `EvaluationRunController`），别的端点以后若出现同样契约也走此模式。

**契约**（`apiFetch(path, { raw: true })` 的响应分支，非 raw 路径逐字节不变）：

1. body 是 GlobalResponse 形状 → 沿用 `code` 判定：`code !== 0` 抛 `ApiError`（覆盖 GlobalExceptionHandler 的 **HTTP 200 错误信封**，如"runIds 不能为空"、权限不足）；`code === 0` 返回 `data`（防御，正常不出现）。
2. 裸 JSON + `res.ok`（含 202）→ 原样返回响应体。
3. 裸 JSON + `!res.ok` → 提取 `body.error` 文案抛 `ApiError`（评估控制器自身的 4xx `{"error": "..."}`）。
4. 401 refresh 单例 / credentials / buildUrl 与非 raw 路径完全复用——**禁止**为裸契约另写 fetch 封装（会出现第二个 refresh 锁，违反 IA-6）。

**配套约定**（评估模块 API 层，`api/evaluation.ts`）：
- 分页换算收口在 queryFn：UI 1 基 ↔ 后端 0 基（`page - 1`），组件无感知；`initialPageParam: 0`。
- jsonb 字段三态防御解包用纯函数 `parseMetricField`（`lib/eval-metrics.ts`）：对象 / JSON 字符串 / PGobject `{type:'jsonb',value}` 归一，解析失败返回 null——JdbcTemplate 读 jsonb 无类型处理器时的已知形态。
- 生成指标 `-1` 是"未计算"哨兵：展示层 `formatMetric` → '—'，**不得**当 0 参与展示或均值。

**Wrong vs Correct**：

```ts
// Wrong：裸契约端点用默认模式——apiFetch 对无信封 JSON 抛"响应格式异常"
api.get<DatasetListResponse>('/evaluation/datasets', { params })

// Correct
apiFetch<DatasetListResponse>('/evaluation/datasets', { method: 'GET', raw: true, params })
```

### 二进制与导航端点例外

`/preview`、`/download` 这类端点**不走 apiFetch**（iframe/`<a>` 导航无法带自定义头，鉴权靠浏览器同源自动携带 Cookie），返回纯 URL 让浏览器导航：

```ts
// api/documents.ts
export function documentPreviewUrl(id: number): string { return `/api/documents/${id}/preview` }
```

预览 URL 只能用作 sandbox iframe 的 `src` 或浏览器导航，**禁止** fetch 后注入 DOM / `srcdoc` / blob URL（会使服务端 CSP 隔离失效）。参考 `api/documents.ts:134-156`、`components/knowledge/document-preview-dialog.tsx`。

---

## 类型镜像（`types/`）

- `types/api.ts`：`GlobalResponse<T>`、`PagedResult<T>`（page 从 1 开始）、`CursorPage<T>`、`ApiError`——后端 `GlobalResponse.java` / `PagedResult.java` 的镜像，注释标注来源。
- 领域 DTO（`DocumentDTO`、`ChunkDTO`…）按领域放 `types/document.ts` 等，字段名/枚举值与后端 record 逐一对齐；后端改契约必须同步改这里。注意 `tsc` 只兜**前端内部**一致性（镜像类型 vs 使用点），**兜不住前后端漂移**——两端独立编译，字段错位照样过编译；契约变更是 review 必查项。OpenAPI 代码生成（springdoc → openapi-typescript）是已登记的改进方向，落地前手工镜像就是唯一事实来源，禁止半生成半手写混用。
- 枚举值用后端字符串字面量联合类型（`EtlStatus`），不发明前端自己的枚举。
- `verbatimModuleSyntax` 开启：类型导入必须 `import type { DocumentDTO } from '@/types/document'`。

---

## TanStack Query

### queryKeys 工厂

每个领域模块导出一个层级 key 工厂（`api/documents.ts:13-20`）：

```ts
export const docKeys = {
  all: ['documents'] as const,                                  // 失效时前缀匹配用
  list: (teamId?: number | null) => ['documents', teamId ?? 'personal'] as const,
  detail: (id: number) => ['documents', id] as const,
  chunks: (id: number) => ['documents', id, 'chunks'] as const,
}
```

- key 数组从粗到细；mutation 成功后 `qc.invalidateQueries({ queryKey: docKeys.all })` 一键整域失效。
- 列表的筛选参数并入 key（`[...docKeys.list(teamId), params]`），参数变 key 变，缓存自动隔离。
- 禁止散落的字符串数组 key（`['documents']` 出现在组件里即违规）。

### Hooks 模式

- 读：`useQuery` / `useInfiniteQuery`；条件查询用 `enabled: !!id` 而不是条件渲染 hook。
- 写：`useMutation` + `onSuccess` 失效；组件里用 `mutateAsync` 需要拿结果时才用，错误统一在调用侧 catch `ApiError`。
- 无需 React 上下文的单次请求导出裸函数（`fetchChunk`），供事件回调使用。
- 分页列表统一 `useInfiniteQuery`：`initialPageParam: 1`，`getNextPageParam` 由 `PagedResult.totalPages` 推导（`api/documents.ts:30-40`）。
- 请求取消：`queryFn: ({ signal }) => fetchXxx(params, signal)`，把 Query 注入的 `AbortSignal` 透传到 api 层——组件卸载/key 变化自动中断，不手写 AbortController。
- 加载态命名（v5）：初次加载判 `isPending`；`isLoading`（= `isPending && isFetching`）只在需要"无数据且在取"语义时用。分支渲染优先 `status`/`error` 判别联合，别堆 `data &&` 链。
- 路由级强依赖数据可用 `useSuspenseQuery` 与懒加载页的 `Suspense` 对齐（可选项，默认仍 `useQuery` 三态自渲染）。

### 全局默认（`lib/query-client.ts`，不要随手覆盖）

`staleTime 30s`、`gcTime 5min`、`refetchOnWindowFocus: false`、mutations 不重试、retry 按 `ApiError.code` 分段（4xx/业务确定性错误不重试）。改这里的策略是全局决策，需说明理由；个别 query 覆盖要有注释。

---

## Zustand

模式（`stores/auth-store.ts`、`stores/chat-store.ts`）：

```ts
export const useAuthStore = create<AuthState>((set, get) => ({ ...state, ...actions }))
```

- 扁平 state + 同级 action 函数，**不用** middleware（无 persist/redux devtools 依赖）。
- 组件订阅必须用 selector：`useAuthStore((s) => s.user)`，禁止整库订阅 `useAuthStore()`（FE-008：无谓重渲染）。
- 非响应式读取用 `useAuthStore.getState()`（事件回调 / store 互访，`chat-store.ts:71`）。
- 持久化偏好：在 store 里手动读写 `STORAGE_KEYS`，参考 `theme-store.ts`；禁止散落的 `localStorage.xxx`。读取必须 `try/catch` + 形状校验、坏数据回退默认值——localStorage 是不可信输入，坏 JSON/旧结构不应炸白屏。
- 存储 JSON 结构要变更时：换新的 `STORAGE_KEYS` 键，或在读取处显式迁移旧结构；禁止原地改结构留脏数据。
- 副作用资源（AbortController 等模块级单例）放 `create` 闭包内（`chat-store.ts:47`）。

### 流式编排模式（FE-006，重要）

store 的 `send` 只做**编排**：构造乐观消息 → 调 `lib/sse.ts` 的 `streamChat` → 每帧委托**纯函数** `applyFrame(messages, frame, assistantId)` 产出新 state。帧归约逻辑放 `lib/chat/stream-reducer.ts`（可单测），store 里不写帧解析/归约细节。新的复杂状态机照此拆分。

---

## SSE 流式（`lib/sse.ts`）

### POST 聊天流（DS §11.3）

硬约束：

- **必须** `fetch` + `ReadableStream` 手动解析——POST 带 `@RequestBody`，`EventSource` 不支持。
- 帧按空行（`\n\n`）切分，字段 `event:` / `data:`；**内容帧没有 event 名**（默认事件即内容）。
- 帧映射用纯函数 `mapFrame(event, data): SseFrame | null`：未知 event 名返回 null 忽略（前向兼容）；JSON 字段用 `safeJson` + 类型兜底（非法 JSON → 空数组/空对象），**不让坏帧炸掉整条流**。
- 解析器不依赖"按 event 名分发"的高层封装，底层读行。
- 取消 = 软取消 `POST /chat/stream/cancel`（幂等，`.catch(() => …)` 静默）+ `AbortController.abort()` 兜底断流，两者叠加（`chat-store.ts:155-165`）。

### GET 事件流（DS §11.17，评估运行进度）

GET 端点走**原生 EventSource**（`subscribeEvalRunEvents`，`lib/sse.ts`）——与聊天流是两条路径，别混用：

- 自动重连是**特性**（DS §11.17 允许）；迟到订阅靠后端 replay 最近 20 条恢复。
- `addEventListener('error')` 同时收到两类事件：后端 `event:error` 帧（MessageEvent，有 `data`）与连接层错误（无 `data`）——按 `typeof me.data === 'string'` 区分；前者是业务终态需 `close()`，后者只通知组件切轮询兜底。
- 终态（done/error 帧）后必须主动 `close()`，返回退订函数；"后台运行"只退订不中断后端执行（SSE 是观察窗）。
- 断开告警只上报一次/次断开（重连成功收到任意帧复位），避免重连期重复 toast。

配套 Vite 代理已为 SSE 调优（`timeout: 0`、关闭缓冲，`vite.config.ts`），动代理配置前先确认流式不受影响。

---

## 认证时序不变量（改 auth 相关代码前必读）

- 会话用户（user/permissions/initialized）只存 `useAuthStore`，**不经 Query 缓存中转**（没有 `/me` 的 query）。写入只发生在三个事件边界：
  1. **启动装载**（`AppDataLoader`）：非 auth 路由拉一次 `fetchMe()` → `setUser`；失败也置 `initialized`，守卫按 `user=null` 分流登录页。
  2. **登录/注册成功**（login-page / register-page）：`mutateAsync → fetchMe() → setUser(me) → navigate`——同步写 store 后立刻导航，`RequireAuth` 必见 user，零闪烁。
  3. **登出/401**：`clear()`（`useLogout` / `srag:unauthorized` 处理器）。
- 聊天消息同理只存 `chat-store`（历史装载 `loadConversation` + 流式 `send`，代序号防切换竞态），不要为 `/conversations/{id}` 建 Query 再用 effect 复制进 store。
- 根路由分流等 `initialized`（`App.tsx` `LandingRedirect`），避免已登录用户闪现登录页。
- `apiFetch` 的 401 refresh / `srag:unauthorized` 事件链路是唯一登出通道，不要在组件里重复实现"401 跳登录"。

---

## React 19 用法边界

栈是 React 19，但不是所有 19 的能力都解锁，以下边界防止新旧范式并存：

- **React Compiler 未启用**（2025-10 发布 1.0，接入是独立决策）：因此**不默认手写 `useMemo` / `useCallback` / `React.memo`**——只对实测热点添加并在注释注明测量依据；无证据的 memo 是噪音，compiler 接入后还要人工拆除。compiler 派生的 lint 规则（refs/set-state-in-effect/purity）已通过 eslint-plugin-react-hooks v7 生效，见 [Quality & Testing](./quality-and-testing.md)。
- **`ref` 直接作 prop**：React 19 起新组件不再写 `forwardRef`（已进入弃用轨道）；存量组件不主动改写，触碰到相关代码时顺手迁移。
- **表单/提交单一范式**：固定 react-hook-form + `useMutation`（见 UI & Styling），不引入 `useActionState` / form actions 生态另起一套。
- **`use(Context)`**：条件分支里读 context 用它，不再写嵌套/条件 `Consumer`。
- **`useOptimistic`**：聊天乐观更新已有既定编排（store + stream-reducer，FE-006），不得另起炉灶；其他场景先评估 Query `onMutate` 乐观更新是否够用。
