# Smart RAG 前端设计系统规范

> **文档类型**：高保真设计系统规范（Design System Specification）
> **首轮交付范围**：仅设计规范，不含页面线框与信息架构
> **基准对标**：Dify · RAGFlow（AI / RAG 平台风）
> **技术栈取向**：Tailwind CSS v4 + shadcn/ui（Radix）+ CSS 变量 token + Inter
> **当前版本**：v0.3.3（技术栈锁定 + 流式耗时/token 不展示决策，详见 1.1）

---

## 1. 文档元信息

### 1.1 版本

| 版本 | 日期 | 变更 | 作者 |
|------|------|------|------|
| 0.3.3 | 2026-08-12 | 技术栈锁定（§15.4 最终版，去除全部"或"待决项）：React 19 + Vite + React Router v7；Tailwind v4（§15.2 改为 CSS-first `@theme`）；Zustand（UI 态）+ TanStack Query v5（服务端态）；传输用原生 fetch + 自定义 apiFetch 薄封装（不用 axios，因 SSE 必须用 fetch 避免双 transport）；shiki（rehype-pretty-code）；echarts（echarts-for-react）；Markdown 管线加 rehype-sanitize 白名单防 XSS。另：durationMs/tokenUsage 因 SSE 不发，决策流式当下不展示（不自行计时），历史 MessageVO 读取后展示（11.3/11.3.7/15.5 同步） | 前端设计 |
| 0.3.2 | 2026-08-12 | 前后端契约对齐（按代码事实校正伪阻塞 + 真/半阻塞降级 mock）：(1) G3 解决——SSE 帧结构从 3 类扩为 7 类（补 reasoning/agentMetadata/fallback/canceled），11.3.5 流式 agentMetadata 已可用；(2) D1 解决——时间字段全局统一 OffsetDateTime（`JacksonTimeConfig`），11.10/13.3/行 184 同步；(3) 15.5 表 T6 决策不加 token-in-body、T7/T8 标记已解决；(4) 11.8 score 明确展示策略——归一化前按后端原值展示（已知取舍，待归一化设计落地后零改动获得可比分数） | 前端设计 |
| 0.3.1 | 2026-06-21 | 修正 Agent 模式设计假设：确认后端 Agent 支持流式（`AgentModeStrategy.java:298` 实现 executeStream），三模式（SIMPLE/MULTI_TURN/AGENT）统一流式 UX。删除"Agent 阻塞式"全部描述（9.4 改为完成后展开动效、11.3.5 改为完成后元数据条、11.4 改为完成后可展开时间线、4.4.7 ChatMode 表去掉"阻塞式"标注） | 前端设计 |
| 0.3.0 | 2026-06-20 | 按用户反馈确立产品基调：新增 2.0 视觉基调节（蓝白配色 / 圆角舒适 / 破坏操作强制弹窗）；圆角阶梯整体上调大一档；新增 4.0 蓝白主调节 + 中性色改冷调；强化 10.11 ConfirmDialog；2.5 可恢复性升为硬规则；12.8 明确纯中文界面定位 | 前端设计 |
| 0.2.0 | 2026-06-20 | 按产品 UI 设计纪律强化：新增一致性锁（颜色/形状/组件）、交互态完整性（5态+3状态）、动效动机总表、组件类型→圆角硬映射表、文案四原则、Z-Index 纪律 | 前端设计 |
| 0.1.0 | 2026-06-20 | 首版：15 章设计系统规范 | 前端设计 |

### 1.1.2 本次优化依据（v0.3.0）

本次按用户明确反馈调整产品基调，四条均为用户指定、优先级高于细节决策：

- **蓝白配色**：用户指定"蓝白配色即可"。本次新增 2.0a 与 4.0 节，确立"白（极浅冷灰）为底 + 蓝为唯一强调色"的双线结构，中性色改注为冷调（blue-cast），明确禁用暖灰/米色/紫色，杜绝"AI 紫光"与"温暖工艺感"两种跑偏。
- **多圆角舒适**：用户指定"界面观感舒适，尽可能多用圆角"。本次将圆角阶梯整体上调大一档（v0.2.0 的 sm4/md6/lg8/xl12/2xl16 → v0.3.0 的 sm6/md8/lg12/xl16/2xl20），新增 3xl(24px) 用于登录卡等营销级入口；放宽原"表格必须 0 圆角"硬规则为"单元格内部 0、容器圆角 12"的折中，保留数据可读性同时不牺牲整体柔和感。
- **破坏操作强制弹窗**：用户指定"二次确认需要弹窗组件"。本次将 2.5 可恢复性升为硬规则，新增 10.11.1 ConfirmDialog 完整规范（警示图标 + 点名后果 + destructive 红色确认 + 默认聚焦取消），明确禁止"点即删"和 Toast 替代弹窗。
- **纯中文界面**：用户指定"平台只展示中文"。本次 12.8 从"国际化预留"改为"纯中文界面定位"，明确无语言切换器、第三方组件文案必须本地化；代码层 i18n key 作为工程纪律保留（不暴露给用户）。

### 1.1.3 本次修正依据（v0.3.1）

本次修正一处设计假设的事实错误（来自 Controller 层探索）：

- **Agent 模式支持流式**：v0.3.0 及之前假设 Agent 是"阻塞式返回、不走流式"，据此设计了"Agent 阻塞等待态动效"（9.4）、"Agent 完成后展示 agentMetadata"（11.3.5）、"AgentTraceTimeline 在等待时填充"（11.4）。但代码探索确认 `AgentModeStrategy.java:298` 实现了 `executeStream`，三种模式（SIMPLE/MULTI_TURN/AGENT）**都支持流式**。用户已决策采用**统一流式 UX**——三模式发送后都走"打字光标 + 逐字出现"，Agent 的差异仅体现在完成后多一个可展开的 agentMetadata 条。
- **影响范围**：9.4 从"阻塞等待态填补动效"改为"完成后展开回放动效"；11.3.5 从"阻塞返回后展示"改为"流式完成后展示"；11.4 从"阻塞等待时填充"改为"完成后可展开"；4.4.7 ChatMode 表 AGENT 去掉"阻塞式，不走流式"标注。这一修正简化了聊天工作台设计（无需为 Agent 单独一套等待态）。

### 1.1.1 本次优化依据（v0.2.0）

本次优化参考了以下前端设计纪律（已提炼为平台适配版本，未照搬营销页规则）：

- **形状一致性锁 / 颜色一致性锁**：成熟产品 UI 的硬纪律，要求"一套圆角规则处处遵守""单一强调色全站一致"。原规范圆角阶梯定义完整但缺硬映射，本次补《组件类型→圆角硬映射表》（7.4）。
- **交互态完整性**：每个交互组件交付前必须自查 5 交互态（default/hover/active/focus-visible/disabled）+ 3 数据态（loading/empty/error）。原规范组件章节分别写了态，但缺统一交付闸，本次补 2.9 与 10.0。
- **动效必须有动机**：任何动效需能用一句话回答"它传达了什么"，杜绝装饰性循环动画。原规范动效章未明确此原则，本次补 2.10 与 9.0 动效动机总表。
- **文案即设计材料**：主动语态、动作命名贯穿全流程、空状态是邀请而非情绪。原规范文案章已有术语表与状态文案，本次补 13.0 文案四原则作为总纲。
- **Z-Index 纪律**：业务代码禁止任意 z 值，只引用 token。原规范有 z-index 表但缺纪律声明，本次补 2.11。

> 说明：`design-taste-frontend` skill 自我声明不适用于 dashboard / 产品 UI（其 §13 out-of-scope 明确排除），本平台属其排除范围。但其通用纪律（一致性锁、交互态完整性、动效动机、Z-Index 克制、暗色强制）对产品 UI 同样成立，本次择适用部分落地，未照搬营销页专有规则（如 Hero 视口/眉题计数/em-dash 禁令等）。

### 1.2 适用范围

本规范是 Smart RAG 前端**唯一的视觉契约**，适用于：

- 后续所有页面高保真设计（线框图绘制时引用本规范的 token 与组件）
- 前端工程实现（token 直接复制为 CSS 变量，组件按本规范实现）
- 设计评审与走查的唯一标准

**不在本规范范围**（留待后续阶段）：

- 信息架构 / 导航树 / 路由结构
- 具体页面线框图
- 前端工程脚手架与组件库代码
- 后端联调配置调整

### 1.3 品牌占位说明 ⚠️

本规范所有品牌相关内容均为**可替换占位**：

| 占位项 | 占位值 | 对应 Token |
|--------|--------|-----------|
| 产品名 | `Smart RAG` | `--app-name` |
| 产品 Logo | 文字 Logo "SR" 方块 | `--app-logo` |
| 主色 | `#155EEF`（Dify 标志蓝） | `--brand-600` |
| Logo 方块色 | `#155EEF` | `--brand-logo-bg` |

**替换品牌零成本**：仅需修改 `:root` 中上述 4 个 token，全站自动生效。详见第 15 章附录《品牌替换清单》。

### 1.4 Token 替换指引

本规范所有色值、尺寸、间距均以 **CSS 变量**形式定义，遵循 `--{语义域}-{属性}-{阶}` 命名规则（详见第 3 章）。落地实现时：

```css
/* 设计系统统一在 :root 定义，暗色在 .dark 覆盖 */
:root {
  --brand-600: #155EEF;        /* 主色 — 品牌占位，可替换 */
  --text-primary: #101828;     /* 主文本 */
  /* ... 完整清单见第 15 章 */
}
.dark {
  --text-primary: #EAECF0;
  /* ... */
}
```

组件中**只引用 token**，禁止硬编码色值。这样切换主题 / 换品牌时无需改业务代码。

### 1.5 阅读约定

| 标记 | 含义 |
|------|------|
| ⚠️ | 待确认项 / 与后端契约有出入 / 需特别注意 |
| 🔒 | 锁定值，不可随意更改（如无障碍强制要求） |
| 💡 | 实现建议 |
| 📐 | 尺寸 / 数值规范 |
| 🎨 | 视觉示例（文字描述，因规范文档不含像素级视觉稿） |

---

## 2. 设计原则

Smart RAG 定位为**面向开发与运维团队的 AI / RAG 平台**（对标 Dify / RAGFlow），而非消费级聊天 App。设计原则据此推导。

### 2.0 视觉基调（产品定位基调）

> 三条基调是用户明确指定的产品观感方向，**优先级高于一切细节决策**，后续所有颜色、圆角、交互取舍都要服从这三条。

**(a) 蓝白配色（Blue & White）**：全站以**纯白 / 极浅冷灰为底，品牌蓝为唯一强调色**。中性色阶压向**冷调**（不用暖灰、不用米色、不用奶油色），让"白"保持干净通透；蓝仅出现在主操作、链接、激活态、聚焦环、状态色这几处，不蔓延成大面积色块。整体观感是"干净的科技感"，不是"温暖的工艺感"。

**(b) 圆角舒适（Soft Corners）**：**尽可能多用圆角**，追求柔和、不锐利的观感。默认所有容器、卡片、按钮、输入框、弹层都走圆角，圆角取值整体偏大（详见第 7 章）。**唯一例外**是密集数据表格的单元格内部，那里保留直角以维持数据可读性，但表格容器本身仍是圆角。

**(c) 破坏操作强制弹窗（Confirm Before Destroy）** 🔒：所有删除、解散、移除、拒绝、清空类破坏性操作，**必须**通过 Modal 二次确认（详见 2.5 与 10.11）。不允许用 Toast 直接执行、不允许"点即删"、不允许行内无确认。弹窗需明确说明后果，主按钮用 destructive 变体。

### 2.1 信息密度优先（Density First）

平台用户是技术人员，习惯密集信息界面。**默认采用中等偏高密度**：

- 表格行高紧凑（40-48px），一屏承载更多数据
- 侧栏、列表项不过度留白
- 文档管理、用量统计、团队列表等以表格为主，非卡片墙
- **反例**：ChatGPT 式的极致留白单列布局不适用于本平台

### 2.2 一致性（Consistency）

同一语义、同一组件、同一 token。**禁止"看起来差不多"的重复造轮子**：

- 所有"上传中"状态用同一种 Progress + 文案，不混用 Spinner / 进度条
- 所有"成功/失败"用同一套语义色，不临时取色
- 所有"删除确认"用同一 Modal，不每个模块各写一个
- 时间显示全站用同一格式化规则（第 13 章）

### 2.3 即时反馈（Immediate Feedback）

RAG / Agent 操作耗时长（流式生成、文档向量化、Agent 多轮工具调用），**必须让用户时刻感知"系统在动"**：

- 流式聊天：打字光标 + 逐字出现
- 文档处理：状态徽标流转 + 进度条 + 可轮询刷新
- Agent 推理：流式完成后可展开的工具调用时间线（11.4）
- 网络：请求 > 200ms 显示 loading，> 3s 显示骨架屏
- 操作：点击按钮即时态变化，不等待响应才反馈

### 2.4 可发现性（Discoverability）

平台功能多（聊天 / 知识库 / 团队 / 用量 / 后台），**深度功能不能藏在三级菜单里**：

- 持久左侧导航栏，一级模块始终可见
- 关键操作（如上传文档、新建会话）放一级操作位
- 长列表用筛选 / 搜索 / 状态过滤前置
- 空状态引导用户下一步动作（非空白页）

### 2.5 可恢复性（Recoverability）🔒

平台允许破坏性操作（删文档、解散团队、删会话），**所有破坏性操作必须可撤销或二次确认**：

- **删除 / 解散 / 移除 / 拒绝 / 清空类操作** 🔒：必须用 Modal 二次确认（详见 2.0c 与 10.11）。弹窗明确说明后果（如"文档及其向量数据将被永久删除，此操作不可撤销"），主按钮用 destructive 变体，**不允许"点即删"、不允许 Toast 直接执行、不允许行内无确认**。例外仅限：撤销操作本身（如取消上传、撤回审批）这类"反破坏"动作。
- 长耗时操作（上传/向量化）：可取消 + 可重试
- 错误状态：提供重试入口，不卡死

### 2.6 无障碍基线（Accessibility Baseline）🔒

**非可选项**，所有组件必须满足（详见第 12 章）：

- 文本对比度 ≥ 4.5:1（WCAG AA）
- 所有交互可键盘操作
- 焦点环始终可见
- 动效尊重 `prefers-reduced-motion`

### 2.7 与后端契约对齐（Backend-Aligned）

前端是后端数据的视图层，**不臆造状态、不臆造字段**：

- 状态徽标必须覆盖后端枚举的**全部值**（如 EtlStatus 11 个值，缺一个就是 bug）
- 表单字段、校验规则、文案与后端 DTO 校验注解一致（如密码 8-72 位）
- 错误文案与后端错误消息体系对齐（第 13 章）
- 时间戳统一为 **OffsetDateTime**（后端 `JacksonTimeConfig` 全局统一，无 LocalDateTime/Instant 混用，详见 13.3）

### 2.8 一致性锁（Consistency Lock）🔒

> 来自成熟产品 UI 的硬纪律。任何组件、任何页面落地前，必须通过下列三锁自检。

**(a) 颜色锁（Color Lock）**：全站**单一强调色**（品牌主色 `--brand-600`）。语义色（success/warning/error）仅用于状态徽标与系统提示，不作为页面装饰色。一个页面里，主色在导航激活、主按钮、链接、聚焦环上的使用必须**完全一致**——不允许"第 3 节突然换个蓝"。

**(b) 形状锁（Shape Lock）** 🔒：全站**一套圆角规则，处处遵守**。组件类型与圆角的映射是硬规则（见第 7.4 章《组件类型→圆角硬映射表》），不允许"这个卡片 12px、那个卡片 16px"的随意取值。圆角胶囊按钮出现在方角布局里，或方角卡片出现在胶囊按钮页面里，都是设计缺陷。

**(c) 组件锁（Component Lock）**：同一语义只用同一组件。禁止"看起来差不多"的重复造轮子——所有"上传中"用同一种 Progress、所有"成功/失败"用同一套 Badge、所有"删除确认"用同一个 Modal。

### 2.9 交互态完整性（Interactive States Completeness）🔒

> 这是评审走查的**第一道闸**。每个交互组件交付时，必须自查下列 5 态 + 3 状态齐全，缺一个即不验收。

**5 交互态**（每个交互元素都要实现）：

| 态 | 含义 | 示例 |
|----|------|------|
| `default` | 默认静止 | 按钮未操作 |
| `hover` | 悬停（鼠标） | 鼠标移入 |
| `active` | 按下/激活 | 点击瞬间、当前选中项 |
| `focus-visible` | 键盘聚焦 | Tab 到达（鼠标点击不显示，键盘显示） |
| `disabled` | 禁用 | 提交中、无权限 |

**3 状态**（每个承载数据/操作的组件都要考虑）：

| 状态 | 要求 |
|------|------|
| `loading` | 骨架屏（≥3s）或内联 Spinner（按钮内），**不用转圈占满整屏** |
| `empty` | 空状态需有引导文案 + 下一步动作按钮，**禁止纯空白页**（文案见 13.6） |
| `error` | 错误需说明"出了什么问题 + 怎么修"，并提供重试入口，**禁止只显示一个错误码** |

> ⚠️ Agent 模式 v0.3.1 起确认支持流式（与其他模式统一）。流式生成期间用标准打字光标（9.3），完成后 agentMetadata 条出现并可展开 AgentTraceTimeline（11.3.5、11.4），不再有独立的"阻塞等待态"。

### 2.10 动效必须有动机（Motion Must Be Motivated）

**禁止"为了动而动"**。任何动效在评审时必须能用一句话回答"它传达了什么"：

