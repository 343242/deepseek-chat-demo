# Smart RAG 前端信息架构与导航设计

> **文档类型**：信息架构（Information Architecture）+ 导航设计
> **本轮范围**：结构层骨架——路由树、导航结构、布局框架、权限分流。**不含页面线框**
> **前置阅读**：须先读 [DESIGN-SYSTEM.md](./DESIGN-SYSTEM.md) v0.3.0（本文件只引用视觉规范，不复述）
> **当前版本**：v0.3.0（前台重新定位为对话助手，采用形态 2 左栏纵向堆叠布局；模型配置收回 USER 访问权并预留接口；详见 1.1）

---

## 1. 文档元信息

### 1.1 版本

| 版本 | 日期 | 变更 | 作者 |
|------|------|------|------|
| 0.3.0 | 2026-06-21 | 前台重新定位为**对话助手**（ChatGPT 式产品逻辑）：布局改**形态 2 左栏纵向堆叠**（主导航上 + 会话列表下，会话列表仅聊天页显示，非聊天页下半区留白）；`model:config` 收回 USER 访问权（矩阵 USER 改回 ❌），路由 `/app/models` 保留作预留接口仅 ADMIN 可访问；前台左栏固定 280px 不折叠；`usage:view` 保持对 USER 开放 | 前端设计 |
| 0.2.0 | 2026-06-20 | `usage:view`（用量统计）、`model:config`（模型配置）开放给 USER 并移至前台（`/app/usage`、`/app/models`）；后台 `/admin` 收窄为纯系统管理（提示词/用户/角色/评估）；TopBar 后台切换钮判定改为"任一后台管理权限"；新增后端待办 IA-8（ModelParams 个人化） | 前端设计 |
| 0.1.0 | 2026-06-20 | 首版：角色权限矩阵 + 前后台双 shell 布局 + 完整路由树 + 导航组件规范 + 18 页清单 | 前端设计 |

### 1.1.2 本次变更依据（v0.3.0）

本次按用户反馈重新定位前台产品逻辑，并收回一项权限：

- **前台重新定位为对话助手**：用户要求"像 ChatGPT 那样凸显对话助手的产品逻辑"。前台不再以"多模块平台"为主调，而是以聊天为核心。布局采用**形态 2：左栏纵向堆叠**——单个 280px 左栏内分上下两区：上区主导航（固定不滚动），下区会话列表（仅聊天页显示，独立滚动）。非聊天页下半区**留白**（诚实设计，不塞伪内容）。这样聊天时消息流宽度约 1100px，对话场景获得最大空间。
- **模型配置收回 USER 访问权 + 预留接口**：v0.2.0 曾把 `model:config` 开放给 USER，但后端 `ModelParams` 表无 userId 维度（参数全局共享），开放会导致"一人改全员受影响"。在 IA-8（ModelParams 个人化）落地前，矩阵 USER 改回 ❌。但**路由 `/app/models` 与组件保留**作为预留接口，仅 ADMIN 可访问；PermissionGuard 守卫照常。未来 IA-8 落地 + 给 USER 授 `model:config` 后，前端零改动即可开放（届时只需主导航加回该项）。
- **用量统计保持开放给 USER**：用户本次只提收回模型配置，未提用量。`usage:view` 沿用 v0.2.0 决定，仍对 USER 开放（用户看自己的 token 消耗是个人能力，且 UsageController 的查询本身是按当前用户过滤的，无全局污染风险）。
- **前台左栏固定不折叠**：形态 2 需要足够高度容纳会话列表，收起成 64px 图标条会让下半区无处放。横向空间已够，故前台左栏固定 280px。后台布局（3.2）仍保留折叠能力。

### 1.2 本文档管什么

| 维度 | 由谁管 | 例子 |
|------|--------|------|
| **结构**（路由、导航、布局、权限、页面清单） | 本文档 | `/app/chat` 路由、侧栏有哪些项、谁能看到后台 |
| **视觉**（颜色、圆角、组件、动效、文案） | DESIGN-SYSTEM.md | 按钮 8px 圆角、`--brand-600`、空状态文案 |

两者共同构成页面线框设计的前置输入。本文件出现的视觉细节（如"激活态用 `--bg-selected`"）均为**引用**，权威定义在 DESIGN-SYSTEM.md。

### 1.3 设计依据（已确认）

基于 16 个 `@RestController` 的真实模块边界（探索结论）+ 用户三项决策：

1. **按角色分流着陆**：普通用户登录进聊天，管理员登录进后台
2. **评估模块预留入口**：双重门禁（eval profile + `evaluation:manage`），默认隐藏
3. **前后台拆分布局**：前台 `/app/*` 与后台 `/admin/*` 两套独立 shell

### 1.4 阅读约定

沿用 DESIGN-SYSTEM.md 1.5 的标记约定（⚠️ 待确认 / 🔒 硬规则 / 💡 建议 / 📐 尺寸）。新增：

| 标记 | 含义 |
|------|------|
| 🧭 | 导航相关 |
| 🛣️ | 路由相关 |
| 🛡️ | 权限相关 |

---

## 2. 用户角色与权限矩阵 🛡️

