# 03 · 知识库线框

> **页面类型**：列表页（前台，IA 3.1 形态 2 非聊天页）
> **路由**：`/app/knowledge` · `/app/knowledge/personal` · `/app/knowledge/team/:teamId`
> **权限**：`isAuthenticated()`（团队文档需是团队成员）
> **前置文档**：DESIGN-SYSTEM.md v0.3.1 · INFORMATION-ARCHITECTURE.md v0.3.0
> **状态**：v0.1.0 ASCII 线框（待确认后转 HTML）

> 本页是 RAG 的数据入口——文档上传、处理状态跟踪、版本管理。扁平结构（无文件夹），个人/团队双作用域。

---

## 1. 整体布局（形态 2 非聊天页）

左栏主导航（上半）+ 下半区留白 + 单栏内容区。

```
┌─ TopBar 56px ───────────────────────────────────────────────────────────┐
│ [SR] Smart RAG              (ADMIN可见: ⚙️后台)          🌓 👤▾        │
├─ 左栏 280px ────────┬─ 内容区 ─────────────────────────────────────────┤
│                     │                                                  │
│ ── 主导航 ── 🔒固定  │  ┌─ 知识库内容 ────────────────────────────────┐ │
│  💬 聊天            │  │                                              │ │
│  📚 知识库    ●     │  │  个人文档 / 团队文档（Tab 切换，见 §2）       │ │
│  👥 团队            │  │                                              │ │
│  📊 用量统计        │  │  [📤 上传文档]                 共 42 个文档    │ │
│  ─────              │  │                                              │ │
│  ⚙️ 后台(ADMIN)     │  │  ┌─ 文档表格 ──────────────────────────────┐ │ │
│ ═════════════ 🔒分隔 │  │  │📄 report.pdf    PDF 1.2MB  ✅完成 42块│ │ │
│                     │  │  │    v2 · 2026-06-20              ⋮      │ │ │
│ ── 下半区留白 ──     │  │  ├────────────────────────────────────────┤ │ │
│                     │  │  │📄 manual.md    MD 45KB   🔄向量化中 60%│ │ │
│  （诚实留白）         │  │  │    v1 · 2026-06-19              ⋮      │ │ │
│                     │  │  ├────────────────────────────────────────┤ │ │
│                     │  │  │📄 spec.docx    DOCX 2.1MB ⚠失败 [重试]│ │ │
│                     │  │  │    v1 · 2026-06-18              ⋮      │ │ │
│                     │  │  └────────────────────────────────────────┘ │ │
│                     │  │                                              │ │
│                     │  │              < 1 2 3 ... 5 >                 │ │
│                     │  └──────────────────────────────────────────────┘ │
│ ═════════════        │                                                  │
│ 👤 admin ▾         │                                                  │
└─────────────────────┴──────────────────────────────────────────────────┘
```

> 非聊天页左栏下半区**留白**（IA 3.1 决策 A），不塞伪内容。主导航在顶部紧凑显示。

---

## 2. 个人 / 团队切换器

顶部用 Tabs（DS 10.13）切换作用域：

```
┌──────────────────────────────────────────────────────────┐
│  [个人文档]  [团队文档 ▾]                                  │  ← Tabs
│                                                           │
│  个人文档：当前用户上传的文档                                │
│  团队文档：下拉选择团队（来自 GET /api/teams），             │
│           进入 /app/knowledge/team/:teamId                 │
└──────────────────────────────────────────────────────────┘
```

| Tab | 数据源 | 路由 |
|-----|--------|------|
| 个人文档 | `GET /api/documents?page=&size=` | `/app/knowledge/personal` |
| 团队文档 | `GET /api/documents?teamId=&page=&size=` | `/app/knowledge/team/:teamId` |

- 团队文档 Tab 点击后展开团队下拉（用户所属团队列表），选择后路由跳转
- 团队文档需是团队成员（后端校验），非成员访问 403

---

## 3. 文档列表（扁平表格）

⚠️ **扁平结构**——无文件夹/分类/标签树（DocumentDTO 无这些字段）。仅个人/团队作用域 + 版本。

### 3.1 表格结构