| 合法动机 | 例子 |
|---------|------|
| 层级引导 | 流式光标吸引视线到正在生成的内容 |
| 叙事 | AgentTraceTimeline 按工具调用顺序逐项展开，对应推理过程 |
| 反馈 | 按钮按下时的 `scale(0.98)`，确认操作生效 |
| 状态转换 | 文档状态徽标颜色平滑过渡，表明状态变化 |

答不出"传达了什么"的动效——删除。装饰性循环动画（无限脉冲、永续视差）默认不要，除非有明确的"系统正在运行"语义（如流式光标、骨架屏）。

### 2.11 Z-Index 纪律（Z-Index Restraint）

**业务代码禁止使用任意 z 值**（如 `z-50`、`z-[999]`）。z-index 仅用于系统层级（第 6.5 章已定义 `--z-*` token）：

- 悬浮表头用 `--z-sticky`
- 弹层用 `--z-dropdown` / `--z-modal` / `--z-drawer`
- 提示用 `--z-toast` / `--z-tooltip`

业务组件若需提升层级，必须引用 token，并在代码注释说明理由。z-index 混乱是 SPA 调试最痛的坑之一，从设计阶段就锁死。

---

## 3. Design Token 总览

### 3.1 命名规则

所有 token 遵循统一层级命名：`--{语义域}-{属性}-{阶}`

| 语义域 | 含义 | 示例 |
|--------|------|------|
| `brand` | 品牌色（可替换） | `--brand-600` |
| `neutral` | 中性色阶 | `--neutral-500` |
| `text` | 文本（语义化别名） | `--text-primary` |
| `bg` | 背景（语义化别名） | `--bg-card` |
| `border` | 边框（语义化别名） | `--border-default` |
| `success` / `warning` / `error` / `info` | 语义色阶 | `--success-600` |
| `radius` | 圆角 | `--radius-md` |
| `shadow` | 阴影 | `--shadow-sm` |
| `space` | 间距 | `--space-4` |
| `font` | 字体 | `--font-size-md` |
| `motion` | 动效 | `--motion-duration-fast` |
| `state` | 业务状态别名 | `--state-doc-completed` |
| `app` | 应用品牌 | `--app-name` |

**两条铁律**：

1. **业务组件只引用语义别名**（如 `var(--text-primary)`），不引用原始色阶（如 `var(--neutral-700)`）
2. **原始色阶只在 `:root` 与语义别名定义中出现**

这样做的好处：换品牌只改 `brand-*`，换主题只改语义别名，业务代码零改动。

### 3.2 双层 token 结构

```
原始色阶（Primitive）         语义别名（Semantic）
--brand-600: #155EEF;   ──►  --text-link: var(--brand-600);
--neutral-700: #344054; ──►  --text-primary: var(--neutral-700);
--neutral-100: #F2F4F7; ──►  --bg-card: var(--neutral-0);     // 多对一映射
                              --bg-base: var(--neutral-50);
```

- **原始色阶**：固定的色卡，亮暗模式都存在但取值不同（如 `--brand-600` 亮色 `#155EEF`，暗色可微调为 `#2E6FF2`）
- **语义别名**：业务引用层，亮暗模式映射到不同的原始色阶

### 3.3 暗色模式机制

采用**类名切换 + CSS 变量覆盖**（Tailwind v4 用 `@custom-variant dark (&:where(.dark, .dark *))` 启用 `.dark` 类切换，见 §15.2）：

```html
<html class="dark">  <!-- 切换 -->
```

- 亮色定义在 `:root`
- 暗色定义在 `.dark`
- 切换时由浏览器自动应用对应变量，**无需 JS 重新渲染**
- 默认跟随系统 `prefers-color-scheme`，用户可在设置中手动锁定

完整 token 清单见第 15 章附录。

---

## 4. 颜色系统

> 本章是 **2.0a「蓝白配色」基调的落地**。全站色彩极简：**白（含极浅冷灰）为底，蓝为唯一强调色**，语义色仅用于状态。禁用暖灰、米色、奶油色、紫色等任何会破坏"干净科技感"的色调。

### 4.0 蓝白主调（Blue & White）🔴

平台配色就两条主线，**不多不少**：

1. **白线（中性）**：从纯白 `#FFFFFF` 到极浅冷灰 `#F2F4F7` 的窄区间，承担界面 80% 渲染。冷调（blue-cast gray，不带黄/红），让"白"保持通透干净。背景层次靠"白 → 极浅冷灰 → 浅冷灰"三档区分，不靠色相变化。
2. **蓝线（强调）**：品牌蓝 `#155EEF` 一族，只出现在 4 类位置：**主操作（主按钮）、链接与激活态、聚焦环、状态色映射**。蓝色不蔓延成大面积色块、不做渐变背景、不做装饰。

**反例（明确禁用）**：
- ❌ 暖中性色（Tailwind `stone`/`warmGray`、`#F5F1EA` 米色系）
- ❌ 紫色 / 紫蓝（"AI 紫光"）
- ❌ 多强调色（绿/橙/粉同时出现作装饰，状态色除外）
- ❌ 蓝色渐变背景墙、蓝色大面积 hero

### 4.1 品牌主色（可替换占位）

基准 `#155EEF`（Dify 标志蓝），提供 50-950 全阶便于浅色背景、悬停态、禁用态的统一表达。

| Token | 色值 | 用途 |
|-------|------|------|
| `--brand-50` | `#EFF4FF` | 主色 5% tint 背景（选中行底色、信息提示底） |
| `--brand-100` | `#D1E0FF` | 主色浅边框、浅色徽标底 |
| `--brand-200` | `#B3CCFF` | — |
| `--brand-300` | `#94B8FF` | — |
| `--brand-400` | `#5C8BFF` | 次要交互（图标悬停） |
| `--brand-500` | `#2E6FF2` | — |
| `--brand-600` 🔒 | `#155EEF` | **主色基准**：主按钮、链接、聚焦环、激活态 |
| `--brand-700` | `#0D4EDB` | 主按钮悬停 |
| `--brand-800` | `#0B3FAE` | 主按钮按下 |
| `--brand-900` | `#0A2E7C` | — |
| `--brand-logo-bg` | `#155EEF` | Logo 方块背景（品牌占位） |

💡 实现建议：使用 Tailwind 配置把 `--brand-*` 映射为 `primary` 色阶，组件写 `bg-primary-600`。

### 4.2 中性色阶（冷调白）🔴

中性色承担平台 80% 的界面渲染，分**色卡阶**和**语义别名**两层。色卡压向**冷调**（blue-cast），不用暖灰。

**色卡阶（Cool Gray）：**

| Token | 亮色 | 暗色 | 用途 |
|-------|------|------|------|
| `--neutral-0` | `#FFFFFF` | `#101828` | 卡片 / 模态背景（纯白） |
| `--neutral-50` | `#F9FAFB` | `#1D2939` | 页面底色（canvas，极浅冷白） |
| `--neutral-100` | `#F2F4F7` | `#212B36` | 区块底色（base）/ 表头（浅冷灰） |
| `--neutral-200` | `#E4E7EC` | `#2D3748` | 边框（弱） |
| `--neutral-300` | `#D0D5DD` | `#475467` | 边框（默认）/ 分隔线 |
| `--neutral-400` | `#98A2B3` | `#667085` | 占位符 / 禁用 |
| `--neutral-500` | `#667085` | `#98A2B3` | 辅助文本 / 次要图标 |
| `--neutral-700` | `#344054` | `#D0D5DD` | 次要正文 |
| `--neutral-900` | `#101828` | `#EAECF0` | 主文本 |

**语义别名（业务引用层）：**

| 别名 | 亮色映射 | 暗色映射 | 说明 |
|------|----------|----------|------|
| `--text-primary` | `--neutral-900` | `--neutral-0` | 主标题、正文 |
| `--text-secondary` | `--neutral-700` | `--neutral-100` | 次要描述 |
| `--text-tertiary` | `--neutral-500` | `--neutral-300` | 元信息、时间戳 |
| `--text-disabled` | `--neutral-400` | `--neutral-400` | 禁用文字 |
| `--text-inverse` | `--neutral-0` | `--neutral-900` | 主色背景上的文字 |
| `--text-link` | `--brand-600` | `--brand-400` | 链接 |
| `--bg-canvas` | `--neutral-50` | `--neutral-900` | 应用底色 |
| `--bg-base` | `--neutral-100` | `--neutral-900` | 区块底 |
| `--bg-card` | `--neutral-0` | `--neutral-800` | 卡片 / 弹层 |
| `--bg-input` | `--neutral-0` | `--neutral-800` | 输入框底 |
| `--bg-hover` | `--neutral-50` | `--neutral-700` | 悬停行 |
| `--bg-selected` | `--brand-50` | `--brand-900` | 选中行 |
| `--border-default` | `--neutral-300` | `--neutral-700` | 默认边框 |
| `--border-strong` | `--neutral-400` | `--neutral-500` | 强调边框 |
| `--border-accent` | `--brand-600` | `--brand-500` | 聚焦边框 |
| `--border-subtle` | `--neutral-200` | `--neutral-800` | 弱分隔线 |

### 4.3 语义色

四种语义色，每种提供 50-950 阶 + 一个 `-tint`（5% 透明度背景，用于徽标底）。

#### Success（成功 / 完成）

| Token | 色值 | 用途 |
|-------|------|------|
| `--success-50` | `#ECFDF3` | tint 背景 |
| `--success-600` | `#039855` | 图标 / 文字 |
| `--success-700` | `#027A48` | 悬停 |
| `--success-tint` | `#0398551A` | 5% 透明（徽标底） |

#### Warning（警告 / 等待 / 审批中）

| Token | 色值 | 用途 |
|-------|------|------|
| `--warning-50` | `#FFFAEB` | tint 背景 |
| `--warning-600` | `#F79009` | 图标 / 文字 |
| `--warning-700` | `#DC6803` | 悬停 |
| `--warning-tint` | `#F790091A` | 5% 透明 |

#### Error（错误 / 失败 / 拒绝）

| Token | 色值 | 用途 |
|-------|------|------|
| `--error-50` | `#FEF3F2` | tint 背景 |
| `--error-600` | `#D92D20` | 图标 / 文字 |
| `--error-700` | `#B42318` | 悬停 |
| `--error-tint` | `#D92D201A` | 5% 透明 |

#### Info（信息 / 处理中 / 主色借用）

⚠️ 本平台 **Info 语义直接复用 brand 主色**（Dify/RAGFlow 惯例，避免蓝紫色重复），不单独定义：

| 别名 | 映射 | 用途 |
|------|------|------|
| `--info-600` | `--brand-600` | 信息图标 |
| `--info-50` | `--brand-50` | 信息 tint 背景 |

### 4.4 业务状态色映射总表 🔒

本表是**状态徽标系统的权威映射**，覆盖后端全部业务枚举。任何状态渲染必须查此表，不可临时取色。

> 数据来源：`rag/etl/EtlStatus.java`、`conversation/enums/*`、`team/enums/*`、`agent/intent/AgentIntent.java`、`chat/mode/ChatMode.java`、`user/enums/UserStatus.java`（详见各枚举源文件）。

#### 4.4.1 文档处理状态 `EtlStatus`（11 个值）⚠️

> README 仅提 6 个值，实际枚举 11 个（源文件 `rag/etl/EtlStatus.java:11-25`）。**全部必须支持**，缺一个即 bug。

| 枚举值 | 中文文案 | 色值 token | 图标（lucide） | 场景 |
|--------|---------|-----------|---------------|------|
| `UPLOADED` | 已上传 | `--neutral-500` | `FileUp` | 个人上传完成，待处理 |
| `PENDING_APPROVAL` | 待审批 | `--warning-600` | `Clock` / `Hourglass` | 团队上传待管理员审批 |
| `PARSING` | 解析中 | `--info-600` | `Loader`（旋转） | Tika 解析文档 |
| `CHUNKING` | 分块中 | `--info-600` | `Scissors` | Parent-Child 分块 |
| `VECTORIZING` | 向量化中 | `--info-600` | `Sparkles` | Embedding 向量化 |
| `PROCESSING` | 处理中 | `--info-600` | `Loader` | 通用进行中（无具体阶段） |
| `COMPLETED` | 已完成 | `--success-600` | `CheckCircle2` | 处理完成，可检索 |
| `FAILED` | 处理失败 | `--error-600` | `XCircle`（+ 重试按钮） | 处理失败，可查看 errorMessage |
| `VECTOR_FAILED` | 向量化失败 | `--error-600` | `XCircle`（+ 重试按钮） | 仅向量化步骤失败（区别于 FAILED） |
| `REJECTED` | 已拒绝 | `--error-600` | `Ban` | 团队审批被拒 |
| `SUPERSEDED` | 已被替代 | `--neutral-400` | `GitBranch` / `ArrowRightLeft` | 文档增量更新后旧版本 |

**状态分组渲染规则**：

- 进行中类（`PARSING` / `CHUNKING` / `VECTORIZING` / `PROCESSING`）：蓝色徽标 + 旋转图标，可配进度条
- 等待类（`UPLOADED` / `PENDING_APPROVAL`）：中性 / 警告徽标，无旋转
- 完成类（`COMPLETED`）：绿色徽标
- 失败类（`FAILED` / `VECTOR_FAILED` / `REJECTED`）：红色徽标 + 操作（重试 / 查看原因）
- 历史类（`SUPERSEDED`）：灰色弱化徽标，通常配合版本链查看

#### 4.4.2 会话状态 `ConversationStatus`（3 值）

| 枚举值 | 中文文案 | 色值 | 图标 | 场景 |
|--------|---------|------|------|------|
| `ACTIVE` | 活跃 | `--success-600` | `MessageCircle` | 正常会话（默认） |
| `ARCHIVED` | 已归档 | `--neutral-500` | `Archive` | 用户归档 |
| `DELETED` | 已删除 | `--neutral-400` | `Trash2` | 软删除（列表不显示，仅管理可见） |

#### 4.4.3 消息状态 `MessageStatus`（3 值）

| 枚举值 | 中文文案 | 色值 | 图标 | 场景 |
|--------|---------|------|------|------|
| `IN_PROGRESS` | 生成中 | `--info-600` | 闪烁光标 `▍` | 流式生成或 Agent 推理中 |
| `FINISHED` | 已完成 | `--neutral-500`（弱） | `Check`（hover 显示） | 正常完成 |
| `ERROR` | 出错 | `--error-600` | `AlertCircle` + 重试 | 生成失败 |

#### 4.4.4 审批状态 `ApprovalStatus`（3 值）

| 枚举值 | 中文文案 | 色值 | 图标 | 场景 |
|--------|---------|------|------|------|
| `PENDING` | 待审批 | `--warning-600` | `Clock` | 等待审批 |
| `APPROVED` | 已通过 | `--success-600` | `CheckCircle2` | 审批通过 |
| `REJECTED` | 已拒绝 | `--error-600` | `XCircle` | 审批拒绝（含 reviewComment） |

#### 4.4.5 团队成员角色 `TeamMemberRole`（3 值）

| 枚举值 | 中文文案 | 色值 | 图标 | 场景 |
|--------|---------|------|------|------|
| `CREATOR` | 创建者 | `--brand-600` | `Crown` | 团队创建人（不可移除 / 不可降级） |
| `ADMIN` | 管理员 | `--success-600` | `ShieldCheck` | 可管理成员 / 审批 |
| `MEMBER` | 成员 | `--neutral-500` | `User` | 普通成员 |

#### 4.4.6 Agent 意图 `AgentIntent`（4 值）

| 枚举值 | 中文文案 | 色值 | 图标 | 场景 |
|--------|---------|------|------|------|
| `DIRECT_ANSWER` | 直接回答 | `--neutral-500` | `MessageSquare` | 无需检索 |
| `RETRIEVAL` | 检索 | `--info-600` | `Search` | 单轮检索 |
| `DEEP_RETRIEVAL` | 深度检索 | `--brand-600` | `Radar` / `Layers` | 多轮 / 多子查询 |
| `GENERAL_TOOL` | 通用工具 | `--warning-600` | `Wrench` | 调用非检索类工具 |

#### 4.4.7 聊天模式 `ChatMode`（3 值）

> README 仅提 SIMPLE / MULTI_TURN，实际有 AGENT 第 3 种。

| 枚举值 | 中文文案 | 色值 | 图标 | 场景 |
|--------|---------|------|------|------|
| `SIMPLE` | 单轮 | `--neutral-500` | `MessageSquare` | 不维护上下文 |
| `MULTI_TURN` | 多轮 | `--info-600` | `MessagesSquare` | 自动维护会话记忆 |
| `AGENT` | Agent | `--brand-600` | `Bot` | Agentic RAG（支持流式，完成后可展开推理详情） |

#### 4.4.8 标题来源 `TitleSource`（2 值）

| 枚举值 | 中文文案 | 表现 | 场景 |
|--------|---------|------|------|
| `SYSTEM` | 系统生成 | 灰色小标签"自动" | 首条消息截取 |
| `USER` | 用户自定义 | 无标签 / 蓝色编辑图标 | 用户手动设置 |

#### 4.4.9 用户状态 `UserStatus`（整数 0/1）⚠️

> 后端 `UserVO.status` 字段类型是 **`Integer`**（手动存 `UserStatus.code`，0/1），前端按整数判断。注意 `UserStatus` 枚举本身**无 `@JsonValue`**——若未来有端点直接返回枚举对象会输出字符串名（"DISABLED"/"ENABLED"），当前 `UserVO` 走 Integer 路径不受影响，但接触枚举本身时需留意。

| 整数值 | 枚举名 | 中文文案 | 色值 | 图标 | 场景 |
|--------|--------|---------|------|------|------|
| `1` | `ENABLED` | 启用 | `--success-600` | `CheckCircle2` | 正常用户 |
| `0` | `DISABLED` | 禁用 | `--error-600` | `Ban` | 被禁用（无法登录） |