### 2.1 三类角色

| 角色 | 判定依据 | 默认着陆 | 可见范围 |
|------|---------|---------|---------|
| **访客**（未登录） | 无有效 Token | `/auth/login` | 仅登录/注册页 |
| **普通用户**（USER） | 登录且 roles 不含 ADMIN | `/app/chat` | 前台全部模块 |
| **管理员**（ADMIN） | 登录且 roles 含 `ADMIN` | `/admin`（首次）/ 上次后台页 | 前台全部 + 后台全部 |

> 角色判定基于 `/api/auth/me` 返回的 `UserInfo { roles, permissions }`（见 DESIGN-SYSTEM 附录数据契约）。`permissions: List<String>` 是权限码列表，前端缓存到全局状态供权限守卫直接读取，无需维护"角色→权限"映射表。

### 2.2 权限码与模块映射

后端权限码（V3 种子 8 个 + V9/V12/V22 追加）与前端模块的对应关系：

| 权限码 | 控制的模块 | 默认授予角色 | 来源 Controller |
|--------|-----------|-------------|----------------|
| `chat:send` | 聊天工作台 | USER / ADMIN | ChatController |
| `chat:stream` | SSE 流式聊天 | USER / ADMIN | ChatController |
| `conversation:manage` | 会话管理 | USER / ADMIN | ConversationController |
| `usage:view` | 用量统计 | USER / ADMIN | UsageController |
| `model:config` | 模型参数配置 | ADMIN | ModelParamsController |
| `prompt:manage` | 系统提示词 | ADMIN | PromptController |
| `user:manage` | 用户管理 | ADMIN | UserController |
| `role:manage` | 角色权限管理 | ADMIN | RoleController |
| `evaluation:manage` | 评估系统 | ADMIN | EvaluationRunController / DatasetController（`@Profile("evaluation")` 门控） |
| `team:manage` | 团队创建者额度设置 | ADMIN | TeamController.setCreatorQuota |
| `team:view` | 查看团队信息 | ADMIN | TeamController |
| `trace:view` | Agent 事件/调用链查看（管理员侧） | ADMIN | AdminTraceController |

> 权限来源：V3 种子 8 个核心码 + V9 `team:view`/`team:manage` + V12 `evaluation:manage` + V22 `trace:view`，均已绑定 ADMIN（详见 `resources/db/migration/`）。前端按"是否拥有该权限码"判断，不写死角色名。

### 2.3 角色 × 模块 × 权限矩阵 🔒

这是权限驱动导航与路由守卫的**权威依据**。

> **v0.3.0 调整**：`model:config`（模型配置）从 USER 开放**收回**为 ADMIN 专属。理由：后端 `ModelParams` 表无 userId 维度，参数全局共享，开放给 USER 会导致"一人改全员受影响"，与"个人偏好"意图冲突。在 IA-8（ModelParams 个人化）落地前，模型配置不开放给 USER。前端**预留接口**：路由 `/app/models` 与组件保留，仅 ADMIN 可访问；未来后端改造完成后，给 USER 加 `model:config` 权限即可零改动开放。
>
> **v0.2.0 保留**：`usage:view`（用量统计）仍开放给 USER（用户看自己的 token 消耗是个人能力），路由 `/app/usage` 在前台。

| 模块 / 页面 | 所属 shell | 所需条件 | 访客 | USER | ADMIN |
|------------|-----------|---------|:----:|:----:|:-----:|
| 登录 / 注册 | 无 | 无 | ✅ | — | — |
| 聊天工作台 | 前台 | `chat:send` | ❌ | ✅ | ✅ |
| 会话列表（聊天左栏下半区） | 前台 | `conversation:manage` | ❌ | ✅ | ✅ |
| 知识库（个人） | 前台 | `isAuthenticated()` | ❌ | ✅ | ✅ |
| 知识库（团队） | 前台 | `isAuthenticated()` + 是团队成员 | ❌ | ✅ | ✅ |
| 团队列表 / 详情 | 前台 | `isAuthenticated()` | ❌ | ✅ | ✅ |
| 用量统计 | 前台 | `usage:view` | ❌ | ✅ | ✅ |
| 模型配置 🔒预留 | 前台（路由保留） | `model:config` | ❌ | ❌ | ✅ |
| 我的账号 | 前台 | `isAuthenticated()` | ❌ | ✅ | ✅ |
| 系统提示词 | 后台 | `prompt:manage` | ❌ | ❌ | ✅ |
| 用户管理 | 后台 | `user:manage` | ❌ | ❌ | ✅ |
| 角色权限 | 后台 | `role:manage` | ❌ | ❌ | ✅ |
| 评估 | 后台 | `evaluation:manage` + `evaluation` profile | ❌ | ❌ | ✅（profile 开启时） |
| 后台入口（顶栏切换） | — | 任一**后台管理**权限（`prompt:manage`/`user:manage`/`role:manage`/`evaluation:manage`） | ❌ | ❌（隐藏切换钮） | ✅ |