```
┌─ 文档表格 ──────────────────────────────────────────────────────┐
│ 📄 文件名              大小     状态        分块  版本  时间   操作│  ← 表头
├────────────────────────────────────────────────────────────────┤
│ 📄 report.pdf          1.2MB   ✅已完成      42   v2   06-20  ⋮│  ← 行
│ 📄 manual.md           45KB    🔄向量化中    -    v1   06-19  ⋮│
│ 📄 spec.docx           2.1MB   ⚠失败         -    v1   06-18  ⋮│
│ 📄 data.xlsx           890KB   ⏳待审批      -    v1   06-18  ⋮│  ← 团队文档特有
│ 📄 old-version.pdf     1.1MB   ⮎已替代       38   v1   06-15  ⋮│  ← 被新版本替代
├────────────────────────────────────────────────────────────────┤
│ < 1  2  3  4  5 >                          每页 20 ▾  共 42 条  │  ← 分页
└────────────────────────────────────────────────────────────────┘
```

### 3.2 列定义

| 列 | 内容 | 对齐 | 备注 |
|----|------|------|------|
| 文件名 | `fileName` + 类型图标 | 左 | 图标按 mimeType（DS 11.5） |
| 大小 | `fileSize` 格式化（DS 13.4） | 右 | `1.2MB` |
| 状态 | `status` 徽标（§5 全 11 值） | 左 | Badge 组件 |
| 分块 | `chunkCount` | 右 | 处理中显示 `-`，完成显示数字 |
| 版本 | `version` | 中 | `v2`，可点击查看历史 |
| 时间 | `createTime` 格式化（DS 13.3） | 右 | 相对时间 |
| 操作 | `⋮` 行菜单 | 右 | hover 显示 |

### 3.3 行交互

| 状态 | 表现 |
|------|------|
| 默认 | 透明底 |
| hover | `--bg-hover`，操作 `⋮` 出现 |
| 点击行 | 打开文档详情抽屉（§6） |
| 选中（多选模式） | `--bg-selected` + 左侧 checkbox |

### 3.4 行操作菜单（⋮）

```
┌──────────────────────┐
│ 📋 查看详情           │  ← 打开抽屉
│ 📜 版本历史           │  ← 打开抽屉历史 Tab
│ 🔄 上传新版本         │  ← replaceDocumentId 增量更新
│ ─────────────        │
│ 🗑️ 删除        (红)   │  ← ConfirmDialog
└──────────────────────┘
```

> 失败状态额外显示"重试"（调 `POST /{id}/retry`）；处理中状态显示"取消"。

### 3.5 筛选约束

🔶 **半阻塞 · 当前用 mock（客户端过滤）**：后端列表接口暂仅 page/size/teamId，无 keyword/status/mimeType（DocumentController.java:47-60）。后端 [`docs/design/document-list-search-filter.md`](../../design/document-list-search-filter.md)（状态：实现就绪）落地后将支持 keyword/status[]/mimeType[] 服务端筛选。

- **搜索框 + 状态/MIME 筛选器照常实现 UI**，但当前走**前端过滤已加载列表**（标注"仅当前已加载 N 条"），搜不到未加载页
- 后端设计文档落地后，前端改传 `keyword`/`status`/`mimeType` 参数切服务端筛选，**UI 不变**
- 默认按 createTime 降序（后端默认）

---

## 4. 上传区

### 4.1 上传入口

顶部主按钮 + 拖拽 dropzone（整个内容区支持拖拽上传）。

```
┌──────────────────────────────────────────────────────────┐
│  [📤 上传文档]                              共 42 个文档    │
└──────────────────────────────────────────────────────────┘
```

拖拽文件到内容区时，覆盖一层 dropzone 提示：
```
┌──────────────────────────────────────────────────────────┐
│                                                          │
│                    📂 释放以上传                          │
│                                                          │
│            支持 PDF/DOCX/PPTX/XLSX/TXT/MD/HTML            │
│                  单文件最大 50MB                          │
│                                                          │
└──────────────────────────────────────────────────────────┘
```

### 4.2 格式与大小限制（DocumentProperties.java:11,13）

| 限制 | 值 |
|------|----|
| 允许格式 | PDF / DOCX / PPTX / XLSX / TXT / MD / HTML |
| 单文件大小 | ≤ 50MB |
| 批量上传 | 后端无限制（`/upload/batch` 接受 `MultipartFile[]`），前端建议限制 10 个 |
| Spring multipart 上限 | 55MB（留余量） |

**前端校验**（上传前拦截，避免无效请求）：
- 格式不符 → Toast error"不支持的文件格式"
- 超过 50MB → Toast error"文件超过 50MB 限制"