> ⚠️ 用户状态更新接口用 **query param**：`POST /api/users/{id}/status?status=0`（`@RequestParam`），**不是 JSON body**。前端若发 `{status:0}` body 会被忽略（status 为 null → 400）。

#### 4.4.10 AgentTrace finalStatus（4 值）

| 枚举值 | 中文文案 | 色值 | 图标 | 场景 |
|--------|---------|------|------|------|
| `COMPLETED` | 已完成 | `--success-600` | `CheckCircle2` | 正常完成 |
| `DEGRADED` | 已降级 | `--warning-600` | `ArrowDownCircle` | Agent 降级到普通 RAG |
| `FAILED` | 失败 | `--error-600` | `XCircle` | 失败 |
| `GUARDRAIL_STOPPED` | 已拦截 | `--error-600` | `ShieldAlert` | Guardrail 拦截 |

#### 4.4.11 AgentEventType（6 值）— 见 11.3 AgentTraceTimeline

| 枚举值 | 中文文案 | 优先级 | 图标 |
|--------|---------|--------|------|
| `INTENT_CLASSIFIED` | 意图识别 | CRITICAL | `BrainCircuit` |
| `RETRIEVAL_STRATEGY` | 检索策略 | HIGH | `Radar` |
| `TOOL_CALLED` | 工具调用 | NORMAL | `Wrench` |
| `SELF_REFLECTION` | 自我反思 | HIGH | `RefreshCw` |
| `INTERMEDIATE_ANSWER` | 中间答案 | CRITICAL | `FileText` |
| `GUARDRAIL_TRIGGERED` | 守门触发 | CRITICAL | `ShieldAlert` |

#### 4.4.12 错误码分段（前端全局提示用）

| code 范围 | 模块 | 提示色 |
|----------|------|--------|
| `0` | 成功 | success Toast |
| `40000-40099` | 通用（参数/校验/权限） | error Toast |
| `40100` | 未认证 | → 跳登录 |
| `40300` | 权限不足 | error Toast |
| `40400` | 资源不存在 | error Toast |
| `42900` | 限流 | warning Toast |
| `50000` | 内部错误 | error Toast |
| `10xxx` | 认证 | error Toast |
| `20xxx` | 用户管理 | error Toast |
| `30xxx` | 会话 | error Toast |
| `40xxx` | 聊天 | error Toast |
| `50xxx` | RAG | error Toast |
| `60xxx` | 团队 | error Toast |

---

## 5. 排版系统

### 5.1 字体族

```css
--font-sans: 'Inter', -apple-system, BlinkMacSystemFont,
             'Segoe UI', 'PingFang SC', 'Microsoft YaHei',
             'Helvetica Neue', Arial, sans-serif;
--font-mono: 'JetBrains Mono', 'SF Mono', Menlo, Consolas,
             'Courier New', monospace;
```

- **主字体**：Inter（拉丁/数字）+ 系统中文字体兜底（PingFang SC / 微软雅黑）。Inter 在小字号下 x-height 高、可读性强，是 Dify/RAGFlow 的共同选择。
- **代码字体**：JetBrains Mono —— 用于消息中的代码块、模型 ID、JSON 展示（如 agentMetadata）
- ⚠️ 中文渲染：Inter 不含中文字形，浏览器自动 fallback 到系统中文字体。`--font-sans` 字符串中务必保留 `'PingFang SC'`（macOS）/ `'Microsoft YaHei'`（Windows）以统一渲染。

### 5.2 字号阶梯（Type Scale）

基于 1.125 模数比，根字号 14px（平台密度偏高）。

| Token | 字号 | 行高 | 字重 | 用途 |
|-------|------|------|------|------|
| `--font-size-xs` | 11px | 16px | 400 | 辅助标签、徽标 |
| `--font-size-sm` | 12px | 18px | 400 | 次要文本、表格副信息 |
| `--font-size-base` | 13px | 20px | 400 | **默认正文**（平台密度） |
| `--font-size-md` | 14px | 22px | 400 | 主要正文 |
| `--font-size-lg` | 16px | 24px | 500 | 卡片标题、表单 label |
| `--font-size-xl` | 18px | 26px | 600 | 页面标题 |
| `--font-size-2xl` | 22px | 30px | 600 | 区块大标题 |
| `--font-size-3xl` | 28px | 36px | 700 | 营销/空状态大标题 |

> 💡 Tailwind 默认根字号 16px 偏大。建议全局设 `html { font-size: 14px; }` 让 `text-sm`(12) / `text-base`(13 自定义) 匹配本阶梯，或自定义 Tailwind fontSize 配置。

### 5.3 字重

| 字重 | token | 用途 |
|------|-------|------|
| 400 Regular | `--font-normal` | 正文 |
| 500 Medium | `--font-medium` | 标签、按钮文字、菜单激活态、表头 |
| 600 Semibold | `--font-semibold` | 卡片标题、页面标题、强调 |
| 700 Bold | `--font-bold` | 营销标题、数字强调（用量统计） |

> ⚠️ 中文慎用 Bold（700），中文字形笔画密，粗体易糊。中文强调用 600 即可。

### 5.4 行高

- 正文 / 多行：`1.5`（20/30 等）
- 标题：`1.3`
- 单行（按钮、徽标、菜单）：`1` 或固定 `line-height: 1`，垂直居中
- 代码块：`1.6`

### 5.5 中文断行规则 🔒

```css
word-break: break-word;      /* 中英混排长词可断 */
overflow-wrap: anywhere;     /* 超长 URL / 路径强制断行 */
line-height: 1.5;            /* 中文行距需大于西文 */
```

- 消息内容、错误信息（含长 stack trace）必须 `overflow-wrap: anywhere`
- 数字与英文（如 token 数、模型 ID）在中文段落中加 `lang` 属性辅助换行

### 5.6 对齐

- 默认左对齐
- 表格数字列（用量、token、文件大小）右对齐
- 表头随数据列对齐
- 标题、按钮文字居中或左对齐，**不两端对齐**（`justify` 在中文会产生不规则空隙）

---

## 6. 间距与栅格

### 6.1 间距阶梯（4px base）

| Token | 值 | 典型用途 |
|-------|----|---------|
| `--space-0` | 0 | — |
| `--space-1` | 4px | 图标与文字间距、紧凑组件内边距 |
| `--space-2` | 8px | 相关元素组内间距、按钮内边距 |
| `--space-3` | 12px | 表单项间距、列表项间距 |
| `--space-4` | 16px | 卡片内边距、表单字段间距、主要间距 |
| `--space-5` | 20px | 区块间距 |
| `--space-6` | 24px | 大区块间距、页面 padding |
| `--space-8` | 32px | 页面主区间距、空状态 padding |
| `--space-10` | 40px | — |
| `--space-12` | 48px | 模态内大留白 |
| `--space-16` | 64px | — |
| `--space-20` | 80px | 空状态大留白 |

**铁律**：所有间距必须是 4 的倍数（4 / 8 / 12...），禁止 5px / 7px / 13px 等奇数值。

### 6.2 布局尺寸（Layout Shell）

```
┌──────────────────────────────────────────────────────────┐
│ TopBar  56px                                              │
├─────────────┬────────────────────────────────────────────┤
│             │                                            │
│  Sidebar    │            Content Area                    │
│  240px      │  (max-width: 1440px, 居中)                  │
│  / 64px 收  │  padding: 24px                             │
│             │                                            │
└─────────────┴────────────────────────────────────────────┘
```

| 元素 | 尺寸 token | 值 | 说明 |
|------|-----------|----|----|
| 顶栏 | `--layout-topbar-h` | 56px | 全局顶栏高度 |
| 侧栏（展开） | `--layout-sidebar-w` | 240px | 默认展开宽度 |
| 侧栏（收起） | `--layout-sidebar-collapsed-w` | 64px | 仅图标 |
| 内容区最大宽 | `--layout-content-max-w` | 1440px | 居中，超宽屏留白 |
| 内容区 padding | `--layout-content-p` | 24px | 左右内边距 |
| 聊天工作台输入区 | `--layout-chat-input-w` | 768px | 聊天输入框最大宽度 |

### 6.3 聊天工作台特殊布局

> ⚠️ **结构以 INFORMATION-ARCHITECTURE.md 3.1 形态 2 为准**（IA v0.3.0）。本节仅定义**视觉尺寸**，布局结构（左栏纵向堆叠、会话列表位置）见 IA 文档。

聊天页的**内容区**采用两栏布局（消息流 + 右侧详情），会话列表位于左栏下半区（与主导航共用 280px 左栏，见 IA 3.1）：

```
┌─ 左栏 280px ─────┬─ 内容区 ──────────────────────────────┐
│ 主导航（上半）    │                                       │
│ ═════════════    │                                       │
│ 会话列表（下半）  │  消息流 (flex-1)   │  详情/引用 320px  │
│ （独立滚动）      │                    │  （可折叠，默认收起）│
│                  │                    │  Agent Trace      │
└──────────────────┴─────────────────────┴───────────────────┘
```

| 元素 | 尺寸 |
|------|------|
| 左栏（主导航+会话列表共用） | 280px 固定（前台不折叠，见 IA 3.1） |
| 消息流 | flex 自适应，min-width 480px |
| 右侧详情栏 | 320px（可折叠，默认收起；引用来源/Agent Trace 展开时显示） |

### 6.4 表格

| 元素 | 尺寸 |
|------|------|
| 行高（紧凑） | 40px |
| 行高（默认） | 48px |
| 表头高度 | 44px |
| 单元格 padding | 12px 16px |
| 列最小宽 | 80px |

### 6.5 z-index 层级

| 层 | token | 值 | 用途 |
|----|-------|----|----|
| 基础 | `--z-base` | 0 | 内容区 |
| 悬浮 | `--z-sticky` | 10 | 吸顶表头、固定列 |
| 抽屉 | `--z-drawer` | 100 | 侧滑抽屉 |
| 下拉 | `--z-dropdown` | 1000 | Select / Menu 下拉 |
| 模态 | `--z-modal` | 1100 | 对话框 |
| Toast | `--z-toast` | 1200 | 全局提示 |
| Tooltip | `--z-tooltip` | 1300 | 气泡提示 |

---

## 7. 圆角与阴影

> 本章是 **2.0b「圆角舒适」基调的落地**。圆角整体偏大，追求柔和观感。v0.3.0 起阶梯较 v0.1.0/v0.2.0 整体上调大一档。

### 7.1 圆角阶梯（Comfort Scale）

| Token | 值 | 用途 |
|-------|----|----|
| `--radius-none` | 0 | 表格**单元格内部**、纯文本分隔线（仅限数据单元格内部，容器本身仍圆角） |
| `--radius-sm` | 6px | 徽标方角变体、复选框、小型紧凑控件 |
| `--radius-md` | 8px | 按钮、输入框、下拉触发器（交互控件默认） |
| `--radius-lg` | 12px | 卡片、列表项、下拉面板、菜单项（内容容器默认） |
| `--radius-xl` | 16px | 对话框、抽屉、空状态容器（大型浮层） |
| `--radius-2xl` | 20px | 大型弹出面板（AgentTraceTimeline 等） |
| `--radius-3xl` | 24px | 引导性大容器（登录卡、首次使用引导） |
| `--radius-full` | 9999px | 头像、胶囊标签、滑块圆点、单选框、进度条 |

**圆角舒适三规则**：

1. **默认带圆角**：除"表格单元格内部"和"纯分隔线"两处，所有元素默认带圆角。需要"直角"必须说明理由（如密集数据网格的可读性）。
2. **嵌套递减**：内部元素圆角 ≤ 外层容器圆角（如卡片 12px 内的按钮 8px、卡片内的输入框 8px）。
3. **同组件族同圆角**：所有按钮 8px、所有卡片 12px、所有对话框 16px——不因"这个卡片大一点"就给 16px。

### 7.2 阴影

平台**弱化阴影**，以边框 + 层级背景区分层次（Dify 风格）。圆角加大后阴影更柔和，但仍保持克制。

| Token | 值 | 用途 |
|-------|----|----|
| `--shadow-none` | none | — |
| `--shadow-xs` | `0 1px 2px rgba(16,24,40,0.05)` | 卡片默认（极轻） |
| `--shadow-sm` | `0 1px 3px rgba(16,24,40,0.08), 0 1px 2px rgba(16,24,40,0.04)` | 卡片 hover、下拉 |
| `--shadow-md` | `0 4px 12px -2px rgba(16,24,40,0.1), 0 2px 6px -2px rgba(16,24,40,0.06)` | 模态、弹层（圆角加大，阴影扩散范围相应增大） |
| `--shadow-lg` | `0 16px 32px -4px rgba(16,24,40,0.1), 0 4px 10px -2px rgba(16,24,40,0.04)` | 大型浮层、对话框 |
| `--shadow-focus` | `0 0 0 4px var(--brand-100)` | 聚焦环（配合 border-accent） |

> 阴影色统一用背景色调和的暗色 `rgba(16,24,40,...)`，**不用纯黑** `rgba(0,0,0,...)`，避免暗色模式下出现生硬黑边。

### 7.3 边框

| Token | 值（亮色） | 用途 |
|-------|-----------|----|
| `--border-default` | `1px solid var(--neutral-300)` | 卡片、输入框默认 |
| `--border-subtle` | `1px solid var(--neutral-200)` | 区块内分隔 |
| `--border-strong` | `1px solid var(--neutral-400)` | 强调容器 |
| `--border-accent` | `1px solid var(--brand-600)` | 聚焦态 |
| `--border-error` | `1px solid var(--error-600)` | 校验错误 |

### 7.4 组件类型 → 圆角硬映射表 🔒

> 本表是 **2.0b 圆角舒适基调与 2.8 形状锁的落地**。全站圆角取值**只能**通过此表查得。v0.3.0 已整体上调，与 v0.2.0 不可混用。

| 组件类型 | 圆角 token | 值 | 说明 |
|---------|-----------|----|----|
| 表格**单元格内部** | `--radius-none` | 0 | 仅单元格内部数据区保持直角以维持可读性 |
| 表格**容器**（外框） | `--radius-lg` | 12px | 表格整体仍是圆角卡片 |
| 表单组、字段集外框 | `--radius-lg` | 12px | 容器圆角 |
| 输入框（Input/Textarea/Select 触发器） | `--radius-md` | 8px | 输入控件统一 |
| 按钮（Button） | `--radius-md` | 8px | 与输入框对齐 |
| 图标按钮（icon-only） | `--radius-md` | 8px | 与文字按钮一致 |
| 徽标 / Tag（方角变体） | `--radius-sm` | 6px | 紧凑信息块 |
| 徽标 / Tag（胶囊变体） | `--radius-full` | 9999px | 状态徽标默认胶囊 |
| 复选框 | `--radius-sm` | 6px | 小圆角方框 |
| 单选框 | `--radius-full` | 9999px | 圆形 |
| 下拉菜单项（Dropdown/Menu item） | `--radius-md` | 8px | 弹层内项（v0.3.0 从 lg 调为 md，与按钮一致更紧凑） |
| 卡片（Card） | `--radius-lg` | 12px | 内容容器默认 |
| 列表项（可选中卡片） | `--radius-lg` | 12px | 与卡片一致 |
| 对话框（Modal/Drawer） | `--radius-xl` | 16px | 大型浮层 |
| 空状态容器 | `--radius-xl` | 16px | 引导性容器 |
| 大型弹出面板 | `--radius-2xl` | 20px | AgentTraceTimeline 等 |
| 登录卡 / 首次引导大容器 | `--radius-3xl` | 24px | 营销级大圆角，营造柔和入口感 |
| 头像（Avatar） | `--radius-full` | 9999px | 圆形 |
| 滑块圆点 / 进度条 | `--radius-full` | 9999px | 圆形/胶囊 |

**三条铁律**（与 2.8 形状锁一致）：

1. **同类组件同圆角**：所有按钮 8px，所有卡片 12px，所有对话框 16px
2. **嵌套递减**：内部元素圆角 ≤ 外层容器圆角
3. **数据内部直角、容器圆角**：表格/网格的**单元格内部**用 0 圆角维持数据可读性，但表格**外框容器**仍是 12px 圆角卡片——这是"舒适"与"可读"的唯一折中点

---

## 8. 图标系统

### 8.1 图标库