> ⚠️ 关于"模型配置"的预留：矩阵虽标 USER ❌，但**路由 `/app/models` 与组件代码保留**，PermissionGuard 守卫照常工作。这不是"删除功能"，而是"默认仅 ADMIN"。未来 IA-8 落地 + 给 USER 角色授予 `model:config` 后，前端零改动即对 USER 开放，届时只需在主导航加回该项。
>
> ⚠️ 关于"后台入口"判定：USER 是否能进后台，取决于是否拥有**任一后台管理权限**（提示词/用户/角色/评估）。标准 USER 角色不拥有这些，因此看不到后台切换钮。

**判定原则**：

- 访客访问任何 `/app/*` 或 `/admin/*` → 重定向 `/auth/login?redirect=<原URL>`
- USER 访问 `/admin/*` → 重定向 `/app/chat`（不显示 403，静默回到前台）
- USER 访问 `/app/models`（无 `model:config`）→ 重定向 `/app/chat`（静默回前台，与误敲后台同处理）
- 已登录用户访问 `/auth/*` → 重定向到对应着陆页
- 缺特定权限码访问受限页（如无 `user:manage` 直敲 `/admin/users`）→ 403 页

---

## 3. 应用布局框架（前后台拆分） 🧭

平台采用**两套布局 shell**，共用顶栏但侧栏内容与导航项不同。两 shell 通过顶栏切换按钮在 `/app/*` 与 `/admin/*` 间跳转，URL 前缀区分，**状态独立**（侧栏收起状态、最近访问页各自记忆）。

> 视觉尺寸（顶栏 56px、侧栏 240px/64px、内容区 max 1440px、padding 24px）均引用 DESIGN-SYSTEM 6.2，不复述。

### 3.1 前台布局（AppShell）🧭

> **v0.3.0 重新定位**：前台核心是**对话助手**（ChatGPT 式产品逻辑），不是"多模块平台"。布局采用**形态 2：左栏纵向堆叠**——单个 280px 左栏内部分上下两区：上区主导航（固定），下区会话列表（仅聊天页显示，独立滚动）。

**聊天页**（左栏两区都在）：

```
┌─ TopBar 56px ─────────────────────────────────────────────────┐
│ [SR] Smart RAG          (ADMIN可见: 切换后台 ⚙️)     🌓 👤▾  │
├─ 左栏 280px ──────┬─ Content Area（消息流）─────────────────────┤
│                   │                                            │
│ ── 主导航 ── 🔒固定│  ┌─ 消息流 ──────────────────────────────┐ │
│  💬 聊天      ●    │  │                                       │ │
│  📚 知识库         │  │  时间分组 + 消息列表 + 输入区          │ │
│  👥 团队           │  │  （见聊天记录显示方案）                │ │
│  📊 用量统计       │  │                                       │ │
│  ─────             │  │                                       │ │
│  ⚙️ 后台管理(ADMIN)│  │              右侧详情栏 320px(默认收起)│ │
│ ═════════════ 🔒分隔│  │              （引用/Agent Trace 时展开）│ │
│                   │  └───────────────────────────────────────┘ │
│ ── 会话列表 ── 🔄滚动│                                            │
│ [+ 新建]  🔍搜索   │                                            │
│                   │                                            │
│ 📌 置顶            │                                            │
│  • RAG 测试    📌  │                                            │
│ 今天               │                                            │
│  • MMR 原理    ⋮  │                                            │
│  • 新会话          │                                            │
│ 昨天               │                                            │
│  • 文档上传        │                                            │
│  ↑ 加载更多        │                                            │
│ ═════════════      │                                            │
│ 👤 admin ▾        │                                            │
└───────────────────┴────────────────────────────────────────────┘
```

**非聊天页**（如知识库 · 左栏下半区留白，主导航独占）：

```
┌─ TopBar 56px ─────────────────────────────────────────────────┐
│ [SR] Smart RAG          (ADMIN可见: 切换后台 ⚙️)     🌓 👤▾  │
├─ 左栏 280px ──────┬─ Content Area ─────────────────────────────┤
│                   │                                            │
│ ── 主导航 ──       │  ┌─ 单栏内容 + 抽屉详情 ─────────────────┐ │
│  💬 聊天           │  │                                       │ │
│  📚 知识库    ●    │  │  （该模块的列表/表格，详情用抽屉）      │ │
│  👥 团队           │  │                                       │ │
│  📊 用量统计       │  │                                       │ │
│  ─────             │  │                                       │ │
│  ⚙️ 后台管理(ADMIN)│  │                                       │ │
│ ═════════════      │  │                                       │ │
│                   │  │                                       │ │
│  （下半区留白）     │  │                                       │ │
│                   │  │                                       │ │
│ ═════════════      │  │                                       │ │
│ 👤 admin ▾        │  └───────────────────────────────────────┘ │
└───────────────────┴────────────────────────────────────────────┘
```

**左栏内部分区规则**：

| 分区 | 位置 | 滚动 | 显示条件 |
|------|------|------|---------|
| 主导航区 | 上部 | **固定不滚动** | 所有前台页始终显示 |
| 会话列表区 | 中部 | **独立滚动**（overflow-y: auto） | **仅聊天页显示**，非聊天页此区留白 |
| 用户区 | 底部 | 固定 | 所有前台页始终显示 |