### 4.3 分片上传流程（ChunkUploadController）

文件 > 5MB 走分片上传（含秒传/续传），流程：

```
用户选文件
  │
  ├─ 前端计算 MD5（32 hex）
  │
  ├─ POST /api/documents/multipart (init: fileMd5, fileName, fileSize, mimeType, chunkSize)
  │   │
  │   ├─ uploaded=true（秒传，MD5 命中）
  │   │   → Toast"文件已存在，秒传成功" → 直接进入 COMPLETED
  │   │
  │   ├─ uploaded=false, uploadedChunks 非空（续传）
  │   │   → 显示续传进度条，从 uploadedChunks 继续
  │   │
  │   └─ uploaded=false, uploadedChunks 空（新上传）
  │       → 并发分片上传（3-5 并发）
  │
  ├─ POST /{uploadId}/chunks/{index} (每片: raw body + X-Chunk-MD5 header)
  │   → 更新进度条
  │   → 单片失败重试 3 次
  │
  └─ POST /{uploadId}/complete → documentId → 进入 ETL 流程
```

**分片大小**：1MB – 50MB（ChunkUploadInitRequest.java:33-34），前端默认按文件大小自动选（如 10MB 文件用 1MB 分 10 片）。

### 4.4 上传进度卡（ChunkUploadProgress · DS 11.6）

上传中的文件在列表顶部显示进度卡：

```
┌─ 上传中 ──────────────────────────────────────────────────┐
│ 📄 bigfile.zip                                            │
│ 并发上传中 5/10 分片        ▓▓▓▓▓░░░░░░░░░ 50%            │
│ ↑ 2.5 MB/s · 剩余 8s                          [取消]       │
└───────────────────────────────────────────────────────────┘
```

| 状态 | 表现 |
|------|------|
| 秒传 | Toast"秒传成功" + 直接 COMPLETED 卡片 |
| 新建上传 | 进度条 + 速度 + 剩余时间 + 取消按钮 |
| 续传 | 进度从已传分片处开始，文案"断点续传中" |
| 取消 | `POST /{uploadId}/delete` 清理后端 session |
| 单片失败 | 自动重试 3 次，超限整体失败 |

---

## 5. 状态徽标 11 值全展示（DS 4.4.1）

文档处理状态 `EtlStatus` 共 11 个值，每值在表格中的表现：

| 状态 | 徽标 | 表格行额外表现 | 可操作 |
|------|------|---------------|--------|
| `UPLOADED` 已上传 | 📤 灰"已上传" | — | 取消（删除） |
| `PENDING_APPROVAL` 待审批 | ⏳ 橙"待审批" | 仅团队文档 | 查看审批 |
| `PARSING` 解析中 | 🔄 蓝"解析中"+旋转 | — | 取消 |
| `CHUNKING` 分块中 | 🔄 蓝"分块中"+旋转 | — | 取消 |
| `VECTORIZING` 向量化中 | 🔄 蓝"向量化中"+旋转 | — | 取消 |
| `PROCESSING` 处理中 | 🔄 蓝"处理中"+旋转 | 通用进行中 | 取消 |
| `COMPLETED` 已完成 | ✅ 绿"已完成" | 显示 chunkCount | 删除/详情 |
| `FAILED` 处理失败 | ⚠️ 红"失败" | 显示 errorMessage（Tooltip） | **重试** + 删除 |
| `VECTOR_FAILED` 向量化失败 | ⚠️ 红"向量化失败" | 显示 errorMessage | **重试** + 删除 |
| `REJECTED` 已拒绝 | ⛔ 红"已拒绝" | 显示 reviewComment | 删除 |
| `SUPERSEDED` 已替代 | ⮎ 灰"已替代" + 行 opacity 0.6 | 旧版本弱化 | 查看新版本 |

> 11 值全覆盖，缺一个即 bug（DS 2.7 后端契约对齐）。

---

## 6. 文档详情抽屉（Drawer · DS 10.12）

点击文档行或"查看详情" → 右侧滑入抽屉（400px 宽）。

