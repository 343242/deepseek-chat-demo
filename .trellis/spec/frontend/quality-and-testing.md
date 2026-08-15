# Quality & Testing Guidelines

> 质量门、TypeScript 约定、测试、构建性能、安全不变量与禁止模式。

---

## 质量门（收工前必跑）

四道质量门，在 `frontend/` 下执行：

```bash
bun run typecheck   # tsc -b --noEmit（strict + noUncheckedIndexedAccess + erasableSyntaxOnly）
bun run lint        # eslint src（flat config，type-aware）
bun run test:run    # vitest run
bun run build       # tsc -b && vite build（提交前完整验证）
```

日常开发 `bun run dev`（Vite :5173，`/api` 代理到后端 :10808）。**禁用 npm/yarn/pnpm** 安装依赖（锁文件是 `bun.lock`，镜像源固定在 `bunfig.toml`）。

### ESLint 覆盖面（`eslint.config.js`）

收口 `tsc` 查不出的错误类别（这些错误编译全过、运行时才炸）：

- **typescript-eslint `recommendedTypeChecked`**（type-aware，`projectService`）：floating promise、async 回调误传 void prop、`import type` 强制（`consistent-type-imports`）。修法约定：异步回调传 `onClick`/`onSubmit`/`onLoadMore` 一律 `() => void fn()` 包装；fire-and-forget 用 `void` 运算符显式标注（如 `void queryClient.invalidateQueries(...)`）。
- **`eslint-plugin-react-hooks` v7 `recommended`**：Rules of React + 依赖数组 + React Compiler 派生规则（`refs` / `set-state-in-effect` / `purity`…）。踩过的红线：数据属性**不要命名为 `ref`**（React 19 保留 prop 名，compiler 规则必炸，见 reference-card 的 `reference` 重命名）；RHF 的 `handleSubmit(fn)` 求值放事件期——`(e) => void handleSubmit(fn)(e)`；effect 里同步 setState 改为渲染期推导 / 事件处理器 / `key` 重置。
- **`import-x/no-restricted-paths`**：分层依赖方向强制（zones 与 Directory Structure 分层表对应）。zone 语义：`target`=发起 import 的文件、`from`=被禁目标；`except` 路径**相对 `from` 解析**（如 `./conversations.ts`）。
- **`no-console`**：`error`/`warn` 放行（正当错误上报，如 checksum worker 兜底），其余 warn 不挡门——TEMP-DEBUG 联调日志到期清零。

新增依赖前自问：体积是否进主包（echarts/shiki/katex 已拆包）？是否与现有能力重复（dayjs/zustand/react-query 已覆盖多数场景）？

---

## TypeScript 约定（`tsconfig.app.json`）

- `strict` + `noUnusedLocals` + `noUnusedParameters` + `noFallthroughCasesInSwitch`：不留死代码，编译不过就是不过。
- `verbatimModuleSyntax`：类型导入**必须** `import type { X }`（现存代码统一如此）。
- 路径别名只用 `@/`（`@/lib/api-fetch`）；同目录内允许相对导入，跨目录禁止 `../../`。
- 禁 `any`；与后端 DTO 对不上的字段先回 [Data & State](./data-and-state.md) 的「类型镜像」改 `types/`，不用 `as` 强转绕过。
- 枚举值用字符串字面量联合类型 + `as const` 常量对象（`PERMISSION`、`ERROR_CODE`），不用 TS `enum`。
- 请求 DTO 字段一律 `readonly`（`ChatRequest`、`ChunkUploadInitRequest`…）；响应 DTO 不加（渲染层可变消费，加了无收益）——2026-08-16 类型专项审查决策。
- 信任边界用类型守卫不用断言：响应信封形状过 `api-fetch.ts` 的 `isGlobalResponse`；`catch (unknown)` 一律 `e instanceof ApiError` / `err instanceof Error` 判定（见 `sse.ts` 的 `isAbortError`/`errorMessage`）。
- 变体载荷用判别联合（type 即载荷，如 `SseFrame`、`ChatDetail`）；禁止「type + 全可选字段」的胖联合。
- 禁 `!` 非空断言（唯一例外：`main.tsx` 启动 `getElementById('root')!` 惯例）；优先 const 局部收窄（可穿透闭包）或 `lib/utils.ts` 的 `getOrCreate`。
- 穷尽性契约用 `expectTypeOf` 锁定在 `src/types/__tests__/type-safety.test.ts`（运行时 no-op，`tsc -b` 编译期把关）。
- 配置/映射类对象字面量用 `satisfies` 校验形状并保留字面量推断——不用 `as`（不安全），不用宽类型注解（丢推断）；`as const` 用于确需全 readonly 收窄的场景。
- `noUncheckedIndexedAccess`：下标访问返回 `T | undefined`，越界在编译期显式化——用可选链 / `??` 兜底处理，不拿 `!` 断言绕过。
- `erasableSyntaxOnly`：仅允许可擦除语法，`enum` / 参数属性直接编译报错（与"禁 enum"约定互为兜底）。