三区之间用 `--border-subtle` 分隔线明确边界。会话列表区的留白是**诚实的设计**（DESIGN-SYSTEM 克制原则）——不塞伪内容假装填满。

**导航项**（前台主导航，自上而下）：

| 顺序 | 项 | 路由 | 图标 | 权限 |
|------|----|------|------|------|
| 1 | 聊天 | `/app/chat` | `MessageSquare` | `chat:send` |
| 2 | 知识库 | `/app/knowledge` | `BookOpen` | `isAuthenticated()` |
| 3 | 团队 | `/app/teams` | `Users` | `isAuthenticated()` |
| 4 | 用量统计 | `/app/usage` | `BarChart3` | `usage:view` |
| — | （分隔线） | | | |
| 5 | 后台管理 | `/admin` | `Settings` | **仅拥有任一后台管理权限可见**，点击切到 AdminShell |

> **模型配置不在主导航**：v0.3.0 收回 USER 访问权（矩阵 USER 改回 ❌）。路由 `/app/models` 保留作为**预留接口**，仅 ADMIN 可访问，但 ADMIN 通常从后台 `/admin` 访问配置类功能，故不在前台主导航暴露。如未来开放给 USER，加回此项即可（见 1.1.x 与 IA-8）。
>
> **左栏不收起**：形态 2 需要足够高度容纳会话列表，收起成 64px 图标条会让下半区无处放。横向空间已够（消息流约 1100px），故前台左栏固定 280px，不做折叠。后台布局（3.2）仍保留折叠能力。

**会话列表区**详细规范见聊天记录显示方案（历史会话列表部分），要点：分组（置顶/今天/昨天/7天内/更早）、无限滚动分页、单条目三层信息（标题/模式+模型/消息数+时间）、hover 操作菜单（置顶/重命名/归档/删除）。

### 3.2 后台布局（AdminShell）🧭

管理员专属，承载提示词、用户、角色、评估（系统级管理）。**单栏 + 抽屉详情**为主（管理表格密集，不走三栏）。

```
┌─ TopBar 56px ────────────────────────────────────────────────────┐
│ [≡] [SR] Smart RAG          ← 返回前台              🌓 👤▾       │
├─ Sidebar 240px / 64px ─┬─ Content Area ──────────────────────────┤
│                       │                                          │
│  ← 返回前台           │   管理表格密集区                          │
│  ──────────           │   - 列表 + 行内操作 / 行菜单              │
│  📝 系统提示词        │   - 详情用右侧抽屉（Drawer）              │
│  👤 用户管理          │   - 顶部面包屑定位                        │
│  🛡️ 角色权限          │                                          │
│  🧪 评估 (条件显示)   │                                          │
│                       │                                          │
│ ──────────            │                                          │
│ (底部) 折叠按钮        │                                          │
└───────────────────────┴──────────────────────────────────────────┘
```

**侧栏导航项**（后台，自上而下）：

| 顺序 | 项 | 路由 | 图标 | 权限 | 条件 |
|------|----|------|------|------|------|
| 1 | 返回前台 | `/app/chat` | `ArrowLeft` | — | 始终（顶部固定项） |
| — | （分隔线） | | | | |
| 2 | 系统提示词 | `/admin/prompts` | `ScrollText` | `prompt:manage` | |
| 3 | 用户管理 | `/admin/users` | `UserCog` | `user:manage` | |
| 4 | 角色权限 | `/admin/roles` | `ShieldCheck` | `role:manage` | |
| 5 | 评估 | `/admin/evaluation` | `FlaskConical` | `evaluation:manage` | **+ `evaluation` profile 开启** |

> **当前状态（v0.3.0）**：后台侧栏只含系统级管理（提示词/用户/角色/评估）。用量统计在 v0.2.0 移至前台（`/app/usage`），v0.3.0 保持；模型配置在 v0.2.0 曾移至前台，v0.3.0 收回 USER 访问权（路由 `/app/models` 保留仅 ADMIN，但不在前台主导航暴露，ADMIN 从后台访问）。每个后台项独立权限守卫：管理员若某项权限被收回，该项隐藏。评估项受 `@Profile("evaluation")` 门控——默认 profile 不激活评估控制器，需 `SPRING_PROFILES_ACTIVE` 含 `evaluation` 才生效；非评估环境调用会得到 404。前端 feature flag 判定见 8.1。

### 3.3 认证布局（AuthShell）

登录/注册页**不用**上述 shell，是独立的全屏布局：

```
┌──────────────────────────────────────────────────────────┐
│                                                          │
│              (居中, 暗色或品牌渐变背景)                    │
│                                                          │
│           ┌─────────────────────────┐                    │
│           │  [SR] Smart RAG         │                    │
│           │                         │                    │
│           │   登录 / 注册表单        │  ← 圆角 3xl (24px) │
│           │   + 滑块验证码           │     营销级入口感    │
│           │                         │                    │
│           └─────────────────────────┘                    │
│                                                          │
└──────────────────────────────────────────────────────────┘
```

