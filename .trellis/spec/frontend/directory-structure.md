# Directory Structure

> `frontend/src/` 分层、命名与依赖方向。

---

## 目录总览

```
frontend/
├── bun.lock / bunfig.toml        # 包管理器固定为 bun（npmmirror 镜像）
├── vite.config.ts                # @ alias、/api 代理（SSE 免超时）、manualChunks
├── vitest.config.ts              # 独立于 vite.config（jsdom 不混入构建链路）
├── tsconfig.app.json             # strict + verbatimModuleSyntax + @/* paths
├── components.json               # shadcn/ui 配置（new-york / slate / cssVariables）
└── src/
    ├── main.tsx                  # 入口：Provider 栈（Query → Tooltip → Router → ErrorBoundary → AppDataLoader）
    ├── App.tsx                   # 路由表（懒加载 + PermissionGuard）
    ├── app.css                   # Tailwind v4 @theme 设计 token（唯一来源）
    ├── api/                      # 按领域的 TanStack Query 层（auth/conversations/documents/models/teams）
    ├── components/
    │   ├── ui/                   # shadcn/ui 原语（button/dialog/select…，生成物）
    │   ├── auth|chat|knowledge/  # 特性组件（按业务域分组）
    │   ├── common/               # 跨特性复用（confirm-dialog/empty-state/status-badge…）
    │   ├── guards/               # 路由守卫（require-auth/permission-guard/root-error-boundary/app-data-loader）
    │   └── shell/                # 布局骨架（sidebar/top-bar/user-menu/theme-toggle）
    ├── hooks/                    # 跨特性 React hooks（use-auth/use-permission/use-theme/use-copy…）
    ├── lib/                      # 基础设施 + 纯逻辑（api-fetch/sse/query-client/constants/format…）
    │   └── chat/                 # 流式纯函数（stream-reducer/flatten-messages/temp-id）
    ├── pages/                    # 路由级页面，按 shell 分组（app/admin/auth/error）
    ├── stores/                   # Zustand 客户端状态（auth/chat/theme/ui）
    ├── test/                     # Vitest setup（仅 jest-dom）
    └── types/                    # 后端 DTO 镜像（api/auth/chat/conversation/document/team）
```

---

## 分层职责与依赖方向

依赖只能自上而下，`lib/` 是最底层：

```
pages → components → hooks / api → stores / lib → types
```

> 依赖方向由 ESLint `import-x/no-restricted-paths` 强制（zones 见 `frontend/eslint.config.js`，与本表一一对应；唯一 except：chat-store → `api/conversations`）。新增例外必须先在本文件登记，再到 eslint 配置加 `except`。

| 层 | 职责 | 禁止 |
|----|------|------|
| `pages/` | 路由入口，组合特性组件 | 写业务逻辑/直接发请求 |
| `components/` | 可复用 UI；特性组件按域分组 | 跨域互相 import（`chat/` 不 import `knowledge/`） |
| `api/` | queryKeys 工厂 + use hooks + 裸请求函数 | 存 UI 状态 |
| `hooks/` | 跨特性有状态逻辑 | 只服务单一特性（那种放特性目录内） |
| `lib/` | 纯函数 + 基础设施封装 | import `api/`、`stores/`、`components/` |
| `stores/` | Zustand 客户端状态 | 缓存服务端数据（Query 的职责） |
| `types/` | 后端 DTO 镜像，零逻辑 | 放运行时值（`constants.ts` 才放） |

已知例外（编排需要，允许存在但不要扩散）：

- `stores/chat-store.ts` import `api/conversations` 的 `convKeys` 做流结束后失效列表——store 编排层允许引用 api 层 key。
- `lib/sse.ts` 直接用 `fetch`——POST body 的 SSE 流无法走 `apiFetch`，见 [Data & State](./data-and-state.md) 的「SSE 流式聊天」。

---

## 命名规则

- **文件**：kebab-case（`document-preview-dialog.tsx`、`stream-reducer.ts`）。
- **页面**：`*-page.tsx`，`export default`（`App.tsx` 用 `lazy(() => import(...))` 按默认导出挂载）。
- **特性组件/hooks/api hooks/stores**：命名导出（`export function DocumentPreviewDialog`、`export function useDocuments`、`export const useAuthStore`）。
- **queryKeys 工厂**：`xxxKeys`（`docKeys`、`convKeys`、`authKeys`），见 [Data & State](./data-and-state.md#querykeys-工厂)。
- **stores**：`xxx-store.ts`，hook 名 `useXxxStore`。
- **类型文件**：按领域命名（`types/document.ts`），导出 `XxxDTO` / 领域类型；跨领域契约（`GlobalResponse`/`PagedResult`/`ApiError`）在 `types/api.ts`。
- **测试**：与源码就近共置 `__tests__/` 目录（`src/lib/chat/__tests__/stream-reducer.test.ts`）。

---

## 路由与代码分割

`App.tsx` 是唯一路由表，模式固定：

- 登录关键路径（`/auth/*`）**同步 import**——体积小、避免登录页二次闪屏。
- 业务页全部 `lazy()` + 统一 `lazyEl()` 包装（`Suspense` fallback 为 `RouteSkeleton`），echarts/shiki/katex 等重依赖只随业务页加载。
- 需要权限的路由用 `<PermissionGuard require="perm:code">` 包裹（`App.tsx:96-114`），权限码用 `PERMISSION` 常量，禁字符串字面量。

新增页面 = 新建 `pages/<shell>/xxx-page.tsx` + `App.tsx` 注册懒加载路由，不改 shell。

---

## 常量收口

所有魔法值集中 `lib/constants.ts`，`as const` 对象命名空间：

- `PERMISSION` / `ROLE` —— 权限码与角色（对齐后端种子，IA §2.2）
- `ERROR_CODE` —— 错误码分段（DS §4.4.12）
- `UPLOAD_LIMITS` / `CHAT_LIMITS` —— 前端限制（大小/分片阈值/并发数/长度）
- `STORAGE_KEYS` —— localStorage key（`srag.*` 前缀）

禁止在组件里散落 `50 * 1024 * 1024`、`'srag.theme'`、`'user:manage'` 这类字面量。

---

## 注释与文档引用约定

本项目注释用中文，且要求**指向决策出处**，方便回溯：

- 设计系统条款：`DS §15.4`、信息架构 `IA §6`、wireframe 条款
- 工程评审问题编号：`FE-006`、`FE-008`、`FE-010`（`docs/frontend/review/`）
- 临时调试日志必须带 `TEMP-DEBUG` 前缀注释并注明移除条件（现存示例 `lib/api-fetch.ts:85`），联调结束删除

新代码写明"为什么"，不写"这行做什么"。