---

## 测试（Vitest + Testing Library）

配置要点（`vitest.config.ts`）：

- **no-globals**：测试文件显式 `import { describe, it, expect } from 'vitest'`——不给 strict tsconfig 注入全局类型，新测试照做。
- jsdom 环境；setup 仅 `@testing-library/jest-dom/vitest`。
- 就近共置：`src/**/__tests__/*.test.ts(x)`。

测试优先级（现有覆盖印证）：

1. **`lib/` 纯函数**（主战场）：`stream-reducer`、`sse`（mapFrame/parseEventBlock 各帧型 + 非法 JSON 兜底）、`checksum-core`、`format`、`conversation-id`、`flatten-messages`。复杂逻辑先抽纯函数再测，别对着组件硬测。
2. **行为关键组件**：仅安全/交互契约值得组件测试，如 `document-preview-dialog.test.tsx`（sandbox 属性、iframe 隔离）。用 `@testing-library/react` + `user-event`，断言用户可见行为，不断言内部状态。
3. 页面/store 编排一般不测，靠类型 + 纯函数下沉覆盖。

用例写法对齐 `lib/__tests__/sse.test.ts`：中文 describe/it 描述场景，"非法输入 → 兜底值"类边界必测。

---

## 构建与性能

代码分割三件套（新页面默认继承，别破坏）：

1. **路由级懒加载**：业务页 `lazy()` + `lazyEl()`（`Suspense` + `RouteSkeleton`）；登录路径保持同步（`App.tsx:5-33`）。
2. **manualChunks**：echarts / shiki / katex 单独拆包（`vite.config.ts`）。仅限无初始化顺序耦合的第三方重库，新增大依赖（>200KB）才评估进列表；**业务代码禁止手动分组**（Rollup 手动 chunk 可引入循环初始化/加载顺序问题），业务侧重依赖优先动态 `import()`。
3. **接口分页**：列表走 `useInfiniteQuery`，不一次拉全量。

列表渲染长内容（消息流）注意 key 稳定（消息用 temp-id/服务端 id，见 `lib/chat/temp-id.ts`）。

---

## 安全不变量（review 必查）

| 风险 | 规则 | 出处 |
|------|------|------|
| 开放重定向 | 跳转前过 `safeRedirect`（仅本站路径、拒 `//`） | `pages/auth/login-page.tsx:27-31` |
| 文件预览 XSS | 预览 URL 仅作 `sandbox=""` iframe src；禁 fetch 注入 DOM / `srcdoc` / blob URL | `api/documents.ts:134-146`、`document-preview-dialog.tsx` |
| Markdown XSS | 渲染链保留 `rehype-sanitize` | `components/chat/markdown-viewer.tsx` |
| 凭证泄露 | Token 只存 HttpOnly Cookie；前端不读写 token，不落 localStorage | `lib/api-fetch.ts` |
| 权限绕过 | 受控路由包 `PermissionGuard`；按钮级用 `usePermission().has(code)`；权限码用 `PERMISSION` 常量 | `App.tsx`、`hooks/use-permission.ts` |

---

## 禁止模式（速查）

- ❌ 组件里直接 `fetch` / `apiFetch`（请求收口 `api/`；SSE 除外且只允许在 `lib/sse.ts`）
- ❌ `useEffect` + `useState` 拉取服务端数据（用 Query hooks）
- ❌ 条件/循环里调用 hook、组件外调用 hook（Rules of React，tsc 查不出）
- ❌ 接口数据复制进 Zustand 缓存
- ❌ 整库订阅 store（`useAuthStore()` 无 selector）
- ❌ 裸色值 / `dark:` 硬编码补丁（用语义 token）
- ❌ 模板字符串拼 className（用 `cn()`）
- ❌ 手改 `components/ui/` 做业务定制（组合优于 fork）
- ❌ 魔法值散落（权限码/限制/localStorage key 一律 `lib/constants.ts`）
- ❌ 留 `TEMP-DEBUG` 日志过夜提交（联调结束即删）
- ❌ npm/yarn/pnpm 操作依赖（bun only）

---

## 已知技术债（改到相关文件时顺手清理）

- **DTO 漂移护栏**：方向是 springdoc-openapi 输出 OpenAPI → `openapi-typescript` 生成 `types/`；落地前契约变更 review 必查 `types/` 同步（见 Data & State「类型镜像」）。
- `lib/api-fetch.ts:85-98`、`login-page.tsx:87`、`components/auth/slider-captcha.tsx:65/119` 存在 `TEMP-DEBUG(联调诊断)` console.info（lint 以 no-console warn 持续提示）——联调结束后应删除。
- 错误提示文案多为就地字符串，后续如需 i18n 再收口。
