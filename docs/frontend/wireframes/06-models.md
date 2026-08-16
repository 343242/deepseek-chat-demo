# 06 · 模型配置线框

> **页面类型**：列表 + 行内编辑/抽屉（前台路由，ADMIN 专属预留页）
> **路由**：`/app/models` 🔒 预留（IA v0.3.0：`model:config` 仅 ADMIN，USER 访问重定向 `/app/chat`）
> **权限**：`model:config`
> **前置文档**：DESIGN-SYSTEM.md v0.4.0 · INFORMATION-ARCHITECTURE.md v0.3.0
> **状态**：v0.1.1 ASCII 线框（待确认后转 HTML）

> 本页管理**每个模型的推理参数覆盖值**（temperature / maxTokens / topP / 双 penalty）。⚠️ 两个诚实前提必须呈现在 UI 上：① 参数**全局共享**（无 userId 维度，一人改全员受影响，这正是 IA v0.3.0 收回 USER 权限的原因）；② 当前后端聊天运行时**尚未消费**这些参数（采样自 YAML 候选配置，见 MODEL-1）——页面照常实现，横幅明示现状。
>
> **v0.1.1 打磨**：① 修正模型表分组错误（qwen3-max 原误入"智谱 AI"组，独立 Qwen 组）；② 参数 label 对齐 DS 11.12（中文主显 + 英文辅，原图英文在前）；③ 外壳对齐 03 篇约定（TopBar 内容、诚实留白、用户位）；④ 状态全集补"保存中"。

---

## 1. 整体布局（形态 2 非聊天页）

```
┌─ TopBar 56px ───────────────────────────────────────────────────────────┐
│ [SR] Smart RAG              (ADMIN可见: ⚙️后台)          🌓 👤▾        │
├─ 左栏 280px ────────┬─ 内容区 ─────────────────────────────────────────┤
│ ── 主导航 ── 🔒固定  │  模型配置                       [↻ 刷新模型列表]  │
│  💬 聊天            │  ┌─ ⚠️ 提示条 ──────────────────────────────────┐ │
│  📚 知识库          │  │ 参数对全部用户生效；当前版本仅存储，对话暂未  │ │
│  👥 团队            │  │ 按此参数调用（后端接线中）                    │ │
│  📊 用量统计        │  └─────────────────────────────────────────────┘ │
│  ─────              │  筛选: [全部能力 ▾]  [仅已自定义 ▾]   🔍 搜索模型 │
│  ⚙️ 后台管理(ADMIN) │  ┌─ 模型表 ────────────────────────────────────┐ │
│                     │  │ DeepSeek                                    │ │
│ ── 下半区留白 ──     │  │  deepseek-v4-flash  对话     ●可用  [已自定义] ⋮│
│  （诚实留白）         │  │  deepseek-reasoner  对话     ●可用  [默认]    ⋮│
│                     │  │ 智谱 AI                                     │ │
│ ═════════════        │  │  glm-5.1            对话     ●可用  [默认]    ⋮│
│ 👤 admin ▾         │  │  embedding-3        向量化   ●可用  [默认]    ⋮│
│                     │  │ Qwen                                        │ │
│                     │  │  qwen3-max          对话     ○不可用 [默认]    ⋮│
│                     │  └────────────────────────────────────────────┘ │
└─────────────────────┴──────────────────────────────────────────────────┘
```

📐 提示条：`--warning-tint` 底 + `AlertTriangle` 图标（DS 4.3），常驻不可关闭（诚实设计）。

---

## 2. 模型表

**数据源**（合并两个接口）：

| 数据 | 接口 | 说明 |
|------|------|------|
| 模型目录 | `GET /api/models/detail`（ADMIN 拥有 `model:config`，**不传 capability 可看全部三种能力**：CHAT / EMBEDDING / RERANKING） | ModelVO `{ id, provider, model, capability, available }` |
| 参数覆盖 | `GET /api/models/params` → `List<ModelParamsDTO>` | 以 modelId 关联；**注意参数键是候选 id**，目录 id 同为候选 id，可直接 join |

⚠️ 普通用户视角的 `/models/detail` 强制只返 CHAT（DS 11.2）；本页是 ADMIN 视角，能看到 Embedding/Rerank——筛选器提供能力过滤（全部/对话/向量化/重排）。

**分组与列**：

| 列 | 来源 | 备注 |
|----|------|------|
| 模型 | ModelVO.id | mono；行内辅显 `provider/model`（原始模型名，`--text-tertiary` xs） |
| 能力 | ModelVO.capability | Badge：对话（brand）/ 向量化（success）/ 重排（warning） |
| 可用性 | ModelVO.available | `●可用` `--success-600` / `○不可用` `--text-disabled` |
| 参数 | ModelParamsDTO 是否存在 | `[默认]`（neutral）/ `[已自定义]`（brand） |
| 操作 | — | hover `⋮`：编辑参数 / 恢复默认（仅已自定义时显示） |

- 按 provider 分组行（表内分组标题，同 DS 11.2 ModelSelector 的分组逻辑，数据同源）
- 排序：provider → id（后端 detail 已按 capability→provider→id 排序）
- 搜索框：前端过滤（模型数有限，无需服务端）

**刷新模型列表**（页头主按钮旁，DS 7.7）：

- 调 `POST /api/models/refresh`；⚠️ 后端**成功与失败都返回 code=0**，靠 message 区分（"Models refreshed successfully" / "Failed to refresh models, existing models remain available"）——前端按 message 判定 success/warning Toast，并失效模型缓存重新拉取 detail