✅ **可查看分块内容**：`GET /api/documents/{id}/chunks`（分页）返回 `ChunkDTO { id, content, documentId, fileName, metadata }`，content 为片段全文。
✅ **原文件预览/下载已接真实端点**：后端 [`docs/design/document-original-file-preview-download.md`](../../design/document-original-file-preview-download.md) 已落地 `GET /api/documents/{id}/preview`（PDF inline 透传 / 文本类渲染；OOXML 与超限文本不可预览）与 `GET /api/documents/{id}/download`（attachment，全类型）。前端契约：`DocumentDTO.previewable` 驱动预览按钮置灰（OOXML / 超预览上限，Tooltip 提示原因）；预览在 `DocumentPreviewDialog` 中以**不带 `allow-same-origin` 的 sandbox iframe `src`** 打开（鉴权走 HttpOnly Cookie，禁止 fetch + innerHTML / srcdoc / blob）；下载走同源 `<a>` 导航触发 attachment 流式下载。

### 6.1 抽屉结构

```
┌─ 详情抽屉 400px（从右滑入）─────────────┐
│ ✕ 文档详情                              │
│                                        │
│ ── 基本信息 ──                          │
│ 📄 report.pdf                           │
│ PDF · 1.2 MB                            │
│                                        │
│ 状态: ✅ 已完成                          │
│ 分块数: 42                              │
│ 版本: v2                                │
│ 文档组: doc-group-abc123                │  ← documentGroupId
│ 创建时间: 2026-06-20 14:30              │
│ 上传者: alice                           │  ← 团队文档显示
│                                        │
│ ── 分块内容 ──（GET /{id}/chunks，分页） │  ← 仅 COMPLETED 可看
│ [1] "片段1全文..."                       │  ← ChunkDTO.content
│ [2] "片段2全文..."                       │
│                    < 1 2 3 > 每页 20 ▾  │
│                                        │
│ ── 错误信息 ──（仅失败状态）              │
│ ⚠️ 解析失败：PDF 加密，无法读取           │  ← errorMessage
│                                        │
│ ── 版本历史 ──（GET /{id}/history）      │
│ v2  当前  2026-06-20  1.2MB  ✅         │  ← 当前版本高亮
│ v1        2026-06-15  1.1MB  ⮎已替代    │  ← 点击切到旧版本详情
│                                        │
│ ── 操作 ──                              │
│ [🔄 上传新版本]                          │  ← replaceDocumentId
│ [🗑️ 删除]  (红)                          │  ← ConfirmDialog
└────────────────────────────────────────┘
```

### 6.2 字段映射（DocumentDTO）

| 显示 | 字段 | 备注 |
|------|------|------|
| 文件名 | `fileName` | — |
| 类型 | `mimeType` | 转中文（PDF/Word 等） |
| 大小 | `fileSize` | DS 13.4 格式化 |
| 状态 | `status` | §5 徽标 |
| 分块数 | `chunkCount` | 处理中为 null |
| 版本 | `version` | — |
| 文档组 | `documentGroupId` | 同组即同一逻辑文档的不同版本 |
| 创建时间 | `createTime` | DS 13.3 |
| 错误信息 | `errorMessage` | 仅失败状态 |
| 被替代 | `supersededBy` | 指向新版本 id |

### 6.3 删除确认（ConfirmDialog · DS 10.11.1）

```
┌─────────────────────────────────────┐
│ ⚠️ 删除文档?              [✕]        │
│                                     │
│ 文档"report.pdf"及其向量数据将被      │
│ 永久删除，此操作不可撤销。            │
│                                     │
│              [取消]  [删除]          │
└─────────────────────────────────────┘
```

- 默认聚焦"取消"（防误删）
- "删除"用 destructive 红色
- 对应 `POST /api/documents/{id}/delete`

### 6.4 上传新版本

点"上传新版本" → 触发文件选择 → 走分片上传流程（init 带 `replaceDocumentId`）→ 完成后：
- 旧版本状态变 `SUPERSEDED`
- 新版本成为当前（version+1）
- 版本历史列表更新

---

## 7. 团队文档特有交互

切换到"团队文档" Tab 后，额外特性：

| 特性 | 说明 |
|------|------|
| 上传需审批 | 上传后状态为 `PENDING_APPROVAL`（非直接 ETL），等 ADMIN/CREATOR 审批 |
| 审批入口 | 管理员在团队详情页"审批"Tab 处理（IA 6.4），非此页 |
| 上传额度 | 受 `TeamMember.uploadLimitMb` 限制，超额拦截 |
| 审批被拒 | 状态 `REJECTED`，显示 reviewComment |
| 成员可见 | 所有团队成员可看团队文档列表 |

> 团队文档的审批流程在团队详情页（`/app/teams/:teamId` 审批 Tab）处理，知识库页只展示结果状态。