📐 登录卡用 `--radius-3xl`（24px，DESIGN-SYSTEM 7.1 最大圆角档），营造柔和入口感。背景可用极浅品牌蓝渐变（`--brand-50` → `--bg-canvas`），但**不喧宾夺主**（遵守 DESIGN-SYSTEM 4.0 蓝白主调反例：不做大面积蓝色 hero）。

### 3.4 错误布局（ErrorShell）

403 / 404 / 500 页独立全屏，居中图标 + 标题 + 描述 + 行动按钮：

```
┌──────────────────────────────────────────────────────────┐
│                                                          │
│                    🔒 (大图标)                            │
│                                                          │
│                没有访问权限                               │
│          你当前的角色无法访问此页面                        │
│                                                          │
│              [返回聊天]  [退出登录]                       │
│                                                          │
└──────────────────────────────────────────────────────────┘
```

---

## 4. 路由树（完整 URL 结构） 🛣️

基于 React Router v6 风格。所有路由挂在一个根 `<Router>` 下，按 shell 分组。

### 4.1 完整路由表

```
/                              → 重定向（见 4.2 着陆分流）

/auth                          → AuthShell（已登录访问则重定向着陆页）
  /auth/login                  → 登录页
  /auth/register               → 注册页

/app                           → AppShell（需 isAuthenticated + chat:send）
  index                        → 重定向 /app/chat
  /app/chat                    → 聊天工作台（三栏）★ 普通用户默认着陆
    /app/chat/:conversationId  → 打开指定会话
  /app/knowledge               → 知识库（index 重定向 /personal）
    /app/knowledge/personal    → 个人文档列表（默认）
    /app/knowledge/team/:teamId→ 团队文档列表（团队切换器进入）
  /app/teams                   → 团队列表
    /app/teams/:teamId         → 团队详情（成员/审批/文档 Tab）
  /app/usage                   → 用量统计（usage:view，USER 可访问）
  /app/models 🔒预留           → 模型配置（model:config，仅 ADMIN；USER 访问重定向 /app/chat）
  /app/account                 → 我的账号（资料/密码/偏好/主题）

/admin                         → AdminShell（需 isAuthenticated + 任一**后台**管理权限）
  index                        → 重定向到首个有权限的后台子页
  /admin/prompts               → 系统提示词（prompt:manage）
  /admin/users                 → 用户管理（user:manage）
  /admin/roles                 → 角色权限（role:manage）
  /admin/evaluation            → 评估（evaluation:manage + flag）

*                              → 404 错误页（ErrorShell）
```

> **v0.3.0 路由变更**：`/app/models` 保留路由但收回 USER 访问权（仅 ADMIN）。用量统计 `/app/usage` 仍对 USER 开放。前台主导航不再显示"模型配置"项（ADMIN 从后台访问配置类功能）。

**文档详情、成员详情、审批详情等不入独立路由**，用各页内的抽屉（Drawer）/ 弹层承载，避免路由爆炸：

| 详情类型 | 承载方式 | 理由 |
|---------|---------|------|
| 文档详情 | 知识库页内右侧 Drawer | 不打断列表浏览 |
| 团队成员详情 | 团队详情页内 Drawer | 同上 |
| 文档版本历史 | 知识库页内 Drawer 二级 | `GET /api/documents/{id}/history` |
| 审批详情 | 团队详情"审批"Tab 内展开 | 不离开团队上下文 |
| 模型参数编辑 | 模型配置页内抽屉/行内 | 列表与编辑同屏 |

### 4.2 着陆分流逻辑 🛣️

**首次登录**（无上次访问记忆）：

```
登录成功
  ├─ roles 含 ADMIN  → /admin（重定向到首个有权限的子页）
  └─ 否则            → /app/chat
```

**根路径 `/` 访问**：

```
/
  ├─ 未登录           → /auth/login
  ├─ 已登录 USER      → /app/chat
  └─ 已登录 ADMIN     → 上次访问的 shell（/app/* 或 /admin/*，localStorage 记忆）
```

**二次登录优化**：localStorage 记录上次访问页（区分前台/后台），二次登录优先回到上次位置（仅同角色内有效，角色变化则回到默认着陆）。

### 4.3 路由守卫链

每个受保护路由外层包 `<PermissionGuard>`，守卫判定顺序：

```
1. isAuthenticated?     ❌ → /auth/login?redirect=<当前URL>
2. 需要特定权限码?
   ├─ chat:send         ❌（USER 也可能被收回）→ 403
   ├─ model:config 等   ❌ → /app/chat（静默回前台）或 403
   └─ evaluation:manage ❌ → 隐藏入口 + 直敲则 403
3. eval profile 开启?   ❌（评估专用，见 8.1）→ 403/404
4. 全通过               → 渲染页面
```

---

## 5. 导航组件规范 🧭

本节定义导航相关组件的**交互规则**。视觉细节（颜色、圆角、尺寸）引用 DESIGN-SYSTEM.md，不重复。

### 5.1 AppSidebar / AdminSidebar

📐 宽 240px（展开）/ 64px（收起），bg `--bg-card`，右边框 `--border-default`

**导航项交互**：