---

## 3. 参数编辑（Drawer，400px）

点"编辑参数" → 右侧抽屉（DS 10.12），内容 = **ModelParamEditor**（DS 11.12，v0.4.0 落地规范）：

```
┌─ 编辑参数 · deepseek-v4-flash ──────────┐
│ 当前: 已自定义（2026-08-10 更新）         │
│                                          │
│ 随机性（Temperature）            0.70       │
│ ├─────────●──────────┤  0 – 2，步进 0.05 │
│                                          │
│ 核采样（Top P）                  0.90       │
│ ├────────●───────────┤  0 – 1，步进 0.01 │
│                                          │
│ 最大输出（Max Tokens）           4096       │
│ [ 4096            ]   1 – 128000         │
│                                          │
│ 重复惩罚（Frequency Penalty）      0        │
│ ├●──────────────────┤  -2 – 2，步进 0.1  │
│                                          │
│ 话题惩罚（Presence Penalty）       0        │
│ ├●──────────────────┤  -2 – 2，步进 0.1  │
│                                          │
│ ⚠️ 保存后对全部用户的该模型调用生效         │
│                     [取消]  [保存参数]    │
└──────────────────────────────────────────┘
```

**字段与范围**（对齐 `ModelParamsDTO` 校验注解，前空=未设置走默认）：

| 参数 | 范围 | 控件 |
|------|------|------|
| temperature | 0.0 – 2.0 | 滑块 + 数值输入联动 |
| maxTokens | 1 – 128000 | 数值输入（滑块跨度太大不用） |
| topP | 0.0 – 1.0 | 滑块 + 数值输入 |
| frequencyPenalty | -2.0 – 2.0 | 滑块 + 数值输入 |
| presencePenalty | -2.0 – 2.0 | 滑块 + 数值输入 |

**保存语义**（与后端"非 null 字段合并"对齐）：

- 编辑态显示：未设置项预填**后端默认值**并标注"默认"；已设置项显示当前值
- 提交 `POST /api/models/{modelId}/params`：**只提交被用户改动的字段**（未动字段不传，保持原值/未设置状态）——避免"打开抽屉全量保存"把未设置项固化为默认值
- 保存成功 Toast"参数已保存"，表格该行变 `[已自定义]`，抽屉关闭

**恢复默认**：行菜单"恢复默认" → 轻确认 Modal（"恢复默认参数? / 将删除该模型的自定义参数，恢复系统默认。/ [恢复]") → `POST /api/models/{modelId}/params/delete` → 行变 `[默认]`

---

## 4. 状态全集

| 状态 | 表现 |
|------|------|
| 加载 | 表格骨架（DS 9.5 行级灰条） |
| 保存中 | 抽屉底部 [保存参数] 按钮 loading + 禁用（DS 10.1），防重复提交 |
| 空状态 | "没有可用模型 / 点击右上角刷新模型列表，或检查厂商配置" + [刷新模型列表] |
| 无权限直敲 | USER 访问 `/app/models` → 静默重定向 `/app/chat`（IA 2.3） |
| 保存失败 | 字段错误内联（越界）；业务错误按 DS 13.5 显示后端 message |
| 刷新部分失败 | Toast warning"部分厂商刷新失败，已保留可用模型"（IA 7.7） |

---

## 5. 引用的设计系统组件

| 组件 | 出处 | 用于 |
|------|------|------|
| ModelParamEditor | DS 11.12（v0.4.0 落地） | 参数编辑（滑块+数值联动） |
| ModelSelector 数据源 | DS 11.2（同源 `/models/detail`） | provider 分组逻辑复用 |
| Drawer | DS 10.12 | 参数编辑抽屉 |
| Badge | DS 10.8 | 能力/参数状态 |
| Table | DS 10.10 | 模型表（分组行） |
| ConfirmDialog | DS 10.11.1 | 恢复默认 |
| Toast | DS 10.14 | 刷新结果（message 判定 success/warning） |

---

## 6. 待确认事项

| 编号 | 事项 | 影响 | 后续动作 |
|------|------|------|---------|
| MODEL-1 | **model_params 未被聊天运行时消费**（运行时采样 YAML 候选配置，DB 参数无注入点） | 本页当前"只存不用"，编辑不改变实际对话行为 | 已在页面横幅明示；推动后端把 `model_params` 接入 ChatRequestSpecFactory 采样链路后，横幅移除、零 UI 改动 |
| MODEL-2 | **参数全局共享（无 userId）** | 无法做"个人偏好" | IA-8：后端给 `model_params` 加 `user_id` 维度 + USER 授 `model:config` 后，本页零改动开放（届时主导航加回入口） |
| MODEL-3 | **`POST /api/models/refresh` 失败也返回 code=0** | 前端不能用 code 判刷新成败 | 按 message 文案判定 Toast 级别（已在设计中）；建议后端改为失败返回非 0 code，前端随后简化 |
| MODEL-4 | **BYOK 用户自有模型配置（`/api/user/llm-config`）无前端入口** | 用户无法自助接入自有厂商 Key/模型 | 后端 CRUD 已就绪（apiKey 脱敏返回），属新功能，建议作为"我的账号"或独立页在下一轮立线框，不在本页混排 |

---

**—— 模型配置线框 v0.1.1 完 ——**

> 上一页：[05-usage.md](./05-usage.md) 用量统计
> 下一页：[07-account.md](./07-account.md) 我的账号
