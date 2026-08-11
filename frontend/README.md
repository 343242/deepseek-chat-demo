# Smart RAG 前端

对话助手 + 知识库前端。基于 `docs/frontend/` 设计文档实现（DESIGN-SYSTEM v0.3.3 + INFORMATION-ARCHITECTURE v0.3.0 + 三页线框）。

## 技术栈（DESIGN-SYSTEM §15.4 锁定）

React 19 · Vite · React Router v7 · **Tailwind v4（CSS-first `@theme`）** · shadcn/ui（Radix）· lucide-react · Zustand · TanStack Query v5 · 原生 fetch + `apiFetch`（非 axios，SSE 必须用 fetch）· react-hook-form + zod · @tanstack/react-table · react-markdown + shiki + rehype-sanitize · dayjs。

## 快速开始

```bash
cd frontend
bun install          # 依赖安装（当前环境用 bun）
bun run dev          # 开发服务器 http://localhost:5173
bun run build        # 类型检查 + 生产构建
bun run typecheck    # 仅类型检查
```

开发期 `/api` 经 Vite 代理转发到后端 `http://localhost:10808`（同源，Cookie 自然携带）。代理 target 可用 `VITE_BACKEND_URL` 覆盖。

## 目录

```
src/
├─ app.css          Tailwind v4 @theme + 全量设计 token（DESIGN-SYSTEM §15.1）
├─ lib/             工具：api-fetch / sse / format / status-meta / constants
├─ types/           后端契约类型
├─ stores/          Zustand：auth / theme / ui
├─ api/             TanStack Query + apiFetch 封装
├─ components/      ui/（shadcn 基础件）· shell · guards · auth · chat · knowledge · common
└─ pages/           auth · app · admin · error
```

## 设计依据

- 视觉：`docs/frontend/DESIGN-SYSTEM.md`（颜色/排版/组件/动效/文案）
- 结构：`docs/frontend/INFORMATION-ARCHITECTURE.md`（路由/导航/权限/布局）
- 线框：`docs/frontend/wireframes/01-auth.md`、`02-chat-workspace.md`、`03-knowledge-base.md`

后端契约摩擦点见 `docs/frontend-backend-api-reconciliation.md`。