| 状态 | 表现 |
|------|------|
| 默认 | 图标 `--text-secondary` + 文字 `--text-secondary`，padding 10px 12px |
| hover | bg `--bg-hover`，文字 `--text-primary` |
| 激活（当前路由） | bg `--bg-selected`，文字 `--brand-700` `--font-medium`，**左侧 3px `--brand-600` 指示条**（从顶到底） |
| disabled（无权限项） | 不渲染（直接隐藏，非置灰） |

**收起态**（64px）：仅图标居中，hover 出 Tooltip 显示文字 + 激活项保留指示条。

**状态持久化**：展开/收起状态存 localStorage，key 区分前台后台（`sidebar.app.collapsed` / `sidebar.admin.collapsed`），各自独立。

**激活判定**：路由前缀匹配。`/app/chat/:id` 激活"聊天"项；`/app/knowledge/team/:id` 激活"知识库"项。

### 5.2 TopBar

📐 高 56px，bg `--bg-card`，下边框 `--border-default`，三段式布局：

| 区域 | 内容 | 条件 |
|------|------|------|
| 左 | 折叠按钮（`PanelLeft`）+ Logo 方块 + 产品名 `--app-name` | 始终 |
| 中 | 前后台切换：前台显示"后台管理"入口（`Settings` 图标 + 文字），后台显示"返回前台"（`ArrowLeft`） | **仅拥有任一后台管理权限可见**（`prompt:manage`/`user:manage`/`role:manage`/`evaluation:manage`）。标准 USER 即使有 `usage:view`（用量在前台），也看不到后台切换钮 |
| 右 | 主题切换（`Sun`/`Moon`）+ 用户头像下拉菜单 | 始终 |

**用户头像下拉菜单**（Dropdown，DESIGN-SYSTEM 10.21）：

```
┌──────────────────────┐
│ 👤 管理员             │  ← 昵称 + 角色徽标
│    admin@example.com │  ← 邮箱（--text-tertiary）
├──────────────────────┤
│ 👤 我的账号           │  → /app/account
│ ⚙️ 后台管理 / ← 前台  │  → 切换 shell（仅 ADMIN）
│ 🌓 切换主题           │  → 切换明暗
├──────────────────────┤
│ 🚪 退出登录           │  → 红色，二次确认（DESIGN-SYSTEM 10.11.1）
└──────────────────────┘
```

> 退出登录是破坏性操作（清会话），按 DESIGN-SYSTEM 2.0c + 2.5 走 ConfirmDialog 二次确认。

### 5.3 PermissionGuard 🛡️

路由级与组件级双用权限守卫：

**路由级**（包裹 `<Route>` element）：

```tsx
<Route path="/admin/users" element={
  <PermissionGuard require="user:manage">
    <UsersPage />
  </PermissionGuard>
} />
```

**组件级**（包裹导航项、按钮等）：

```tsx
<PermissionGuard require="evaluation:manage" feature="evaluation">
  <NavItem icon={FlaskConical} label="评估" to="/admin/evaluation" />
</PermissionGuard>
```

**API**：

| prop | 类型 | 说明 |
|------|------|------|
| `require` | `string \| string[]` | 权限码，数组表示"满足任一" |
| `feature` | `string` | feature flag 名（目前仅 `evaluation`，来源见 8.1） |
| `fallback` | `ReactNode` | 无权限时的替代渲染（默认 `null` 即隐藏） |
| `redirect` | `string` | 路由级用，无权限重定向目标（默认 `/app/chat`） |
| `children` | `ReactNode` | 受保护内容 |

**判定逻辑**：

1. `require` 权限码是否在当前用户 `permissions` 列表中？（`/api/auth/me` 返回 `permissions: List<String>`，前端缓存——无需前端维护"角色→权限"映射表）
2. 若有 `feature` prop，该 feature flag 是否开启？（见 8.1）
3. 全通过渲染 children，否则渲染 fallback / 重定向

> ⚠️ 前端权限守卫仅作**体验优化**（隐藏入口、防误访问），**不替代后端 `@PreAuthorize`**。后端是真正的安全边界，前端被绕过不能造成数据泄露。

### 5.4 Breadcrumbs

仅后台表格密集页用（前台聊天/知识库等用页面标题 + 子标题即可）。

📐 高 32px，`--font-size-sm` `--text-tertiary`，分隔符 `ChevronRight` 图标：

```
后台管理 / 用户管理 / 编辑 admin
```

- 最后一级为当前页，`--text-primary` `--font-medium`，不可点
- 前几级可点跳转
- 层级 ≤ 3，超过则省略中间

---

## 6. 页面清单（骨架，线框留后续） 🧭

本轮 IA 覆盖的全部页面。每页给：路由、shell、权限、类型、主数据源。**具体线框在下一轮做核心三页**。

### 6.1 认证与错误页

| 页面 | 路由 | Shell | 权限 | 类型 | 数据源 |
|------|------|-------|------|------|--------|
| 登录 | `/auth/login` | AuthShell | 访客 | 表单 | AuthController |
| 注册 | `/auth/register` | AuthShell | 访客 | 表单 | AuthController |
| 403 | `*` | ErrorShell | — | 错误页 | — |
| 404 | `*` | ErrorShell | — | 错误页 | — |
| 500 | `*` | ErrorShell | — | 错误页 | — |