**统一使用 [lucide-react](https://lucide.dev/)**（RAGFlow 当下选择，轻量、SVG、可 tree-shake）。

> 不混用 Ant Design Icons / Heroicons / Material Icons，避免风格不统一。

### 8.2 图标尺寸

| Token | 值 | 用途 |
|-------|----|----|
| `--icon-xs` | 12px | 徽标内图标 |
| `--icon-sm` | 14px | 按钮内图标、菜单项 |
| `--icon-md` | 16px | **默认**，列表项、表格操作 |
| `--icon-lg` | 20px | 卡片标题、空状态 |
| `--icon-xl` | 24px | 大空状态、导航激活 |

**规则**：图标尺寸必须是 4 的倍数，与字号阶梯对齐。

### 8.3 图标描边

- 统一 `stroke-width: 2`（lucide 默认）
- 紧凑场景（徽标）可用 `stroke-width: 1.5`
- 不用填充图标（fill），保持线性风格一致

### 8.4 图标颜色

- 跟随文本色（`currentColor`），不硬编码
- 状态图标按第 4.4 章业务状态色映射
- 禁用图标用 `--text-disabled`

### 8.5 常用图标映射（语义化）

| 语义 | 图标 | 用于 |
|------|------|------|
| 新建 | `Plus` | 新建会话 / 文档 / 团队 |
| 上传 | `Upload` / `FileUp` | 文档上传 |
| 搜索 | `Search` | 搜索框、检索意图 |
| 删除 | `Trash2` | 删除操作 |
| 编辑 | `Pencil` / `SquarePen` | 重命名、编辑 |
| 设置 | `Settings` / `Cog` | 设置入口 |
| 用户 | `User` / `Users` | 用户、成员 |
| 团队 | `Users` / `UsersRound` | 团队 |
| 文档 | `FileText` / `Files` | 文档 |
| 模型 | `Bot` / `Cpu` | AI 模型 |
| 聊天 | `MessageSquare` / `MessagesSquare` | 会话 |
| 统计 | `BarChart3` | 用量 |
| 警告 | `AlertTriangle` / `AlertCircle` | 警告提示 |
| 成功 | `CheckCircle2` | 成功提示 |
| 关闭 | `X` | 关闭弹层 |
| 更多 | `MoreHorizontal` | 行操作菜单 |
| 刷新 | `RefreshCw` | 刷新、重试 |
| 复制 | `Copy` | 复制消息 |
| 重新生成 | `RotateCcw` / `RefreshCcw` | 消息重生成 |
| 主题 | `Sun` / `Moon` | 明暗切换 |

### 8.6 加载态图标

- 旋转加载：`Loader2`（`animate-spin`）
- 进度：`LoaderCircle`
- ⚠️ 流式生成的打字光标**不用图标**，用 CSS 文本光标 `▍` 闪烁（见 9.3）

---

## 9. 动效规范

> 总原则（见 2.10）：**动效必须有动机**。本章每个动效都已标注动机；新增动效时必须能用一句话回答"它传达了什么"，答不出就删。

### 9.0 动效动机总表

| 动效 | 动机 | 出处 |
|------|------|------|
| 按钮按下 `scale(0.98)` | 触觉反馈，确认操作生效 | 10.1 |
| 弹层 fade/scale 进入 | 状态转换：从"无"到"有"的层级建立 | 9.7 |
| 流式打字光标 | 层级引导：视线锁定正在生成的内容 | 9.3 |
| Agent 推理详情展开 | 叙事：流式完成后回放 Agent 推理过程（意图/检索/工具调用） | 9.4 |
| 骨架屏 pulse | 反馈：数据正在加载，结构已就位 | 9.5 |
| 状态徽标过渡 | 状态转换：文档处理阶段的流转可见 | 9.6 |
| 页码切换、Tab 切换 | 反馈：内容已更新 | 10.13/10.19 |

> 🚫 **默认禁止的动效**：装饰性无限脉冲、永续视差、无关元素入场动画、页面滚动劫持、自定义鼠标光标。这些是 LLM 生成的常见"AI 味"动效，平台 UI 不需要。

### 9.1 时长

| Token | 时长 | 用途 |
|-------|------|------|
| `--motion-duration-instant` | 0ms | 立即 |
| `--motion-duration-fast` | 100ms | hover、色值变化、小状态切换 |
| `--motion-duration-base` | 200ms | **默认**，下拉、折叠、tab 切换 |
| `--motion-duration-slow` | 300ms | 模态、抽屉、大面板 |
| `--motion-duration-slower` | 400ms | 页面过渡、空状态进入 |

**铁律**：动效 ≤ 400ms。RAG 平台用户高频操作，长动效令人烦躁。

### 9.2 缓动

| Token | 曲线 | 用途 |
|-------|------|------|
| `--motion-ease-default` | `cubic-bezier(0.4, 0, 0.2, 1)` | 通用 |
| `--motion-ease-in` | `cubic-bezier(0.4, 0, 1, 1)` | 退出 |
| `--motion-ease-out` | `cubic-bezier(0, 0, 0.2, 1)` | 进入 |
| `--motion-ease-in-out` | `cubic-bezier(0.4, 0, 0.2, 1)` | 往返 |
| `--motion-ease-bounce` | `cubic-bezier(0.34, 1.56, 0.64, 1)` | 弹性（极少用，如验证成功） |

### 9.3 流式打字动效（聊天专属）

聊天流式生成时，光标与文字的出现方式：

**光标**：
```css
.typing-cursor::after {
  content: '▍';
  color: var(--brand-600);
  animation: blink 1s step-end infinite;
}
@keyframes blink {
  0%, 50% { opacity: 1; }
  51%, 100% { opacity: 0; }
}
```

**文字出现**：
- 新 token 追加到末尾，无逐字动画（性能优先，符合 Dify/ChatGPT 实际表现）
- 段落滚动：自动滚到底部，**用户手动上滚时暂停自动滚动**（显示"回到底部"按钮）

### 9.4 Agent 推理详情展开动效

> v0.3.1 修正：Agent 模式支持流式（与其他模式统一），不再有"阻塞等待态"。本节动效用于**流式生成完成后**，用户点击"查看推理"时 AgentTraceTimeline 的展开回放。

- 时间线逐项出现（见 11.4 AgentTraceTimeline）：每个事件按时间序动画展开，对应推理过程
- 展开时整体淡入 + 时间线节点依次弹入（200ms 间隔，bounce 缓动）
- 流式生成期间与其他模式完全一致（打字光标 + 逐字出现），不做特殊"Agent 等待"动效

### 9.5 骨架屏（Skeleton）

数据加载态用骨架屏，不用转圈 Spinner（除了按钮内）：

- 表格：行级灰条骨架（`--neutral-200` 闪烁）
- 卡片：标题 + 2-3 行内容骨架
- 列表：列表项骨架
- 闪烁动画：`pulse`（`opacity: 0.5 ↔ 1`，1.5s 循环）

### 9.6 状态徽标过渡

文档状态从 `PARSING → CHUNKING → VECTORIZING → COMPLETED` 流转时：

- 徽标颜色平滑过渡（200ms）
- 进行中态：图标 `animate-spin`（Loader / LoaderCircle）
- 完成态：图标一次性 `scale(0.8) → scale(1)` 弹入（200ms bounce）

### 9.7 弹层进入退出

- Modal：fade + slight scale（`0.95 → 1`），300ms
- Drawer：translateX 滑入，300ms
- Dropdown / Popover：fade + slight translateY（`4px → 0`），200ms
- Toast：右侧滑入，300ms；3s 后自动滑出

### 9.8 动效减弱 🔒

```css
@media (prefers-reduced-motion: reduce) {
  *, *::before, *::after {
    animation-duration: 0.01ms !important;
    transition-duration: 0.01ms !important;
  }
}
```

**所有动效必须在此媒体查询下降级为瞬态**。光标闪烁、旋转加载、骨架屏闪烁都要尊重该设置。

---

## 10. 基础组件规范

> 本章定义通用基础组件（不绑定业务语义）。每个组件给出：变体、状态、尺寸、token 引用。落地建议直接用 shadcn/ui 同名组件 + 本规范覆盖样式。
>
> 标注约定：📐 尺寸 / 🎨 颜色 token / ⚙️ 行为

### 10.0 组件交付硬规则 🔒

每个组件落地前，必须通过下列两道自查，缺一项不验收：

**(1) 交互态 5 态齐全**（见 2.9）：`default` / `hover` / `active` / `focus-visible` / `disabled`。下方每个组件的"状态"小节列出的是该组件特有的态组合，但**最低 5 态不可缺**。重点提醒：

- `focus-visible` 与 `focus` 分离：鼠标点击不显示聚焦环（避免视觉干扰），键盘 Tab 到达才显示。**禁止全局 `outline: none`**。
- `disabled` 不只是变灰——文字用 `--text-disabled`，背景 `--neutral-100`，`cursor: not-allowed`，且 `aria-disabled="true"`，键盘不可聚焦。

**(2) 数据态 3 状态齐全**（见 2.9）：`loading` / `empty` / `error`。承载数据或异步操作的组件（Table、Select、下拉、表单提交）必须各自定义这三态的表现。下文未单独列出的，按通用规则：

- `loading`：列表/表格用 Skeleton（10.18），按钮用内联 Loader2 旋转（替换文字），下拉用顶部 Loader
- `empty`：用 Empty 组件（10.20）+ 第 13.6 章对应文案
- `error`：内联错误条（`--error-50` 底 + `AlertCircle` 图标 + 说明 + 重试按钮），不阻断整页

**(3) 一处大胆，其余克制**：每个组件只允许一个"视觉记忆点"，其余保持安静。例如 Button 的记忆点是"按下时的 `scale(0.98)` 触觉反馈"，其余状态都用克制的色值过渡。

### 10.1 Button 按钮

**变体：**

| 变体 | 背景 | 文字 | 边框 | 用途 |
|------|------|------|------|------|
| `primary` | `--brand-600` | `--text-inverse` | 无 | 主操作（提交、新建、确认） |
| `secondary` | `--bg-card` | `--text-primary` | `--border-default` | 次要操作（取消、筛选） |
| `outline` | transparent | `--brand-600` | `--brand-600` | 强调但非主操作 |
| `ghost` | transparent | `--text-secondary` | 无 | 弱化操作（更多、过滤） |
| `destructive` | `--error-600` | `--text-inverse` | 无 | 删除、危险操作 |
| `link` | transparent | `--text-link` | 无 | 链接式 |

**尺寸：**

| 尺寸 | 高度 | padding | 字号 | 图标 |
|------|------|---------|------|------|
| `sm` | 28px | 8px 12px | 13px | 14px |
| `md`（默认） | 36px | 10px 16px | 13px | 16px |
| `lg` | 44px | 12px 20px | 14px | 18px |
| `icon` | 36×36px | 0 | — | 16px（纯图标按钮） |

**状态：**

| 状态 | 表现 |
|------|------|
| 默认 | 按变体 |
| hover | primary：bg `--brand-700`；secondary：bg `--bg-hover`；ghost：bg `--bg-hover` |
| active/press | primary：bg `--brand-800`；轻微 scale(0.98) |
| focus-visible | `--shadow-focus` 聚焦环 |
| disabled | bg `--neutral-100`，文字 `--text-disabled`，cursor not-allowed |
| loading | 文字替换为 `Loader2` 旋转 + 原 padding 保留，按钮禁用 |

**规则：**
- 一个视图区域最多 1 个 primary 按钮（视觉焦点）
- 图标在文字**左侧**，间距 `--space-2`
- 危险操作按钮用 `destructive`，不放主操作位
- 圆角 `--radius-md`（6px）

### 10.2 Input 文本输入

📐 高度 36px（md），padding 8px 12px，圆角 `--radius-md`

| 状态 | 边框 | 背景 |
|------|------|------|
| 默认 | `--border-default` | `--bg-input` |
| hover | `--border-strong` | `--bg-input` |
| focus | `--border-accent` + `--shadow-focus` | `--bg-input` |
| error | `--border-error` + error focus 环（error-100） | `--bg-input` |
| disabled | `--border-subtle` | `--neutral-50`，文字 `--text-disabled` |

**附属元素：**
- 前缀/后缀图标：垂直居中，距文字 `--space-2`，颜色 `--text-tertiary`
- 后缀操作（清除、密码显示）：hover 显 `--text-secondary`
- 错误提示：输入框下方 `--font-size-sm` + `--error-600`，间距 `--space-1`
- Label：输入框上方，`--font-size-md` `--font-medium` `--text-primary`，必填标 `*`（error-600）

### 10.3 Textarea 多行输入

📐 同 Input，min-height 80px，resize 竖向，圆角 `--radius-md`
- 字数计数：右下角 `--font-size-xs` `--text-tertiary`（如 `120/10000`）
- 超限计数变 `--error-600`
- 聊天输入框特殊：见 11.x

### 10.4 Select 下拉选择

基于 Radix Select。

📐 触发器同 Input（36px 高）。下拉面板：bg `--bg-card`，border `--border-default`，圆角 `--radius-lg`，shadow `--shadow-md`，max-height 300px，溢出滚动。

- 选项 padding 8px 12px，hover bg `--bg-hover`
- 选中项：左侧 `Check` 图标（`--brand-600`），文字 `--text-primary`
- 分组：组标题 `--font-size-xs` `--text-tertiary` uppercase（用于按 provider 分组的模型选择）
- 搜索：顶部 Input（用于模型多时）
- 空状态：居中文案"无匹配项"

### 10.5 Checkbox 复选框

📐 16×16px，圆角 `--radius-sm`
- 选中：bg `--brand-600`，勾 `--text-inverse`
- 未选：bg `--bg-input`，border `--border-default`
- indeterminate：bg `--brand-600`，横线
- disabled：`--neutral-100` 底
- label 在右侧，间距 `--space-2`

### 10.6 Radio 单选

📐 16×16px 圆形
- 选中：内圆 `--brand-600`，外环 border `--brand-600`
- 未选：border `--border-strong`
- 单选组用 RadioGroup（竖排间距 `--space-3`）

### 10.7 Switch 开关

📐 36×20px，圆角 `--radius-full`
- 开：bg `--brand-600`，圆点右侧
- 关：bg `--neutral-300`，圆点左侧
- disabled：bg `--neutral-100`
- 圆点 transition 200ms
- 用于 RAG 开关、思考模式开关、置顶开关

### 10.8 Badge 徽标 / Tag

📐 高度 22px（sm）/ 24px（md），padding 2px 8px，圆角 `--radius-full`（胶囊）或 `--radius-sm`（方角）
- 字号 `--font-size-xs`，`--font-medium`

**变体（与 4.4 业务状态色映射对应）：**

| 变体 | 背景 | 文字 | 边框 |
|------|------|------|------|
| `neutral` | `--neutral-100` | `--neutral-700` | 无 |
| `brand` | `--brand-50` | `--brand-700` | 无 |
| `success` | `--success-50` | `--success-700` | 无 |
| `warning` | `--warning-50` | `--warning-700` | 无 |
| `error` | `--error-50` | `--error-700` | 无 |
| `outline` | transparent | `--text-secondary` | `--border-default` |

- 业务状态徽标统一用此组件 + 第 4.4 章映射，**不另造组件**
- 可带圆点前缀（运行中态）
- 可带图标（关闭 X 用于可移除标签）

### 10.9 Card 卡片

📐 圆角 `--radius-lg`，bg `--bg-card`，border `--border-default`，shadow `--shadow-xs`
- padding 默认 `--space-4`（16px），大卡片 `--space-6`
- 可分 CardHeader（标题区，border-bottom `--border-subtle`）/ CardBody / CardFooter
- hover 卡片：shadow 升至 `--shadow-sm`，无位移
- 选中卡片：border `--border-accent` + bg `--bg-selected`

### 10.10 Table 表格

📐 行高 48px（默认）/ 40px（紧凑），表头 44px，cell padding 12px 16px

- 表头：bg `--bg-base`，文字 `--font-size-sm` `--font-medium` `--text-secondary`，左对齐（数字列右对齐）
- 行：hover bg `--bg-hover`
- 选中行：bg `--bg-selected`
- 边框：行间 `--border-subtle` 1px（仅水平线，无竖线）
- 圆角 `--radius-none`（数据展示）
- 排序：表头排序图标，激活 `--brand-600`
- 空表格：跨列居中空状态
- 长内容：ellipsis + Tooltip
- 状态列：用 Badge 组件
- 操作列：右对齐，`MoreHorizontal` 行菜单或文字按钮组
- 💡 实现：`@tanstack/react-table`（RAGFlow 同款）

### 10.11 Modal 对话框 🔒

> Modal 是 **2.0c「破坏操作强制弹窗」基调的承载组件**。所有删除/解散/移除/拒绝/清空类操作必须经此组件二次确认，不允许"点即删"或 Toast 直接执行。

📐 圆角 `--radius-xl`（16px），bg `--bg-card`，shadow `--shadow-lg`，max-width 480px（sm）/ 600px（md）/ 800px（lg）
- 遮罩：bg `rgba(16,24,40,0.6)`，点击遮罩默认**不关闭**（防误触，破坏性操作尤其重要）
- Header：标题 `--font-size-lg` `--font-semibold`，右上角 `X` 关闭按钮
- Body：padding `--space-6`
- Footer：右对齐按钮组，主按钮最右
- 进入：fade + scale(0.95→1)，300ms
- Esc 键可关闭（破坏性确认弹窗也允许 Esc 取消，等于点"取消"）

#### 10.11.1 破坏性确认弹窗（ConfirmDialog）🔒 必选规范

这是平台**最关键的交互模式之一**，所有破坏操作统一走此变体：

📐 尺寸 480px（sm，固定窄宽度聚焦注意力）

```
┌─────────────────────────────────────┐
│  ⚠️ 删除文档?              [X]       │  ← 警示图标 + 动作名（带"?"）
│                                     │
│  文档"report.pdf"及其向量数据将被     │  ← 明确后果说明
│  永久删除，此操作不可撤销。           │     （点名具体对象 + 不可逆提示）
│                                     │
│              [取消]  [删除]          │  ← 取消(secondary) + 确认(destructive)
└─────────────────────────────────────┘
```

**必备要素**：

| 要素 | 要求 |
|------|------|
| 警示图标 | 标题左侧 `AlertTriangle`（`--warning-600`）或 `Trash2`（`--error-600`），视严重程度 |
| 标题 | 动作名 + `?`，如"删除文档?""解散团队?"（与 13.7 操作确认文案表一致） |
| 后果说明 | **点名具体对象**（如文档名、团队名）+ 说明**不可逆性**（"此操作不可撤销"） |
| 取消按钮 | secondary 变体，文字"取消"，左侧，默认聚焦（按 Enter = 取消，防误删） |
| 确认按钮 | **destructive** 变体（红色 `--error-600` 底 + 白字），文字用动作动词（"删除""解散""移除"），右侧 |
| 遮罩 | 点击不关闭（防误触通过遮罩点掉） |

**禁止**：
- ❌ 不带后果说明的确认弹窗（如只写"确定删除?"）
- ❌ 确认按钮用 primary 蓝色（破坏操作必须用 destructive 红色，与主操作区分）
- ❌ 默认聚焦确认按钮（应默认聚焦取消，Enter 优先取消）
- ❌ 用 Toast 替代弹窗做破坏操作

完整操作确认文案见 13.7。

### 10.12 Drawer 抽屉

📐 宽度 400px（sm）/ 480px（md），从右侧滑入
- 遮罩同 Modal
- Header / Body / Footer 同 Modal
- 用于文档详情、成员详情等不打断主流程的详情查看

### 10.13 Tabs 标签页

📐 标签高度 40px，下划线 2px
- 激活：文字 `--text-primary` `--font-medium`，下划线 `--brand-600`
- 未激活：文字 `--text-secondary`
- hover 未激活：文字 `--text-primary`
- 内容区上方留 `--space-4`
- 用于设置页、详情页分组（如文档详情：基本信息 / 历史 / 状态）

### 10.14 Toast 全局提示

📐 右上角 / 右下角浮出，圆角 `--radius-lg`，shadow `--shadow-lg`，min-width 320px
- padding 12px 16px，左侧图标 + 内容
- 自动消失：3s（success）/ 5s（warning、error）/ 不自动（info 待关）
- 同时最多 3 条，超出堆叠

| 类型 | 图标 | 色条（左侧） |
|------|------|-------------|
| success | `CheckCircle2` | `--success-600` |
| warning | `AlertTriangle` | `--warning-600` |
| error | `AlertCircle` | `--error-600` |
| info | `Info` | `--info-600` |
| loading | `Loader2` 旋转 | `--brand-600` |

- 标题 `--font-size-md` `--font-medium`，描述 `--font-size-sm` `--text-secondary`
- 带"重试"操作按钮（用于可重试错误）

### 10.15 Tooltip 气泡提示

📐 黑底（`--neutral-900`）/ 白字，圆角 `--radius-sm`，padding 6px 8px，`--font-size-xs`
- 延迟出现 500ms（防闪烁）
- 箭头指向触发元素
- max-width 240px，长文本换行
- 不承载关键信息（鼠标 / 键盘不可见），仅辅助说明

### 10.16 Avatar 头像

📐 24px（xs）/ 32px（sm）/ 40px（md）/ 48px（lg）/ 64px（xl）
- 圆形 `--radius-full`
- 有图片：cover 填充
- 无图片：bg `--brand-50`，文字首字母（`--brand-700`），字号按尺寸缩放
- 支持 group（团队成员叠放，最多 +N）

### 10.17 Progress 进度条

📐 高度 8px（md）/ 4px（sm），圆角 `--radius-full`
- 轨道：bg `--neutral-100`
- 填充：bg `--brand-600`
- 文档处理进度：填充色按状态变（vectorizing 用 `--info-600`）
- 可带百分比文字（右侧 `--font-size-xs`）
- 不确定态（无具体百分比）：填充条横向滚动动画

### 10.18 Skeleton 骨架屏

📐 bg `--neutral-200`，圆角 `--radius-sm`，pulse 动画
- 文本：高度 12px，宽度按内容估算
- 标题：高度 16px
- 头像：圆形
- 卡片：组合上述
- 表格行：多个文本条堆叠

### 10.19 Pagination 分页

📐 高度 32px，按钮 32×32px，圆角 `--radius-md`
- 上一页 / 下一页：`ChevronLeft` / `ChevronRight` 图标按钮
- 页码：数字按钮，当前页 bg `--brand-600` 文字 inverse，其余 hover `--bg-hover`
- 省略号：`...`
- 每页条数选择：Select（10/20/50/100）
- ⚠️ **size 上限因端点而异**：全局 `PageRequest.MAX_PAGE_SIZE=100`（文档/审批/团队成员等接口会被 clamp 到 100），但**会话列表** `GET /api/conversations` 的 `size` 是 `@Max(500)` 默认 50——可超过 100。前端"每页"选项统一不超过 100 即可（会话列表传更大值后端也接，但其他接口会被 clamp，建议统一 100 上限避免踩坑）
- 总数显示：左侧"共 N 条"

### 10.20 Empty 空状态

📐 居中，padding `--space-12`
- 图标 `--icon-xl`（48px），颜色 `--neutral-300`
- 标题 `--font-size-lg` `--font-semibold` `--text-primary`
- 描述 `--font-size-md` `--text-secondary`
- 行动按钮（可选）：引导用户操作
- 每个模块空状态文案见第 13 章

### 10.21 Dropdown 下拉菜单

基于 Radix DropdownMenu。
📐 面板 bg `--bg-card`，border `--border-default`，圆角 `--radius-lg`，shadow `--shadow-md`，min-width 160px
- 菜单项 padding 8px 12px，hover bg `--bg-hover`
- 危险项：文字 `--error-600`
- 分组分隔：1px `--border-subtle`
- 图标 + 文字，间距 `--space-2`
- 禁用项：文字 `--text-disabled`

### 10.22 Form 表单

📐 标签在上，字段在下；字段间距 `--space-4`（垂直）
- Label：`--font-size-md` `--font-medium` `--text-primary`，必填 `*`（error-600）
- 帮助文本：label 下方 `--font-size-sm` `--text-tertiary`
- 错误：字段下方 `--font-size-sm` `--error-600`（含图标 `AlertCircle`）
- 提交校验：失焦校验 + 提交校验，错误时输入框 `--border-error`
- 提交按钮：表单底部右对齐（取消 + 提交）
- 💡 实现：`react-hook-form` + `zod`（与后端校验注解对齐）

---

## 11. 业务复合组件规范

> 本章是平台规范的**核心差异点**。这些组件绑定 Smart RAG 后端契约，普通 UI 库不提供，必须按本规范实现。
>
> 每个组件给出：契约字段、状态、变体、交互、token 引用。组件名前缀 `Srag`（Smart RAG 业务组件命名空间），避免与基础组件混淆。

### 11.1 SliderCaptcha 滑块验证码

**契约**：`GET /api/auth/captcha` → `CaptchaResult { captchaId, backgroundImage(base64 PNG), puzzleImage(base64 PNG), answer?(dev only) }`

> **缺口位置约定**：缺口 **y 固定居中**（`answerY = (155-47)/2 = 54`），**x 随机**（安全边界——bot 必须图像识别才能定位）。前端实现要点：
> - puzzleImage `top` 写死 `39px`（= `answerY - padding(15)`），用户只水平拖动
> - `captchaCode = puzzleImage.left + 15`（补偿 padding，对齐到拼图块左边缘，与后端 answerX 语义一致）
> - 容差 ±5px，无需精确对齐

**用途**：登录、注册前置验证。

📐 布局（宽 320px，高 ~200px）：
```
┌────────────────────────────────┐
│   [背景图 310×155 含缺口]        │  ← backgroundImage，缺口 y 固定居中、x 随机
│   [拼图小块 ← 仅水平跟随滑块]    │  ← puzzleImage，top 固定 39px，left 随滑块
├────────────────────────────────┤
│ [滑轨] ▶ 向右拖动完成验证 [■]    │  ← 滑块轨道，宽 310，高 40
└────────────────────────────────┘
```

**状态：**

| 状态 | 表现 | 触发 |
|------|------|------|
| 待验证 | 滑块在左，文案"向右拖动滑块完成验证"，轨道 `--neutral-100` | 初始 |
| 拖拽中 | 拼图块跟随滑块 x 位移，轨道填充 `--brand-50` | 按住拖动 |
| 验证中 | 滑块禁用，Loader 旋转 | 松手提交（POST 时带 captchaCode=x坐标） |
| 成功 | 滑块到右，轨道变 `--success-600`，显示 `CheckCircle2` + "验证成功" | 后端确认（登录/注册成功即隐含） |
| 失败 | 拼图抖动回弹左侧，轨道闪红，文案"验证失败，请重试" | 校验失败 |
| 过期 | 文案"验证码已过期，点击刷新"，`RefreshCw` 按钮 | captchaId 超时 |

**交互：**
- 拖拽：鼠标按下 + 移动 + 松手；触摸支持
- 滑块 x 坐标即 `captchaCode`（整数像素值），提交时与 `captchaId` 一起发给登录/注册接口
- ⚠️ dev 环境后端返回 `answer`（正确 x 坐标），可用于自动化测试；生产为 null
- 验证失败后自动刷新获取新 captcha（避免重复用同一 captchaId）
- 提供刷新按钮（右上角小图标），手动换图

### 11.2 ModelSelector 模型选择器

**契约**：`GET /api/models/detail` → `List<ModelVO>`，每项 `{ id, provider, model, capability, available }`（`chat/dto/ModelVO.java`）：
- `id` — 候选唯一标识（请求 `/api/chat` 时传此值）
- `provider` — 供应商 ID（`deepseek`/`zhipu`/`MiniMax` …），前端用它**分组**（无需再维护前缀映射表）
- `model` — 原始模型名（发给 LLM API 的）
- `capability` — `CHAT`/`EMBEDDING`/`RERANKING`
- `available` — 是否可用（未被运行时禁用）

> **能力可见性**：本端点对普通用户（`chat:send`）**强制只返 CHAT**（后端按用途分流，非 CHAT 需 `model:config` 权限）。普通用户的 ModelSelector 永远只看到对话模型，Embedding/Rerank 仅管理界面可见。旧的 `GET /api/models`（返回 `List<String>` 仅 CHAT id）仍保留但无 provider 信息——新前端一律用 `/models/detail`。

**用途**：聊天模式选择、会话创建、Agent 配置（均为 CHAT 语境）。

📐 触发器：Select 样式（36px 高），显示当前模型 ID + provider 小标签。
📐 下拉面板（宽 280px）：
```
┌────────────────────────────────┐
│ 🔍 搜索模型...                  │
├────────────────────────────────┤
│ DeepSeek                        │  ← provider 分组标题（取 ModelVO.provider）
│   ◉ deepseek-v4-flash           │
│     DeepSeek · 推荐              │
│   ○ deepseek-reasoner           │
├────────────────────────────────┤
│ 智谱 AI                         │
│   ○ glm-5.1                     │
├────────────────────────────────┤
│ MiniMax                         │
│   ○ MiniMax-M2.1                │
└────────────────────────────────┘
```

**分组规则**：直接用后端返回的 `ModelVO.provider` 字段分组，**无需前端维护前缀映射表**（旧设计因后端只返 ID 字符串而存在，现后端已提供 provider 字段，该映射表废弃）。

**校验提示**（关键）：
- ⚠️ 后端 fail-fast：`model` 字段**禁止包含 `/`**（如 `deepseek/deepseek-chat`），否则返回 400 + code 100001
- 前端 ModelSelector 只暴露 candidate ID（`ModelVO.id`），**不暴露 provider/modelId 复合形式**
- 若输入框手动输入带 `/` 的值，前端立即拦截并提示"请从下拉选择模型"
- ⚠️ 后端校验能力：传非 CHAT 能力的 candidateId（如 embedding 模型 id）会被拒绝，返回 code **103004**「所选模型不支持对话，请选择 CHAT 能力模型」。这是后端真安全边界，前端隐藏非 CHAT 只是体验优化

### 11.3 ChatMessageBubble 聊天消息气泡 ⭐

**契约**：
- 发送：`POST /api/chat`（阻塞，返回完整 `ChatResponse`）/ `POST /api/chat/stream`（SSE 流式）
- 消息：`MessageVO { id, parentId, role, content, status, modelId?, thinkingEnabled?, tokenUsage, durationMs?, createdAt, children[] }`
  - ⚠️ `tokenUsage` 是**单个 Integer（总 token 数）**，无 prompt/completion 拆分——元信息行的 `↑prompt ↓completion` 无法仅凭此字段渲染（见 11.3.7 注）
  - 字段名是 `thinkingEnabled`（`MessageVO`），请求侧是 `enableThinking`（`ChatRequest`）——序列化后前端收到的是 `thinkingEnabled`
- RAG 引用：`List<Reference> { refNumber, chunkId, documentId(String), fileName, page?, score, source?, content? }`
- Agent 元数据：`Map { intent?, confidence?, retrievalRounds?, agentDegraded?, degradedTo? }`——阻塞式随 `ChatResponse.agentMetadata` 返回；流式由 `event:agentMetadata` 终端帧携带（见下 SSE 帧结构）
- 降级：`FallbackMeta { requestedModel, fallback }`——阻塞式随 `ChatResponse.fallback` 返回；流式由 `event:fallback` 终端帧携带

**⚠️ SSE 流式接入（关键约束）**：

1. **必须用 `fetch` + `ReadableStream` 手动解析，禁用浏览器原生 `EventSource`**。原因：`/api/chat/stream` 是 **POST + `@RequestBody`（JSON）**，而 `EventSource` 只支持 GET 且无法设 body/自定义 header。Token 在 HttpOnly Cookie 里，fetch 需带 `credentials: 'include'`。
2. **SSE 帧结构（七类）**：
   | 帧 | SSE 输出 | 含义 | 前端处理 |
   |---|---|---|---|
   | 内容帧 | `data:{chunk}\n\n`（**无 `event:` 行**） | 模型输出的文本片段 | 累加到当前消息 content，末尾接打字光标 |
   | reasoning 帧 | `event:reasoning\ndata:{思考片段}\n\n` | 推理过程（思考模型，enableThinking 开启时） | 渲染到可折叠的"思考过程"区 |
   | references 帧 | `event:references\ndata:[{...Reference}]\n\n` | RAG 引用列表（终端帧，仅 RAG 有引用时） | 解析 JSON，渲染引用脚注区 |
   | agentMetadata 帧 | `event:agentMetadata\ndata:{intent,confidence,retrievalRounds,...}\n\n` | Agent 元数据（终端帧，仅 AGENT 模式） | 渲染 11.3.5 元信息条 |
   | fallback 帧 | `event:fallback\ndata:{requestedModel,fallback}\n\n` | 跨模型降级信号（终端帧，发生降级时） | 渲染 11.3.6 降级提示 |
   | canceled 帧 | `event:canceled\ndata:{reason}\n\n` | 软取消终止帧（点击"停止"触发） | 终止流，保留已生成内容 |
   | error 帧 | `event:error\ndata:{error,message,attempted}\n\n` | 结构化流式失败 | 显示错误卡片 + 重试 |
   > ⚠️ 内容帧**没有 `event:` 名**，很多 SSE 库默认按 event 名路由、对未命名事件处理不一——务必用底层流解析（读 `data:`/`event:` 行），不要依赖"按 event 名分发"的高层封装。
   > 📐 终端帧（references/agentMetadata/fallback）仅在 content 流正常 complete 后发送；canceled 帧在软取消时**替代**上述终端帧发送（取消不发 references/agentMetadata/fallback）。
3. **流式元数据已就绪（G3 已解决）**：`SseStreamBridge` 现已发 `event:agentMetadata` / `event:fallback` / `event:references` 终端帧（见上表），AGENT 模式流式完成后可直接渲染 11.3.5 元信息条与 11.3.6 降级提示。
   - 📌 **`durationMs` / `tokenUsage` 流式当下不展示（已决策）**：SSE 不发这两项，前端**不自行计时、不估算 token**。流式生成（含当次会话刚完成）的元信息行**只显示 `模型 · 时间`**；重新加载会话后从历史 `MessageVO`（带这两字段）读取时再展示完整 `模型 · token · 耗时 · 时间`（见 11.3.7）。

**这是平台最复杂的组件**，需同时支持流式态、Agent 态、错误态、分支态。

> v0.3.1：三模式（SIMPLE/MULTI_TURN/AGENT）统一走流式 UX，无独立"阻塞态"。Agent 模式差异仅在完成后多一个可展开的 agentMetadata 条（11.3.5）——流式下由 `event:agentMetadata` 终端帧提供数据（见上 #3）。

📐 通用结构：
```
[Avatar]            消息流（无气泡背景，纯排版）
  USER/ASSISTANT     正文内容
                     ── 引用脚注区 [1][2]（RAG 时）
                     ── Agent 元数据条（Agent 模式）
                     ── 操作栏（hover 显示）：复制 / 重生成 / 分支
                     ── 元信息行：模型 · token · 耗时 · 时间
```

> 风格选择：**无气泡背景**（Dify/ChatGPT 风格，用户与助手仅靠 avatar + 对齐区分），而非传统左右气泡。理由：长答案、代码块、引用在气泡内排版局促。

#### 11.3.1 角色（role）

| role | Avatar 位置 | 对齐 | 表现 |
|------|------------|------|------|
| `USER` | 右侧 | 右对齐 | 当前用户头像；正文 bg `--bg-base`，圆角 `--radius-lg`，padding `--space-3`（轻气泡，仅短消息） |
| `ASSISTANT` | 左侧 | 左对齐 | 模型/Bot 头像；正文无背景，全宽排版（markdown 渲染） |

#### 11.3.2 内容渲染（content）

- Markdown 渲染（`react-markdown` + `remark-gfm`）
- 代码块：`react-syntax-highlighter`，暗色主题，含复制按钮、语言标签
- 数学公式：`katex`（如有）
- 表格、列表、引用块正常渲染
- ⚠️ 超长内容：`overflow-wrap: anywhere`（URL、stack trace）
- 流式态：末尾光标 `▍`（见 9.3），新 token 追加无逐字动画

#### 11.3.3 状态（status）

| status | 表现 |
|--------|------|
| `IN_PROGRESS` | 末尾闪烁光标；底部"生成中"小字 + Loader |
| `FINISHED` | 无光标；底部元信息完整显示 |
| `ERROR` | 内容区显示错误卡片（`--error-50` 底，`AlertCircle` 图标，错误文案 + 重试按钮） |

#### 11.3.4 引用脚注（references）— RAG 模式

正文中的 `[1]` `[2]` 渲染为上标链接（`--text-link`，可点击）。
答案下方显示**引用来源区**：

```
─── 引用来源 ───
[1] report.pdf · 第3页   混合检索 0.87   ▸  [展开片段]
[2] manual.pdf           向量检索 0.82   ▸  [展开片段]
```

- 每条引用：序号（`--brand-600` 圆形）+ fileName + 可选页码 + 来源 Tool（source，中文映射）+ 相关性得分（score）+ 展开按钮
- 点击正文 `[1]` → 滚动到对应引用卡并高亮
- "展开片段"：卡片内 `content` 是截断预览，点击展开按钮调 `GET /api/chunks/{chunkId}` 取全文 `ChunkDTO.content`（见 11.8）。source/score 为 null 时（agent 路径常见）隐藏对应行，仅保留文件名 + 页码

#### 11.3.5 Agent 元数据条（agentMetadata）— Agent 模式

> v0.3.1 修正：Agent 模式**支持流式**（`AgentModeStrategy.java:298` 实现 executeStream），与 SIMPLE/MULTI_TURN 统一走流式 UX。
>
> ✅ **G3 已解决**：`agentMetadata` 阻塞式随 `ChatResponse` 返回，流式由 `SseStreamBridge` 的 `event:agentMetadata` 终端帧携带（content 流正常 complete 后发送）。AGENT 模式流式完成后直接渲染本条。降级提示同理走 `event:fallback` 终端帧（11.3.6）。

```
🤖 Agent · 意图: 深度检索(0.92) · 检索 3 轮 · 用时 8.2s  ▸ 查看推理
```

- 图标 `Bot` + "Agent"
- 意图 Badge（按 AgentIntent 映射，4.4.6）+ 置信度
- 检索轮数（retrievalRounds）
- ⚠️ 降级提示（agentDegraded=true）：橙色警告条"Agent 已降级为普通多轮对话（原因：超时/失败）"
- **"查看推理"入口**：流式生成期间只显示普通流式光标（与其他模式一致），完成后这条元信息出现，点击展开右侧 AgentTraceTimeline（11.4）回放推理过程

#### 11.3.6 降级提示（fallback）

非 Agent 模式也可能降级（模型故障 → 切备用模型）。降级时消息底部小字：

```
⚠️ 已从 deepseek-v4-flash 降级到 qwen-plus（备用链）
```

- `--warning-600` 文字，`AlertTriangle` 图标
- 折叠态，点击展开详情

#### 11.3.7 元信息行（FINISHED 态）

```
deepseek-v4-flash · ↑120 ↓80 · 1.5s · 2026-06-20 14:30
```

- modelId（`--text-tertiary`，mono 字体）
- token：仅显示总数（`--text-tertiary`，如 `200 token`）
  > ⚠️ 后端 `MessageVO.tokenUsage` 是**单 Integer（总 token 数）**，无 prompt/completion 拆分——元信息行只显示总数，不渲染 `↑prompt ↓completion` 拆分。
- 耗时 durationMs → 转秒（`--text-tertiary`）
- 时间（`--text-tertiary`，按第 13 章格式化）
- 整行 `--font-size-xs`，分隔符用 `·` 间隔

> 📌 **流式当下不展示 token / 耗时（已决策）**：SSE 不发 `tokenUsage` / `durationMs`，前端**不自行计时、不估算 token**。流式生成期间及当次会话刚完成的消息，元信息行**只显示 `模型 · 时间`**，省略 token 与耗时两项；重新加载会话后，从历史 `MessageVO`（带 `tokenUsage` / `durationMs`）读取时再完整展示上述四段。

#### 11.3.8 分支导航（children / parentId）

⚠️ 后端消息是树形（parentId + children，支持重生成分支）。消息底部显示分支切换器（仅当有多 children 时）：

```
  └─ 2/3 ── ◀ ▶        ← 同级兄弟分支切换
```

- 当前分支序号 / 总数
- 左右箭头切换查看不同重生成结果
- 父消息上的"重新生成"按钮会创建新 child 分支

#### 11.3.9 操作栏（hover 显示）

| 操作 | 图标 | 条件 |
|------|------|------|
| 复制 | `Copy` | 所有 ASSISTANT 消息 |
| 重新生成 | `RotateCcw` | ASSISTANT 消息（创建新分支） |
| 编辑 | `Pencil` | USER 消息（编辑后重新发送，创建新分支） |
| 删除 | `Trash2` | 自己的消息（二次确认） |
| 反馈（赞/踩） | `ThumbsUp`/`ThumbsDown` | 可选，留接口 |

### 11.4 AgentTraceTimeline Agent 推理时间线 ⭐

**契约**：⚠️ **v0.3.1 修正**——Agent 模式支持流式（与 SIMPLE/MULTI_TURN 统一），`agentMetadata` 随流式最终响应返回。

> **完整事件流（6 种 AgentEventType）后端已持久化（`agent_session_event` 表 + `AgentEventStore`），但目前仅暴露**管理员侧**端点：`GET /api/admin/agent-events?sessionId=...`（需 `trace:view` 权限）。面向**普通用户**的会话级事件历史端点（如 `GET /api/agent/sessions/{id}/events`）**尚未提供**，当前迭代**不开发**——本组件按"预留"处理。
>
> 因此本组件当前版本**在流式完成后，从最终响应的 agentMetadata 渲染汇总**；完整时间线（6 事件逐项回放）留作"后端补用户态端点后启用"的占位规范。下面给出完整规范，待端点就绪即可实现。

📐 右侧抽屉 / 面板，宽 360px，从消息流右侧滑入。**触发**：流式生成完成后，点击消息底部的"查看推理"入口（11.3.5）。

**汇总视图（当前可实现）：**
```
┌─────────────────────────────┐
│ Agent 推理过程                │
│                             │
│ ✅ 意图识别                   │
│    深度检索 · 置信度 0.92     │
│                             │
│ 🔍 检索 3 轮                  │
│    混合检索 · 4 子查询         │
│                             │
│ 🤖 工具调用 5 次              │
│    hybridSearch ×3           │
│    rerank ×1 · docDetail ×1  │
│                             │
│ ⚙️ 反思 2 次                  │
│    相关性 0.8 · 完整性 0.7    │
│                             │
│ 总计: 1.2万 token · 8.2s     │
└─────────────────────────────┘
```

**完整时间线视图（待用户态端点，当前不开发）：** 按 AgentEventType 6 类（4.4.11）从上到下时间序展示，每项：

| 事件 | 展示内容（来自 payload） |
|------|-------------------------|
| INTENT_CLASSIFIED | 意图 Badge + 置信度进度条 |
| RETRIEVAL_STRATEGY | 策略 Badge（hybrid/vector/keyword）+ 子查询列表 |
| TOOL_CALLED | 工具名 + 输入参数（可折叠 JSON）+ 结果文档数 + 耗时 + 成功/失败 |
| SELF_REFLECTION | 相关性 / 完整性双进度条 + 建议 Badge（need_more_retrieval/sufficient） |
| INTERMEDIATE_ANSWER | 子查询 + 中间答案 + 引用文档数 |
| GUARDRAIL_TRIGGERED | 守门名 + 原因 + 动作 Badge（stop/degrade/retry，红色高亮） |

**视觉：** 竖向时间轴，左侧圆点（按事件优先级着色 CRITICAL 红 / HIGH 橙 / NORMAL 灰），连线 `--border-subtle`。流式完成后展开时逐项动画回放（9.4）。

**空态（agentMetadata 为空）：** 显示骨架 + "Agent 推理中…" 旋转。

### 11.5 DocumentUploadCard 文档上传卡

**契约**：`DocumentDTO { id, fileName, fileSize, mimeType, chunkCount?, status, errorMessage?, userId, teamId?, version?, supersededBy?, documentGroupId?, createTime }`，`EtlStatus` 11 值。

📐 卡片式（用于网格视图）或行式（用于表格视图），两种渲染模式共用状态逻辑。

**卡片模式布局：**
```
┌─────────────────────────────────┐
│ 📄 report.pdf              ⋮    │  ← 文件名 + 行菜单
│ PDF · 1.2 MB                    │  ← 类型 + 大小
│                                 │
│ [状态徽标] 42 块                 │  ← Badge + chunkCount
│ ━━━━━━━━━━━━ 65%                │  ← 进行中时进度条
│                                 │
│ 2026-06-20 14:30    [操作]      │
└─────────────────────────────────┘
```

**按状态渲染（核心，对照 4.4.1）：**

| status | 卡片表现 | 操作 |
|--------|---------|------|
| UPLOADED | neutral Badge "已上传"，无进度 | 取消上传（删除） |
| PENDING_APPROVAL | warning Badge "待审批"，无进度 | 查看审批（团队） |
| PARSING/CHUNKING/VECTORIZING/PROCESSING | info Badge（旋转图标）+ 进度条（不确定态，无具体%） | 取消 |
| COMPLETED | success Badge "已完成" + chunkCount | 删除 / 查看详情 |
| FAILED/VECTOR_FAILED | error Badge + errorMessage（Tooltip 或展开） | **重试** + 删除 |
| REJECTED | error Badge "已拒绝" + reviewComment | 删除 |
| SUPERSEDED | neutral 弱化 Badge "已替代"，卡片整体 `opacity: 0.6` | 查看新版本（跳转） |

**文件类型图标**（按 mimeType）：

| 类型 | 图标 | 色 |
|------|------|----|
| PDF | `FileText` | `#E11D48`（红） |
| Word | `FileText` | `#2563EB`（蓝） |
| Excel | `FileSpreadsheet` | `#16A34A`（绿） |
| PPT | `Presentation` | `#EA580C`（橙） |
| 图片 | `FileImage` | `#7C3AED`（紫） |
| 文本/Markdown | `FileText` | `--neutral-500` |
| 其他 | `File` | `--neutral-500` |

**版本链**（version / supersededBy）：卡片可展示"v2"版本标，点击查看历史（`GET /api/documents/{id}/history`）。

### 11.6 ChunkUploadProgress 分片上传进度

**契约**：分片上传 init `ChunkUploadResult { uploaded, uploadId?, chunkSize?, totalChunks?, uploadedChunks?, documentId? }`，逐片 `ChunkUploadResponse`。

📐 用于大文件（> 5MB）上传时的进度展示，覆盖秒传、续传、并发上传。

**三态：**

**秒传（uploaded=true）：** Toast "文件秒传成功（已存在）" + 直接进入 COMPLETED 卡片。

**新建（uploaded=false, uploadedChunks 为空）：**
```
┌─────────────────────────────────┐
│ 📄 bigfile.zip                  │
│ 并发上传中 5/10 分片              │
│ ▓▓▓▓▓░░░░░░░░░ 50%              │
│ ↑ 2.5 MB/s · 剩余 8s             │
│                      [取消]      │
└─────────────────────────────────┘
```

**续传（uploadedChunks 非空）：** 进度条从已传分片处开始，文案"断点续传中"。

- 进度：已完成分片数 / totalChunks
- 速度 / 剩余时间：前端计算
- 并发：前端控制 3-5 并发上传分片
- 每片需算 MD5（`X-Chunk-MD5` header），上传前计算
- 取消：调用 `POST /{uploadId}/delete`，清理后端 session
- 失败单片：自动重试 3 次，超限整体失败

### 11.7 ConversationTree 会话树 / 分支导航

**契约**：消息 `parentId` + `children[]` 构成树，支持重生成分支。消息加载走**游标分页**：

- `GET /api/conversations/{conversationId}/messages?limit=20&before={根消息id}` → `MessageCursorPage { items: List<MessageVO>, nextCursor: Long, hasMore: boolean }`
- 分页方向：从最新向最早翻（时间倒序加载），`items` 内部仍按时间升序排列便于正序渲染
- `nextCursor` = 本页最早的**根消息 id**（USER 消息，轮次粒度），`null` 表示已到最早
- limit @Max(50)，默认 20
- `GET /api/conversations/{id}`（会话详情）也返回 `ConversationDetail`（含首批消息），但翻历史用上面的 messages 端点

📐 用于消息流中的分支切换（见 11.3.8）。分支较多时展示简化树：

```
用户: 文档讲了什么?
  └─ [回复1] (当前) ── ◀ 1/2 ▶
  └─ [回复2]
```

- 当前分支高亮（`--brand-600` 左侧线）
- 切换：左右箭头或点击分支节点
- ⚠️ 后端仅返回一层 children，深层分支需懒加载（点开展开节点）
- 父子缩进：每级 `--space-6`

### 11.8 ReferenceCard 引用来源卡

**契约**：`Reference { refNumber, chunkId, documentId, fileName, page?, score, source?, content? }`（`mode/Reference.java`，agent + chat 双路径统一）

📐 用于 ChatMessageBubble 的引用来源区（11.3.4）和 AgentTraceTimeline 的检索结果展示。

单卡布局：
```
┌─────────────────────────────────┐
│ [1]  report.pdf · 第3页          │  ← 序号圆 + 文件名 + 页码
│ 来自: hybridSearch · 0.87        │  ← source（来源 Tool）+ score（相关性）
│ "片段文本预览..."                │  ← content（截断的 chunk 内容，可空）
│                          [跳转]  │  ← 跳转文档详情
└─────────────────────────────────┘
```

- 字段说明（全部来自 `Reference`）：
  - `refNumber` — 稳定编号 [n]，与正文脚注一致
  - `chunkId` — vector_store UUID，点击"查看完整片段"调 `GET /api/chunks/{chunkId}` 取全量 content（见下）
  - `documentId` / `fileName` / `page` — 文档定位
  - `score` — 相关性得分（向量相似度 / RRF 融合分 / rerank 分，取决于检索路径）。前端据此排序、高亮、置信度展示
  > 📌 **score 展示策略（已决策：归一化前原值展示）**：当前管线 `rrfScore (~0.033)` 与 `rerankScore (~1.0)` 量纲差约 30 倍、末端无质量门，分数跨检索路径**不可直接横向比较**。前端决策：**按后端原值展示**，不做前端归一化计算。后果：同一批引用内不同来源的绝对数字可能不具可比性——这是已知取舍。待后端 [`docs/design/rag-score-normalization-cutoff.md`](../design/rag-score-normalization-cutoff.md)（归一化 + 末端截断）落地后，score 才具备跨路径可比的排序/置信度意义，届时前端零改动即获得可比分数
  - `source` — 检索来源 Tool 名（如 `hybridSearch` / `vectorSearch`），前端映射为中文（混合检索 / 向量检索）。**可空**——agent 路径未参与打分时为 null
  - `content` — 截断的 chunk 内容预览，**可空**（agent 路径在注入 prompt 后未单独保留）
- **完整片段查看**：卡内 content 是截断预览，点击"查看完整片段"调 `GET /api/chunks/{chunkId}` → `ChunkDTO { id, content, documentId, fileName, metadata }` 取全文展开（归属校验复用文档权限逻辑）
- source 为 null 时隐藏"来自"行；content 为 null 时隐藏预览区仅保留文件名行（agent 路径常见）

### 11.9 ApprovalCard 审批卡

**契约**：`ApprovalVO { id, documentId, fileName, fileSize, uploaderId, uploaderName, status, reviewerId?, reviewComment?, createdAt, reviewedAt? }`，`ApprovalStatus` 3 值。

> ⚠️ **后端待补（A2）**：`ApprovalVO` 只有 `reviewerId`（Long），**无 `reviewerName`**。下方"已审批态"示例里"审批人: admin"无法直接渲染——要么推动后端 join 用户表补 `reviewerName`，要么前端额外查用户（不推荐，N+1）。当前文档示例按"后端补字段后"的预期写。

📐 用于团队审批列表（管理员视角）和我的审批（上传者视角）。

**管理员视角（待审批）：**
```
┌─────────────────────────────────┐
│ 📄 report.pdf · 1.2 MB          │
│ 上传者: alice · 2026-06-20 14:30 │
│                                 │
│              [拒绝]  [通过 ✓]    │  ← destructive / primary
└─────────────────────────────────┘
```

- 点击"通过"或"拒绝"后调 `POST /api/teams/{teamId}/approvals/{id}/review`，请求体 `ApprovalReviewRequest { action, comment }`：
  - `action`（**必填**）：`"APPROVE"` 或 `"REJECT"`（决定通过/拒绝，**非** boolean）
  - `comment`（可选）：审批备注，max 512
- 通过 → 文档进入 ETL；拒绝 → uploader 收到通知

**已审批态（PENDING 之外）：**
```
┌─────────────────────────────────┐
│ 📄 report.pdf                   │
│ 上传者: alice · 2026-06-20      │
│ [已通过 Badge] 审批人: admin     │
│ 备注: 内容合规                   │
└─────────────────────────────────┘
```

- status Badge 按 4.4.4
- reviewerName（⚠️ 后端待补，见本节契约注）+ reviewedAt + reviewComment（如非空）

### 11.10 TokenUsageChip 用量徽标

**契约**：`TokenUsageDTO { conversationId, modelId, promptTokens, completionTokens, totalTokens, durationMs, createdAt(OffsetDateTime) }`；聚合 `UsageStats { groupKey, requestCount, totalPromptTokens, totalCompletionTokens, totalTokens, avgDurationMs }`。

> ✅ **D1 已解决**：后端 `JacksonTimeConfig` 全局统一，`TokenUsageDTO.createdAt` 已改为 **OffsetDateTime**（与 `MessageVO`/`SystemPromptDTO`/`ConversationSummary`/`DocumentDTO` 一致）。前端 dayjs 用同一套 ISO 带偏移解析即可，无需按字段分支。

📐 紧凑展示 token 用量，用于消息元信息行、用量统计页。

**消息内（紧凑态）：**
```
↑120 ↓80 · 200
```
- ↑ prompt（`--text-tertiary`）
- ↓ completion
- 总数
- 全部 `--font-size-xs` mono

**统计卡片态（用量页）：**
```
┌──────────────────┐
│ deepseek-v4-flash │
│ 150 次请求         │
│ 4.5万 总 token     │
│ ↑3万 ↓1.5万       │
│ 平均 1.2s          │
└──────────────────┘
```
- 数字强调 `--font-size-2xl` `--font-bold` `--text-primary`
- 标签 `--font-size-sm` `--text-tertiary`
- 配图表（柱状/折线，`@antv/g2` 或 `echarts`）

### 11.11 QuotaIndicator 额度指示

**契约**：`TeamDetailVO.defaultUploadLimitMb`、`creatorUploadLimitMb`、`TeamMemberVO.uploadLimitMb`（MB 单位）。

📐 展示成员 / 团队上传额度使用情况。

```
alice 的上传额度
已用 32 MB / 50 MB
▓▓▓▓▓▓▓░░░ 64%
```

- 进度条（Progress 组件）
- 已用 / 总额
- 接近上限（>80%）：进度条变 `--warning-600`
- 超额（如可）：变 `--error-600`
- 管理员可点击"调整额度"（打开 `MemberUploadLimitRequest` 表单，1-10240 MB）

### 11.12 其他复合组件索引（后续页面设计时细化）

以下组件在页面线框阶段再细化规范，本规范先列出契约对应关系：

| 组件 | 契约来源 | 场景 |
|------|---------|------|
| ConversationListItem | `ConversationSummary` | 会话列表侧栏项（标题 + 置顶钉 + messageCount + lastMessageAt） |
| ModelParamEditor | `ModelParamsDTO` | 模型参数编辑（temperature/maxTokens/topP 滑块） |
| SystemPromptEditor | `SystemPromptDTO` | 系统提示词编辑（大文本 + 字数 50000） |
| UserStatusToggle | `UserStatus` (0/1) | 用户启停开关 |
| RolePermissionMatrix | `SysPermission` × 8 | 角色-权限分配矩阵 |
| TeamMemberRoleBadge | `TeamMemberRole` | 成员角色徽标（复用 Badge + 4.4.5） |

---

## 12. 可访问性规范 🔒

> 本章为**强制基线**，非可选项。所有组件、页面必须满足。验收时逐项检查。

### 12.1 对比度（WCAG 2.1 AA）🔒

| 元素 | 对比度要求 |
|------|-----------|
| 正文文本（< 18px） | ≥ 4.5:1（vs 背景） |
| 大文本（≥ 18px 或 14px+bold） | ≥ 3:1 |
| 图标 / 边框 / 输入框 | ≥ 3:1 |
| 交互态（hover/active 文字） | 同正文要求 |

本规范定义的 token 已满足：

- `--text-primary`(#101828) on `--bg-card`(#FFF) ≈ 16:1 ✅
- `--text-secondary`(#344054) on `--bg-card` ≈ 10:1 ✅
- `--text-tertiary`(#667085) on `--bg-card` ≈ 5.4:1 ✅（仅用于非关键元信息）
- ⚠️ `--text-disabled`(#98A2B3) on `--bg-card` ≈ 2.7:1 —— **不达标，禁用于承载信息的文字**，仅用于已禁用控件的视觉弱化（此时信息本就不可用，豁免）
- ⚠️ 白字 on `--brand-600`(#155EEF) ≈ 4.6:1 ✅（刚达标，主按钮可用）

### 12.2 键盘导航 🔒

- 所有交互元素可通过 Tab 到达，顺序符合视觉流
- Shift+Tab 反向
- Enter / Space 触发按钮、链接
- 方向键导航菜单、Tab、Radio、Tree（分支切换）
- Esc 关闭 Modal / Drawer / Dropdown / Popover
- 焦点陷阱（focus trap）：Modal 打开时焦点不离开，关闭后回到触发元素
- 滑块验证码：方向键 ← → 微调，Enter 提交（除鼠标拖拽外必须有键盘路径）

### 12.3 焦点环 🔒

```css
:focus-visible {
  outline: none;
  box-shadow: var(--shadow-focus);  /* 0 0 0 4px var(--brand-100) */
}
```

- **始终可见**，不因美观移除
- 仅 `:focus-visible` 显示（鼠标点击不显示，键盘导航显示），避免鼠标用户视觉干扰
- ⚠️ 鼠标用户用 `:focus` 隐藏，键盘用户用 `:focus-visible` 显示 —— 不要 `outline: none` 全局移除

### 12.4 ARIA 与语义化 🔒

- 表单字段关联 `<label>`（`for` 或包裹）
- 错误信息 `aria-describedby` 指向，`aria-invalid="true"`
- 图标按钮必须有 `aria-label`（如关闭按钮 `aria-label="关闭"`）
- 装饰性图标 `aria-hidden="true"`
- 动态内容（Toast、流式消息、状态变化）用 `aria-live="polite"`（重要变化用 `assertive`）
- Modal：`role="dialog"` `aria-modal="true"` `aria-labelledby`
- 加载态：`aria-busy="true"` 或 `role="status"`
- 下拉 / 菜单：`role="menu"` / `role="listbox"`，项 `role="menuitem"` / `role="option"`
- Tree（会话分支）：`role="tree"` 项 `role="treeitem"`

### 12.5 动效减弱 🔒

见 9.8。所有动效在 `prefers-reduced-motion: reduce` 下降级为瞬态。

### 12.6 色彩无障碍

- **颜色不作为唯一信息载体**：状态除颜色外，必须配图标 / 文字（已在 4.4 业务状态色映射中保证 —— 每个状态都有图标 + 文案）
- 红绿色盲友好：error(红) 与 success(绿) 都配了不同图标（XCircle vs CheckCircle2）
- 暗色模式同样满足对比度

### 12.7 响应式（虽为 Web 端，仍保留基础）

本平台定位 Web 端，但保留基础响应式：

- 最小支持宽度 1024px（低于此布局可能错乱，显示"建议使用更宽屏幕"提示）
- 主断点：1280px（紧凑 → 标准）、1440px（标准 → 宽屏，内容区居中留白）
- 表格在小屏横向滚动，不挤压列

### 12.8 纯中文界面（语言定位）🔴

平台界面**只展示中文**，不做多语言切换、不保留语言选择器。具体要求：

- 所有可见文案（导航、按钮、标签、状态、提示、错误、空状态）均为中文
- 不引入语言切换控件（顶栏、设置页均不出现"语言/English"入口）
- 第三方组件库（shadcn/ui、Radix）的内置英文文案（如分页 "Previous/Next"、空表格 "No results"）**必须本地化为中文**

**工程层的 i18n 预留**（仅代码结构，不暴露给用户）：

虽界面纯中文，代码层面仍走 i18n key，便于未来单点维护：

- 所有文案走 i18n key（如 `t('chat.send')`），不硬编码字符串
- 文案集中管理（第 13 章文案表即唯一的中文资源文件）
- 布局不依赖固定文字宽度（按钮、菜单用 padding 自适应）

> 这是一项"工程纪律"而非"功能"——用户永远看不到多语言切换，但维护者改文案只改一个文件。

---

## 13. 文案规范

> 文案是设计材料，不是装饰。和间距、颜色用同样的注意力对待。文字出现在界面上只有一个目的：**让人更容易理解和使用**。
>
> 本章所有文案均以"站在使用者屏幕这一侧"的视角撰写。

### 13.0 文案四原则 🔒

1. **主动语态、说出结果**：控件命名用"会发生什么"，不用系统术语。"保存修改"而非"提交"；"删除文档"而非"执行删除操作"。
2. **动作命名贯穿全流程**：按钮叫"发布"，成功提示就叫"已发布"；按钮叫"删除"，确认就叫"删除文档?"。同一动作在触发、确认、结果三处的措辞一致，不中途换词。
3. **空状态是邀请，不是情绪**：空状态告诉用户"现在能做什么"，而不是渲染一种"空荡荡"的氛围。错误状态说"出了什么问题 + 怎么修"，不道歉、不含糊、不堆错误码。
4. **平实、对齐受众**：平台面向技术团队，用动名词、用术语表里的统一词、不用营销腔（"赋能/无缝/极致"）。一个元素只做一件事：标签只贴标签，示例只做示例。

### 13.1 术语统一表

全站统一用词，避免同义混用：

| 概念 | 统一词 | ❌ 避免用词 |
|------|--------|-----------|
| Conversation | **会话** | 对话 / 聊天记录（标题用"会话"，功能页可称"聊天"） |
| Message | **消息** | 信息 / 消息记录 |
| Document | **文档** | 文件（上传时称"文件"，入库后称"文档"） |
| Knowledge Base | **知识库** | 文档库 / 语料库 |
| Team | **团队** | 群组 / 组织 |
| Model | **模型** | — |
| Provider | **厂商** | 提供商 / 供应商（API 文档可保留 Provider） |
| Embedding | **向量化** | 嵌入（技术文档可保留 Embedding） |
| Chunk | **分块** | 切片 / 片段 |
| Rerank | **重排** | 重新排序 / 精排（技术文档可保留 Rerank） |
| Agent | **Agent**（保留英文，已通用） | 智能体 / 代理 |
| Token | **Token**（保留英文，技术语境） | 令牌 / 词元 |
| Approval | **审批** | 审核 / 批准 |
| Quota | **额度** | 配额 / 限额 |
| Pin | **置顶** | 固定 / 钉住 |

### 13.2 状态文案表（对照 4.4）

已在第 4.4 章业务状态色映射中定义中文文案，此处为权威表。所有状态展示**必须**使用此表文案，不自行措辞。

### 13.3 时间格式化规则 🔒

⚠️ 后端时间戳统一为 **OffsetDateTime**（`JacksonTimeConfig` 全局统一，D1 已解决），前端统一格式化，对用户透明：

| 场景 | 格式 | 示例 |
|------|------|------|
| 当天 | `HH:mm` | 14:30 |
| 当年 | `MM-DD HH:mm` | 06-20 14:30 |
| 跨年 | `YYYY-MM-DD HH:mm` | 2025-12-31 14:30 |
| 完整（详情） | `YYYY-MM-DD HH:mm:ss` | 2026-06-20 14:30:00 |
| 相对（列表） | "刚刚 / N 分钟前 / N 小时前 / N 天前" | 3 分钟前 |
| 时区 | 统一显示**本地时区**，后端 OffsetDateTime 已带时区自动转换 | — |

- 相对时间超过 7 天转为绝对时间
- Tooltip 悬停相对时间显示完整时间
- 💡 实现：`dayjs` + `relativeTime` + `utc` 插件

### 13.4 文件大小格式化

| 字节数 | 显示 |
|--------|------|
| < 1 KB | `N B` |
| < 1 MB | `N.N KB` |
| < 1 GB | `N.N MB` |
| ≥ 1 GB | `N.NN GB` |

- 1 位小数（KB/MB），2 位（GB）
- 无空格（`1.2MB`）或 1 空格（`1.2 MB`）全站统一，本规范用**带空格**：`1.2 MB`

### 13.5 错误文案（与后端对齐）

后端返回 `GlobalResponse { code, message }`，前端**优先显示后端 message**，本地兜底文案仅用于网络错误。

> ⚠️ **错误响应是双轨制（J4）**，前端 HTTP 拦截器必须同时处理两类：
> - **业务错误**：HTTP **200** + body `{ code: 非0, message }`（如校验失败、限流内部、资源不存在）——拦截器要检查 `code !== 0` 才算错
> - **安全/协议错误**：HTTP **4xx/5xx** + GlobalResponse body（401 未认证、403 权限不足、429 限流由 Security 过滤器直接返回 HTTP 状态码）
>
> 即"HTTP 200 不代表成功"——必须看 `code === 0` 才放行。两类都从 body 取 `message` 显示。

| 场景 | 前端兜底文案 |
|------|-------------|
| 网络断开 | "网络连接已断开，请检查网络" |
| 请求超时 | "请求超时，请重试" |
| 401 未认证 | 跳转登录页（不弹 Toast） |
| 403 权限不足 | 用后端 message（通常"权限不足"），不绕过 |
| 429 限流 | 用后端 message（"请求过于频繁"）+ 重试倒计时 |
| 500 内部错误 | "服务暂时不可用，请稍后重试"（隐藏技术细节） |
| SSE 断流 | "连接中断，正在重连…"（自动重连）+ 手动重连按钮 |

⚠️ 后端 `40001`（参数校验）message 含具体字段名（如"model: 模型不能为空"），前端原样显示。

### 13.6 空状态文案

每个模块的空状态：

| 模块 | 空状态标题 | 空状态描述 | 行动按钮 |
|------|-----------|-----------|---------|
| 会话列表 | "还没有会话" | "开始你的第一次对话吧" | "新建会话" |
| 文档列表（个人） | "还没有文档" | "上传文档即可用 RAG 检索" | "上传文档" |
| 文档列表（团队） | "团队还没有文档" | "成员可上传文档，管理员审批后入库" | "上传文档" |
| 团队列表 | "还没有团队" | "创建团队，与成员协作管理知识库" | "创建团队" |
| 成员列表 | "团队暂无其他成员" | "邀请成员加入团队" | "邀请成员" |
| 待审批 | "没有待审批的文档" | "成员上传的文档会在这里等待审批" | — |
| 用量统计 | "暂无用量数据" | "开始对话后这里会显示 Token 用量" | — |
| 搜索无结果 | "未找到匹配项" | "试试调整搜索关键词或筛选条件" | "清除筛选" |

### 13.7 操作确认文案

破坏性操作必须明确后果：

| 操作 | 确认标题 | 确认描述 | 主按钮 |
|------|---------|---------|--------|
| 删除文档 | "删除文档?" | "文档及其向量数据将被永久删除，此操作不可撤销。" | "删除"（destructive） |
| 删除会话 | "删除会话?" | "会话及全部消息将被删除，此操作不可撤销。" | "删除" |
| 解散团队 | "解散团队?" | "团队及所有成员、文档数据将被移除，此操作不可撤销。" | "解散"（destructive） |
| 移除成员 | "移除成员?" | "{nickname} 将被移出团队，可重新邀请加入。" | "移除" |
| 拒绝审批 | "拒绝此文档?" | "上传者将收到拒绝通知。" | "确认拒绝"（destructive） |

### 13.8 数字与单位

- Token 数：千分位 + 中文单位（`12,345` / `4.5万`）
- 文件大小：见 13.4
- 耗时：< 1s 用 ms（`650ms`），≥ 1s 用 s（`1.5s`），≥ 60s 用 m s（`2m 15s`）
- 百分比：整数（`65%`），置信度 1 位小数（`0.92`）
- 数字右对齐（表格列）

### 13.9 标点与语气

- 句尾不用句号（按钮、标签、Toast 标题）
- 句尾用句号（描述、说明文字）
- 操作动词开头（"上传文档"而非"文档上传"）
- 第二人称（"你的会话"）或无主语（"新建会话"），不混用
- 中英文之间加空格（`使用 DeepSeek 模型`）
- 数字与单位之间加空格（`1.2 MB`、`8.2s` 例外紧凑）

---

## 14. 暗色模式规范

### 14.1 切换机制

- 默认跟随系统：`@media (prefers-color-scheme: dark)`
- 用户可在设置中手动锁定：亮 / 暗 / 跟随系统
- 切换无刷新（CSS 变量切换）
- 偏好持久化（localStorage）

### 14.2 token 映射规则

暗色模式**不是简单反色**，而是重新映射语义别名到不同的原始色阶（见 4.2 中性色表的暗色列）：

| 语义别名 | 亮色来源 | 暗色来源 | 调整逻辑 |
|---------|---------|---------|---------|
| `--bg-canvas` | `--neutral-50`（#F9FAFB） | `--neutral-900`（#101828） | 深色底 |
| `--bg-card` | `--neutral-0`（#FFF） | `--neutral-800`（#1D2939） | 卡片比底略亮 |
| `--text-primary` | `--neutral-900` | `--neutral-0` | 反转 |
| `--text-secondary` | `--neutral-700` | `--neutral-100` | 反转 |
| `--brand-600` | #155EEF | #2E6FF2（提亮） | 暗底上需更亮保证对比 |
| 语义色 | 不变 | 提亮 1-2 阶 | 暗底可读性 |

### 14.3 特殊处理

- **阴影**：暗色模式阴影几乎不可见，改用边框 + 略亮的背景区分层次（`--shadow-xs` 在暗色降为更弱）
- **图片 / 头像**：暗色模式不变色，但可加轻微 `brightness(0.9)` 避免过亮刺眼
- **代码块**：暗色模式已有暗背景（语法高亮主题切换）
- **状态徽标 tint 底**：暗色模式 tint 改用更深阶（如 `--success-900` 而非 `--success-50`），文字用浅阶
- **图表**：暗色模式坐标轴、网格线、文字色跟随反转

### 14.4 过渡

切换主题时全站 200ms 颜色过渡（`transition: background-color, color, border-color 200ms`），但不过渡图片、阴影（性能 + 避免闪烁）。

---

## 15. 附录

### 15.1 完整 Token CSS 变量清单（可直接复制）

> 💡 此清单可直接粘贴到项目的全局 CSS 文件作为设计系统落地的起点。

```css
:root {
  /* ===== 应用品牌（可替换占位） ===== */
  --app-name: 'Smart RAG';
  --app-logo: 'SR';

  /* ===== 品牌主色（50-950 + logo） ===== */
  --brand-50:  #EFF4FF;
  --brand-100: #D1E0FF;
  --brand-200: #B3CCFF;
  --brand-300: #94B8FF;
  --brand-400: #5C8BFF;
  --brand-500: #2E6FF2;
  --brand-600: #155EEF;  /* 🔒 主色基准 */
  --brand-700: #0D4EDB;
  --brand-800: #0B3FAE;
  --brand-900: #0A2E7C;
  --brand-logo-bg: #155EEF;

  /* ===== 中性色阶 ===== */
  --neutral-0:   #FFFFFF;
  --neutral-50:  #F9FAFB;
  --neutral-100: #F2F4F7;
  --neutral-200: #E4E7EC;
  --neutral-300: #D0D5DD;
  --neutral-400: #98A2B3;
  --neutral-500: #667085;
  --neutral-700: #344054;
  --neutral-900: #101828;

  /* ===== 文本语义别名 ===== */
  --text-primary:   var(--neutral-900);
  --text-secondary: var(--neutral-700);
  --text-tertiary:  var(--neutral-500);
  --text-disabled:  var(--neutral-400);
  --text-inverse:   var(--neutral-0);
  --text-link:      var(--brand-600);

  /* ===== 背景语义别名 ===== */
  --bg-canvas:  var(--neutral-50);
  --bg-base:    var(--neutral-100);
  --bg-card:    var(--neutral-0);
  --bg-input:   var(--neutral-0);
  --bg-hover:   var(--neutral-50);
  --bg-selected: var(--brand-50);

  /* ===== 边框语义别名 ===== */
  --border-default: var(--neutral-300);
  --border-strong:  var(--neutral-400);
  --border-accent:  var(--brand-600);
  --border-subtle:  var(--neutral-200);
  --border-error:   #D92D20;

  /* ===== 语义色 ===== */
  --success-50: #ECFDF3; --success-600: #039855; --success-700: #027A48;
  --success-tint: rgba(3, 152, 85, 0.10);
  --warning-50: #FFFAEB; --warning-600: #F79009; --warning-700: #DC6803;
  --warning-tint: rgba(247, 144, 9, 0.10);
  --error-50: #FEF3F2;   --error-600: #D92D20; --error-700: #B42318;
  --error-tint: rgba(217, 45, 32, 0.10);
  --info-50: var(--brand-50); --info-600: var(--brand-600);

  /* ===== 排版 ===== */
  --font-sans: 'Inter', -apple-system, BlinkMacSystemFont, 'Segoe UI',
               'PingFang SC', 'Microsoft YaHei', 'Helvetica Neue', Arial, sans-serif;
  --font-mono: 'JetBrains Mono', 'SF Mono', Menlo, Consolas, 'Courier New', monospace;
  --font-size-xs: 11px;  --font-size-sm: 12px;  --font-size-base: 13px;
  --font-size-md: 14px;  --font-size-lg: 16px;  --font-size-xl: 18px;
  --font-size-2xl: 22px; --font-size-3xl: 28px;
  --font-normal: 400; --font-medium: 500; --font-semibold: 600; --font-bold: 700;

  /* ===== 间距（4px base） ===== */
  --space-0: 0; --space-1: 4px; --space-2: 8px; --space-3: 12px;
  --space-4: 16px; --space-5: 20px; --space-6: 24px; --space-8: 32px;
  --space-10: 40px; --space-12: 48px; --space-16: 64px; --space-20: 80px;

  /* ===== 布局 ===== */
  --layout-topbar-h: 56px;
  --layout-sidebar-w: 240px;
  --layout-sidebar-collapsed-w: 64px;
  --layout-content-max-w: 1440px;
  --layout-content-p: 24px;
  --layout-chat-input-w: 768px;

  /* ===== 圆角 ===== */
  --radius-none: 0; --radius-sm: 6px; --radius-md: 8px; --radius-lg: 12px;
  --radius-xl: 16px; --radius-2xl: 20px; --radius-3xl: 24px; --radius-full: 9999px;

  /* ===== 阴影 ===== */
  --shadow-xs: 0 1px 2px rgba(16,24,40,0.05);
  --shadow-sm: 0 1px 3px rgba(16,24,40,0.1), 0 1px 2px rgba(16,24,40,0.06);
  --shadow-md: 0 4px 8px -2px rgba(16,24,40,0.1), 0 2px 4px -2px rgba(16,24,40,0.06);
  --shadow-lg: 0 12px 16px -4px rgba(16,24,40,0.08), 0 4px 6px -2px rgba(16,24,40,0.03);
  --shadow-focus: 0 0 0 4px var(--brand-100);

  /* ===== 图标 ===== */
  --icon-xs: 12px; --icon-sm: 14px; --icon-md: 16px; --icon-lg: 20px; --icon-xl: 24px;

  /* ===== 动效 ===== */
  --motion-duration-fast: 100ms; --motion-duration-base: 200ms;
  --motion-duration-slow: 300ms; --motion-duration-slower: 400ms;
  --motion-ease-default: cubic-bezier(0.4, 0, 0.2, 1);
  --motion-ease-out: cubic-bezier(0, 0, 0.2, 1);
  --motion-ease-bounce: cubic-bezier(0.34, 1.56, 0.64, 1);

  /* ===== z-index ===== */
  --z-base: 0; --z-sticky: 10; --z-drawer: 100; --z-dropdown: 1000;
  --z-modal: 1100; --z-toast: 1200; --z-tooltip: 1300;
}

.dark {
  /* 中性色阶（暗色取值） */
  --neutral-0:   #101828;
  --neutral-50:  #1D2939;
  --neutral-100: #212B36;
  --neutral-200: #2D3748;
  --neutral-300: #475467;
  --neutral-400: #667085;
  --neutral-500: #98A2B3;
  --neutral-700: #D0D5DD;
  --neutral-900: #EAECF0;

  /* 文本反转 */
  --text-primary:   var(--neutral-0);
  --text-secondary: var(--neutral-100);
  --text-tertiary:  var(--neutral-300);
  --text-disabled:  var(--neutral-400);
  --text-inverse:   #101828;

  /* 背景 */
  --bg-canvas:  #101828;
  --bg-base:    #1D2939;
  --bg-card:    #1D2939;
  --bg-input:   #212B36;
  --bg-hover:   rgba(255,255,255,0.05);
  --bg-selected: rgba(21, 94, 239, 0.15);

  /* 边框 */
  --border-default: var(--neutral-700);
  --border-strong:  var(--neutral-500);
  --border-subtle:  var(--neutral-200);

  /* 品牌提亮 */
  --brand-600: #2E6FF2;
  --brand-700: #5C8BFF;
  --text-link: var(--brand-400);

  /* 阴影弱化 */
  --shadow-xs: 0 1px 2px rgba(0,0,0,0.3);
  --shadow-sm: 0 1px 3px rgba(0,0,0,0.4);
}
```

### 15.2 Tailwind 配置扩展示例（v4，CSS-first `@theme`）

> v0.3.3 起 Tailwind 升级到 v4。v4 用 CSS-first 配置：`@import "tailwindcss"` + `@theme {}` 取代 v3 的 `tailwind.config.js`。`@theme` 里的 `--color-*` / `--font-*` / `--text-*` / `--radius-*` 命名空间自动生成对应工具类（如 `--color-primary-600` → `bg-primary-600`）。

```css
/* app.css —— Tailwind v4 CSS-first 配置，与本规范 token 对齐 */
@import "tailwindcss";

/* 暗色强制 class 模式（v4 默认 prefers-color-scheme，这里改为 .dark 切换） */
@custom-variant dark (&:where(.dark, .dark *));

@theme {
  /* 品牌色阶 —— 映射 :root 的 --brand-* 变量，使用时 bg-primary-600 / text-primary-700 */
  --color-primary-50:  var(--brand-50);
  --color-primary-100: var(--brand-100);
  --color-primary-600: var(--brand-600);
  --color-primary-700: var(--brand-700);
  /* ...其余阶按需补 */

  /* 语义色 */
  --color-success-50: var(--success-50);  --color-success-600: var(--success-600);
  --color-warning-50: var(--warning-50);  --color-warning-600: var(--warning-600);
  --color-error-50:   var(--error-50);    --color-error-600:   var(--error-600);

  /* 语义背景别名 */
  --color-canvas: var(--bg-canvas);
  --color-surface: var(--bg-card);
  --color-muted: var(--bg-base);

  /* 字体 */
  --font-sans: var(--font-sans);
  --font-mono: var(--font-mono);

  /* 字号阶梯（本规范 6.3 自定义阶梯，覆盖 v4 默认） */
  --text-xs: 11px;  --text-sm: 12px;  --text-base: 13px;
  --text-md: 14px;  --text-lg: 16px;  --text-xl: 18px;

  /* 圆角阶梯（本规范 7.1） */
  --radius-sm: 6px;  --radius-md: 8px;   --radius-lg: 12px;
  --radius-xl: 16px; --radius-2xl: 20px; --radius-3xl: 24px;

  /* 焦点阴影 */
  --shadow-focus: var(--shadow-focus);
}
```

> 💡 v3 → v4 迁移要点：① 删除 `tailwind.config.js`，配置并入 CSS `@theme`；② `darkMode: ['selector','.dark']` 改为 `@custom-variant dark (...)`；③ shadcn/ui 已支持 v4，`npx shadcn add` 生成的组件即兼容。

### 15.3 品牌替换清单

换品牌时**仅需修改以下 token**，全站自动生效：

| 位置 | token | 说明 |
|------|-------|------|
| `:root` | `--app-name` | 产品名（顶部 Logo 文字、浏览器标题） |
| `:root` | `--app-logo` | Logo 缩写文字（占位方块内） |
| `:root` | `--brand-50` ~ `--brand-900` | 整套品牌色阶（建议保持等阶关系，仅换色相） |
| `:root` | `--brand-logo-bg` | Logo 方块背景 |
| `.dark` | `--brand-600` / `--brand-700` | 暗色模式品牌提亮色 |

**Logo 图形**：本规范用文字方块占位。若有 SVG 图标 Logo，替换 Logo 组件即可，不影响 token。

**品牌色阶生成建议**：选定主色（如新品牌主色 `#7C3AED` 紫色），用工具（如 [uicolors.app](https://uicolors.app/)）生成 50-950 等阶替换 `--brand-*`，暗色提亮一阶即可。

### 15.4 技术栈落地清单（最终版，v0.3.3 锁定）

> 版本基线取当下最新主线（2026-08）。所有"或"待决项已定。

| 类别 | 选型 | 版本 / 集成方式 | 对应章 |
|------|------|----------------|-------|
| 框架 | **React + Vite** | React 19 + Vite | — |
| 路由 | **React Router** | v7（declarative / Vite 模式，与 v6 路由树兼容） | IA §4 |
| 样式 | **Tailwind CSS** | v4（CSS-first `@theme` 配置，见 §15.2） | 全篇 token |
| 组件库 | **shadcn/ui** | Radix UI 基底，源码经 CLI 拷入仓库 `components/ui/` | 第 10 章 |
| 图标 | **lucide-react** | 唯一图标库，不混用 | 第 8 章 |
| 变体 | **CVA + tailwind-merge + clsx** | shadcn 同款 | 第 10 章 |
| UI / 临时态 | **Zustand** | 聊天/SSE 会话态、侧栏折叠等 | — |
| 服务端态 / 请求编排 | **TanStack Query** | v5，缓存/失效/重试/分页 | — |
| HTTP 传输 | **原生 fetch + 自定义 apiFetch** | 不用 axios（理由见下） | — |
| 表单 | **react-hook-form + zod** | 配 `@hookform/resolvers/zod` | 10.22 |
| 表格 | **@tanstack/react-table** | headless，支持虚拟化 | 10.10 |
| Markdown | **react-markdown + remark-gfm + rehype-raw + rehype-sanitize + rehype-katex + katex** | rehype-sanitize 白名单防 XSS（必备） | 11.3.2 |
| 代码高亮 | **shiki** | 经 `rehype-pretty-code` 接入 react-markdown 管线 | 11.3.2 |
| 图表 | **echarts** | 经 `echarts-for-react`，仅用量页懒加载（包大） | 11.10 |
| 时间 | **dayjs** | + `relativeTime` + `utc` 插件 | 13.3 |

**关键选型理由：**

- **传输层 fetch + apiFetch（非 axios）**：SSE 流式（`POST + @RequestBody`）本就必须用 fetch + ReadableStream（见 11.3），axios 无法胜任；若 REST 走 axios 则需维护**两套 transport**。axios 的卖点（拦截器/超时/JSON 转换）在 fetch + 薄封装下均可复刻，而本项目的三件定制（401→refresh→重放的并发单例锁、GlobalResponse 双轨制判 `code===0`、Cookie `credentials:'include'`）放在一个 `apiFetch` 里比塞进 axios 拦截器更清晰。TanStack Query 负责**编排**（缓存/失效），`apiFetch` 负责**传输**，两者是不同层。
- **表单/表格用无头库而非全家桶**：react-hook-form+zod / @tanstack/react-table 是 shadcn 官方组合的**逻辑引擎**（shadcn 的 `<Form>` 套在 RHF 上、Data Table 用 TanStack Table）。逻辑（无头）与样式（自有 token）分层，避免全家桶的设计语言绑定与"两套状态源"冲突，契合本规范自定义设计系统。
- **rehype-sanitize 必备**：LLM 输出与 RAG 片段可能含恶意 `<script>`/`<img onerror>`；rehype-raw 允许原始 HTML，必须配 sanitize 白名单，否则 XSS。
- **shadcn/ui = Radix（行为/a11y 骨架）+ Tailwind v4 + 本规范 token（皮），源码归仓库**：样式零冲突、改无上限、无运行时供应商锁定。

### 15.5 待确认事项追踪 ⚠️

本规范标注的待确认 / 待后端补齐项，集中追踪：

| 编号 | 事项 | 影响 | 后续动作 |
|------|------|------|---------|
| T1 | ~~`team:manage` 权限被代码引用但不在 8 权限种子内~~ | 团队额度设置入口的权限判断 | ✅ 已解决：`team:manage` 经 `V9__add_team.sql:88` 种子化并绑定 ADMIN（另有 `team:view` 供查看）。前端按"是否拥有该权限码"判断即可 |
| T2 | ~~文档无 chunk 内容查看端点~~ | 11.5 文档卡 / 11.8 ReferenceCard 展示片段 | ✅ 已实现：`GET /api/documents/{id}/chunks`（分页）+ `GET /api/chunks/{chunkId}`（`ChunkController`，返回 `ChunkDTO { id, content, documentId, fileName, metadata }`，归属校验复用文档权限逻辑） |
| T3 | Agent 事件历史 REST 端点（用户态） | 11.4 AgentTraceTimeline 完整 6 事件视图 | ⏸️ 当前不开发（预留）：后端已持久化（`agent_session_event` + `AgentEventStore`），但仅暴露**管理员侧** `GET /api/admin/agent-events`（需 `trace:view`）。面向普通用户的会话级端点尚未提供。当前用 `agentMetadata` 汇总视图兜底 |
| T4 | ~~Reference 不含 score/source/片段~~ | 11.8 引用卡信息不全 | ✅ 已实现：`mode/Reference.java` 含 8 字段（refNumber, chunkId, documentId, fileName, page, **score**, **source**, **content**），agent + chat 双路径统一。score/source/content 可空（agent 路径常见） |
| T5 | ~~`/api/models` 仅返回 ID 字符串列表~~ | 11.2 ModelSelector 需前端维护 provider 映射表 | ✅ 已实现：`GET /api/models/detail` 返回 `List<ModelVO>`（id/provider/model/capability/available）。新前端一律用 `/models/detail`，旧的 `/api/models`（仅 CHAT id 字符串）不再用 |
| T6 | 登录响应不返回 token（纯 Cookie） | 跨域部署时 Cookie SameSite=Lax 可能失效 | 🚫 已决策**不加** token-in-body（放大 XSS 风险）。跨域改走 T7 的 `SameSite=None; Secure`；原生 App 等无法用 Cookie 的客户端另议 OAuth2 Bearer |
| T7 | ~~Cookie SameSite 硬编码 Lax~~ | 跨域部署受影响 | ✅ 已解决：`JwtProperties.cookieSameSite` 参数化（默认 Lax），`CookieTokenManager` 检测 `none` 强制 `Secure=true`。跨域部署设 `JWT_COOKIE_SAMESITE=None` |
| T8 | ~~Cookie Secure 无 profile 设置~~ | HTTPS 生产必须手动设 | ✅ 已解决：`application-stable.yml` 已配 `cookie-secure: true`，prod 是 stable 的 overlay（`SPRING_PROFILES_ACTIVE=stable,prod`）继承该值，无需在 prod.yml 重复 |
| G3 | ~~流式不发 agentMetadata / fallback~~ | 11.3.5 Agent 元信息条 / 11.3.6 降级提示在流式下无数据 | ✅ 已解决：`SseStreamBridge` 现发 `event:references` / `event:agentMetadata` / `event:fallback` / `event:canceled` / `event:error` 终端帧（见 11.3 SSE 帧结构）。附带决策：`durationMs` / `tokenUsage` SSE 仍不发，前端**流式当下不展示**（不自行计时），历史 `MessageVO` 读取后展示 |
| D1 | ~~时间类型混用 LocalDateTime / OffsetDateTime~~ | dayjs 解析需按字段分支 | ✅ 已解决：后端 `JacksonTimeConfig` 全局统一，`TokenUsageDTO` / `SystemPromptDTO` / `MessageVO` / `ConversationSummary` / `DocumentDTO` 时间字段**全部为 OffsetDateTime**，前端统一一套解析即可 |

### 15.6 文档维护

- 本规范随前端迭代演进，每次重大变更递增版本号（语义化版本）
- 新增业务组件先入第 11 章规范，再开发
- token 变更需同步更新 15.1 清单与 Tailwind 配置示例
- 与后端契约的偏差记录在 15.5 待确认事项

---

**—— 设计系统规范 v0.1.0 完 ——**

> 下一阶段：信息架构 + 导航设计 → 核心三页高保真线框（认证 / 聊天工作台 / 知识库）。
> 本规范是后续所有页面设计的视觉契约，页面线框必须严格引用本规范的 token 与组件，不得脱离规范自由发挥。