---

## 8. 状态全集

### 8.1 空状态（个人文档空）

```
                    ┌─────────────────┐
                    │                 │
                    │   📚             │
                    │                 │
                    │  还没有文档      │
                    │  上传文档即可    │
                    │  用 RAG 检索     │
                    │                 │
                    │  [📤 上传文档]   │
                    └─────────────────┘
```

### 8.2 空状态（团队文档空）

```
                    ┌─────────────────┐
                    │   📚             │
                    │  团队还没有文档  │
                    │  成员可上传文档  │
                    │  管理员审批后入库│
                    │                 │
                    │  [📤 上传文档]   │
                    └─────────────────┘
```

### 8.3 加载态

表格区显示骨架行（3-5 行灰条），加载完成淡入。

### 8.4 上传中

列表顶部插入上传进度卡（§4.4），完成后转为文档行（状态进入 ETL）。

### 8.5 上传失败（格式/大小）

Toast error：
- 格式不符："不支持的文件格式，仅支持 PDF/DOCX/PPTX/XLSX/TXT/MD/HTML"
- 超大："文件超过 50MB 限制"

### 8.6 处理失败

文档行状态徽标变红 + "重试"按钮（行内或菜单）。抽屉显示 errorMessage。

### 8.7 团队非成员访问

直敲 `/app/knowledge/team/:无权teamId` → 403 页或重定向个人文档 + Toast"你不是该团队成员"。

---

## 9. 引用的设计系统组件

| 组件 | 出处 | 用于 |
|------|------|------|
| DocumentUploadCard | DS 11.5 | 文档行 + 详情 |
| ChunkUploadProgress | DS 11.6 | 上传进度卡 |
| Drawer | DS 10.12 | 文档详情抽屉 |
| Badge | DS 10.8 | 11 种状态徽标 |
| Table | DS 10.10 | 文档列表表格 |
| Tabs | DS 10.13 | 个人/团队切换 |
| Empty | DS 10.20 | 空状态 |
| Pagination | DS 10.19 | 分页 |
| ConfirmDialog | DS 10.11.1 | 删除确认 |
| Toast | DS 10.14 | 上传校验提示 |
| Button | DS 10.1 | 上传按钮 |

---

## 10. 待确认事项

| 编号 | 事项 | 影响 |
|------|------|------|
| KB-1 | ~~文档列表搜索是否做？~~ | 🔶 半阻塞·mock：`GET /api/documents`（及 `?teamId=`）仅 page/size，无 keyword/status/mimeType。前端搜索/筛选器照常实现，走客户端过滤已加载列表（标注"仅已加载"）。后端 [`document-list-search-filter.md`](../../design/document-list-search-filter.md)（实现就绪）落地后切服务端 keyword/status[]/mimeType[]，UI 不变 |
| KB-2 | ~~文档内容预览~~ | ✅ 分块内容可看：`GET /api/documents/{id}/chunks` + `GET /api/chunks/{chunkId}` 返回 `ChunkDTO.content`（片段全文）。✅ 原文件预览已接真实端点：`GET /api/documents/{id}/preview`（[`document-original-file-preview-download.md`](../../design/document-original-file-preview-download.md)，PDF inline / 文本类渲染 / OOXML 不可预览），前端以无 `allow-same-origin` 的 sandbox iframe 打开，`DocumentDTO.previewable` 驱动按钮置灰 |
| KB-3 | 文档下载 | ✅ 已接真实端点：`GET /api/documents/{id}/download`（[`document-original-file-preview-download.md`](../../design/document-original-file-preview-download.md)，attachment，全类型），前端同源 `<a>` 导航触发流式下载，文件名由服务端 UTF-8 ContentDisposition 提供 |
| KB-4 | 批量操作 | 当前方案支持多选（checkbox）。批量删除/批量重试是否需要？后端 `/upload/batch` 支持批量上传，但删除/重试是单个端点，批量需前端循环 |
| KB-5 | 拖拽上传的 dropzone 范围 | 当前方案是整个内容区支持拖拽。也可限定为顶部 dropzone 区域。需定 |

---

**—— 知识库线框 v0.1.0 完 ——**

> 上一页：[02-chat-workspace.md](./02-chat-workspace.md) 聊天工作台
> 核心三页线框完成。下一阶段：确认 ASCII 方向后，选关键页（推荐聊天工作台）做 HTML 高保真原型。