### 6.2 前台页面（AppShell）

| 页面 | 路由 | 权限 | 类型 | 主数据源 |
|------|------|------|------|---------|
| 聊天工作台 | `/app/chat[/:conversationId]` | `chat:send` | 三栏工作台 | ChatController + ConversationController |
| 知识库-个人 | `/app/knowledge/personal` | `isAuthenticated()` | 列表 + 抽屉 | DocumentController |
| 知识库-团队 | `/app/knowledge/team/:teamId` | `isAuthenticated()` + 团队成员 | 列表 + 抽屉 | DocumentController(`?teamId=`) |
| 团队列表 | `/app/teams` | `isAuthenticated()` | 卡片/列表 | TeamController |
| 团队详情 | `/app/teams/:teamId` | `isAuthenticated()` + 团队成员 | Tab 详情（成员/审批/文档） | TeamMember/Approval/Document Controller |
| 用量统计 | `/app/usage` | `usage:view` | 仪表盘（图表 + 明细，个人维度） | UsageController |
| 模型配置 🔒预留 | `/app/models` | `model:config`（仅 ADMIN） | 列表 + 行内编辑/抽屉 | ModelParamsController + `/api/models` |
| 我的账号 | `/app/account` | `isAuthenticated()` | 表单（资料/密码/偏好） | AuthController `/me` |

### 6.3 后台页面（AdminShell）

| 页面 | 路由 | 权限 | 类型 | 主数据源 |
|------|------|------|------|---------|
| 系统提示词 | `/admin/prompts` | `prompt:manage` | 列表 + 大文本编辑 | PromptController |
| 用户管理 | `/admin/users` | `user:manage` | 列表 + 抽屉详情 | UserController |
| 角色权限 | `/admin/roles` | `role:manage` | 列表 + 权限矩阵 | RoleController |
| 评估 | `/admin/evaluation` | `evaluation:manage` + `evaluation` profile | 工作台（运行/数据集） | EvaluationRun/DatasetController |

### 6.4 团队详情 Tab 结构

团队详情页（`/app/teams/:teamId`）用 Tabs（DESIGN-SYSTEM 10.13）分 3 个 Tab，Tab 切换不走路由（用 query param `?tab=members|approvals|documents` 记忆）：

| Tab | 内容 | 角色可见性 |
|-----|------|-----------|
| 成员 | 成员列表 + 邀请/移除/改角色/改额度（管理员操作） | 全员可见；管理操作仅 ADMIN/CREATOR |
| 审批 | 待审批列表（ADMIN/CREATOR）+ 我的审批（全员） | 全员可见；审批操作仅 ADMIN/CREATOR |
| 文档 | 团队文档列表（复用知识库文档卡，带审批状态） | 全员可见 |

> "文档"Tab 点击上传可跳转 `/app/knowledge/team/:teamId`（知识库团队模式），也可在 Tab 内直接上传。两处共享同一份文档数据。

---

## 7. 全局交互约定 🧭

跨页面的交互规则（IA 层面）。视觉细节引用 DESIGN-SYSTEM.md。

### 7.1 加载与过渡

| 场景 | 表现 |
|------|------|
| 路由切换 | 顶部 2px 进度条（`--brand-600`），200ms 内完成不显示，> 200ms 显示 |
| 页面首屏 | 骨架屏（DESIGN-SYSTEM 9.5 / 10.18），匹配最终布局形状 |
| 列表分页 | 骨架屏替换当前列表，不整页 loading |
| 按钮提交 | 按钮内联 Loader2 旋转 + 禁用（DESIGN-SYSTEM 10.1 loading 态） |

### 7.2 认证失效（401）

全局 HTTP 拦截器捕获 `code: 40100`：

1. 清除前端缓存的用户信息
2. 跳转 `/auth/login?redirect=<当前完整URL>`
3. 登录成功后回跳 `redirect`（验证 redirect 是否为本站路径，防开放重定向）
4. 跳转前若有未保存表单，弹 ConfirmDialog 确认（DESIGN-SYSTEM 10.11.1）

> Token 刷新（`/api/auth/refresh`）在 401 时自动尝试一次，成功则重放原请求；失败才走上述跳转。

### 7.3 权限不足（403）

- `code: 40300`：显示专用 403 页（ErrorShell），不裸露错误码
- 提供返回入口（返回聊天 / 退出登录）
- USER 误敲 `/admin/*`：静默重定向 `/app/chat`（不显示 403，更友好）

### 7.4 错误兜底

| 错误 | 表现 |
|------|------|
| 5xx（`code: 50000`） | 500 错误页 + 重试按钮 |
| 网络断开 | 顶栏红色横幅"网络连接已断开"，恢复后绿色横幅 2s 消失 |
| 请求超时 | Toast warning + 重试 |
| 429 限流（`code: 42900`） | Toast warning + 倒计时重试 |

### 7.5 离开确认 🔒

下列场景路由跳转前弹 ConfirmDialog（DESIGN-SYSTEM 10.11.1）：

- 聊天输入框有未发送内容
- 任何表单有未保存改动（脏检测）
- 文档分片上传进行中

> 依赖 React Router 的 `useBlocker`（v6.4+）或等价机制。

