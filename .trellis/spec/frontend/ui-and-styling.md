# UI & Styling Guidelines

> Tailwind v4 设计 token、shadcn/ui、组件编写、表单。写任何组件前必读。

---

## 设计 token（`src/app.css`，唯一来源）

Tailwind v4 CSS-first：`@import "tailwindcss"` → `@theme inline` 把 CSS 变量暴露为工具类 → `:root` / `.dark` 定义原始 token。**写业务组件时只用语义 token，不碰原始变量**：

| 类别 | 可用 token（示例） | 用途 |
|------|-------------------|------|
| 语义背景 | `bg-canvas` `bg-surface` `bg-base` `bg-field` `bg-hover` `bg-selected` | 页面底 / 卡片 / 次级面 / 输入框 / 悬停 / 选中 |
| 语义文本 | `text-fg` `text-muted` `text-subtle` `text-faint` `text-inv` `text-link` | 主/次/三级/禁用/反色/链接 |
| 语义边框 | `border-line` `border-line-strong` `border-line-subtle` `border-accent` | 默认/强调/弱化/点缀 |
| 色阶 | `primary-50…900` `success-*` `warning-*` `error-*` `info-*` | 品牌与状态色（如 `text-error-600`） |
| 中性阶 | `neutral-0…900` | 原始中性色（少用，优先语义 token） |

**禁止**：

- 裸 Tailwind 调色板（`bg-gray-500`、`text-slate-400`）与任意 hex/rgb 值——暗色模式必炸。
- 需要新颜色时在 `app.css` 的 `@theme inline` 注册 token（对齐 DESIGN-SYSTEM §15），不就地写值。

暗色模式：`.dark` 类手动切换（`@custom-variant dark`），由 `theme-store` + `useTheme` 控制。组件必须用语义 token 表达明暗差异，**禁写 `dark:` 前缀硬编码补丁**（token 已自动切换）。

图标尺寸用 `size-4` / `size-3.5` 工具类；字体 Inter Variable / JetBrains Mono 由 `@fontsource` 全局注入，不自引字体。

---

## shadcn/ui（`components/ui/`）

- 配置：new-york 风格、slate 基色、cssVariables（`components.json`）；图标库 lucide。
- `ui/` 目录是**生成物**：优先 `bunx shadcn@latest add <component>` 引入新原语，而不是手写。
- 手改 `ui/` 仅限：桥接项目 token（`app.css` 末段 shadcn 语义桥已做大部分）、修 bug——改完在文件头注明原因。业务定制（如特殊 Dialog 头部布局）写在**特性组件**里通过组合实现，不fork原语。
- 条件类名一律 `cn()`（`lib/utils.ts`：`clsx + tailwind-merge`），禁止模板字符串拼 class（不处理冲突）。
- 复合变体用 `class-variance-authority`（`ui/button.tsx` 的 `cva` 模式）。

---

## 特性组件编写模式

参考标杆：`components/knowledge/document-preview-dialog.tsx`、`components/chat/reference-card.tsx`。

```tsx
/** 原文件预览弹窗（KB-2，design §4.3 安全契约） …为什么这样设计 */
export function DocumentPreviewDialog({ doc, open, onOpenChange }: {
  doc: DocumentDTO | null
  open: boolean
  onOpenChange: (o: boolean) => void
}) {
```

- **命名导出** + 就地 interface/内联 props 类型；文件头注释说明用途与决策出处（DS/IA/FE-xxx，KB-xxx）。
- 数据来自 `api/` hooks，事件向上回调（`onOpenChange`），组件不做数据获取编排。
- 受控弹窗模式：`open` / `onOpenChange` props 对齐 Radix Dialog；`doc === null` 时 return null 而非条件渲染外层。
- 加载/空/错误三态用 `components/common/` 现件：`RouteSkeleton`、`EmptyState`、`ConfirmDialog`、`StatusBadge`——先找复用再新建。
- 弹窗内加载态用绝对定位覆盖层（`document-preview-dialog.tsx:52-56` 的 Loader2 模式），不阻塞布局。
- 中文 UI 文案；无障碍属性（`aria-label`、`title`）不可省。

---

## 表单（react-hook-form + zod）

模式固定（`pages/auth/login-page.tsx:20-47`）：

```ts
const schema = z.object({
  username: z.string().min(1, '用户名不能为空'),
  password: z.string().min(1, '密码不能为空'),
})
type FormValues = z.input<typeof schema>   // 表单输入视角；schema 带 transform 时结果类型用 z.output

const { register, handleSubmit, formState: { errors, isValid } } = useForm<FormValues>({
  resolver: zodResolver(schema), mode: 'onChange', defaultValues: { … },
})
```

- 校验规则（长度/格式/限制值）写在 **zod schema**，与 `UPLOAD_LIMITS` / `CHAT_LIMITS` 常量联动，不散落 `if`。
- 表单类型一律从 schema 推导（`z.input` / `z.output`），禁止手写与 schema 平行的 interface——两份定义必漂移。
- 字段错误就近渲染 `<p className="text-sm text-error-600">`；提交级错误（如后端 ApiError）在表单顶部或弹窗内渲染。
- 提交按钮 `disabled={!isValid}`；提交中态必须反馈（禁用/spinner）。
- 输入控件用 `ui/input` + `ui/label`，`htmlFor`/`id` 配对。

---

## 其他 UI 约定

- **Toast**：sonner。`Toaster` 已挂 `main.tsx`；组件里 `import { toast } from 'sonner'` 直接用，成功/失败语义区分变体。
- **日期**：dayjs；展示格式化收口 `lib/format.ts`（`formatFileSize` 等同层），禁止组件内手写格式化逻辑。
- **Markdown 渲染**：`components/chat/markdown-viewer.tsx` 是唯一入口。插件链 `react-markdown + remark-gfm + rehype-raw + rehype-sanitize + rehype-katex + rehype-pretty-code(shiki)`——**`rehype-sanitize` 必须保留且在注入类插件之后**（XSS 边界）。新增渲染能力改这一个文件。
- **图表**：echarts 经 `echarts-for-react`，仅限懒加载页面内使用（已单独拆包，见 [Quality & Testing](./quality-and-testing.md#构建与性能)）。
- **主题/侧栏等持久化偏好**：走对应 store + `STORAGE_KEYS`，新键先在 constants 注册。