### 7.6 空状态统一

所有列表/详情的空状态文案统一引用 DESIGN-SYSTEM 13.6 空状态文案表，不在各页自行措辞。

### 7.7 刷新模型

聊天页（ModelSelector 旁）与模型配置页提供"刷新模型列表"按钮，调 `POST /api/models/refresh`（权限 `model:config`）：

- 成功：Toast success"模型列表已刷新"
- 部分失败：Toast warning"部分厂商刷新失败，已保留可用模型"

> 刷新按钮需 `model:config` 权限（后端 `@PreAuthorize` 强制，与代码对齐）。普通用户（USER）无此权限，聊天页 ModelSelector 旁**不显示**刷新按钮（PermissionGuard 隐藏）；仅 ADMIN 在模型配置页可见。

---

## 8. 待确认事项追踪 ⚠️

延续 DESIGN-SYSTEM 附录 15.5 的格式，记录本 IA 阶段新发现的后端依赖与待决策项：

| 编号 | 事项 | 影响 | 后续动作 |
|------|------|------|---------|
| IA-1 | **评估 feature flag 来源** | 评估导航项的显示需知道 eval profile 是否开启 | ⏸️ 当前不开发（预留）：评估控制器受 `@Profile("evaluation")` 门控，`/api/auth/me` 暂无 `features.evaluation` 字段。当前前端只能按 `evaluation:manage` 权限码显示入口，非评估环境点击会得到 404（控制器未激活）。如需可靠判定，建议后端在 `/api/auth/me` 响应增加 `features: { evaluation: boolean }` 字段或新增 `/api/features` 端点——列为后续，当前迭代不动 |
| IA-2 | **系统状态指示器** | `/actuator/health` 已 `permitAll`，可做顶栏轻量健康指示 | 评估是否在 TopBar 右侧加一个小绿点/红点指示后端健康。需权衡：频繁轮询的开销 vs 价值。暂不实现，列入后续 |
| IA-3 | **团队文档与团队详情的团队上下文同步** | 知识库 `/app/knowledge/team/:teamId` 与团队详情 `/app/teams/:teamId` 文档 Tab 共享数据，切换时需保持团队上下文 | 用全局状态（Zustand）记录"当前活跃团队"，两处读写同一份 |
| IA-4 | **前后台切换的记忆粒度** | 侧栏收起状态、最近访问页是否前台后台各自独立 | 已定：localStorage key 区分 `sidebar.app.*` / `sidebar.admin.*`，最近访问页区分 `last.app` / `last.admin`，各自独立 |
| IA-5 | ~~**权限码前端获取方式**~~ | ~~`/api/auth/me` 返回 `roles: List<String>`（角色名），但权限守卫需要权限码~~ | ✅ 已解决：`/api/auth/me` 返回的 `UserInfo` 已含 `permissions: List<String>`（`LoginResponse.java:30`），权限守卫直接读权限码，无需前端维护"角色→权限"映射表。注：登录/注册/刷新响应中 permissions 可能为空（异步预热 best-effort），前端拿响应后应立即调 `/me` 兜底拉取（`/me` 保证返回非空当前权限快照） |
| IA-6 | **401 自动刷新的并发控制** | 多个请求同时 401 时，refresh 应只发一次，其余排队等刷新结果 | 前端实现：refresh 请求做单例锁，并发 401 共享同一 Promise |
| IA-7 | **会话路由与会话列表状态** | `/app/chat/:conversationId` 直接打开会话，需确保侧栏会话列表也高亮该项并滚动可见 | 前端：路由参数变化时同步列表 active 态 + scrollIntoView |
| IA-8 | **ModelParams 缺 userId 维度（个人化阻塞，v0.3.0 已降级）** | `model:config` 在 v0.3.0 收回 USER 访问权，此项不再阻塞当前迭代。但未来若要重新开放给 USER（个人化模型参数），仍需后端给 `ModelParams` 表加 `user_id` 列（按 `(user_id, model_id)` 唯一索引），API 层注入当前用户 id 过滤。前端 `/app/models` 路由与组件已预留，改造完成后给 USER 授 `model:config` 即可零改动开放 | 后端给 `ModelParams` 表加 `user_id` 列（当前 `V1__init_schema.sql:66-75` 的 `model_params` 表按 `model_id` 全局唯一，无 userId）；前端已预留接口，无需改动 |
| IA-9 | **会话列表服务端搜索缺失** | `GET /api/conversations` 仅支持 page/size/status，无 keyword 参数 | 已知限制：前端搜索框只能客户端过滤已加载列表（标注"仅已加载"）。会话极多时只能搜已加载部分。如需服务端搜索，需后端在 `ConversationServiceImpl.list` 加 keyword 参数 |

---

**—— 信息架构 v0.3.0 完 ——**

> 下一阶段：**核心三页高保真线框**（认证 / 聊天工作台 / 知识库），基于本 IA 的路由与布局 + DESIGN-SYSTEM v0.3.0 的视觉规范。
> 本文件与 DESIGN-SYSTEM.md 共同构成页面线框设计的双重前置输入：IA 管"结构"，规范管"视觉"。

