# Frontend Development Guidelines

> Best practices for frontend development in this project.

---

## Overview

React 19 + Vite 6 + TypeScript 5.9（strict）+ Tailwind v4（CSS-first）+ shadcn/ui（new-york）的对话助手 + 知识库前端。服务端状态全部走 TanStack Query，客户端状态走 Zustand，HTTP 传输统一收口 `apiFetch`。包管理器为 **bun**。

代码位于 `frontend/`，与 Spring Boot 后端（`:10808`）通过 Vite 代理 `/api` 同源通信，鉴权依赖 HttpOnly Cookie。

---

## Pre-Development Checklist

开始编码前，确认已阅读：

- [ ] [Directory Structure](./directory-structure.md) — src/ 分层、命名规则、依赖方向
- [ ] [Data & State](./data-and-state.md) — apiFetch 契约、TanStack Query、Zustand、SSE 流式（改任何数据获取/状态代码前必读）
- [ ] [UI & Styling](./ui-and-styling.md) — 设计 token、shadcn/ui、组件编写、表单（写任何组件前必读）
- [ ] [Quality & Testing](./quality-and-testing.md) — 质量门、TS 约定、测试、性能与安全清单

---

## Guidelines Index

| Guide | Description | Status |
|-------|-------------|--------|
| [Directory Structure](./directory-structure.md) | src/ 目录组织、命名、分层与依赖方向 | ✅ Filled |
| [Data & State](./data-and-state.md) | apiFetch/GlobalResponse 契约、queryKeys 工厂、Zustand 模式、Effect 边界、React 19 用法、SSE 手动解析 | ✅ Filled |
| [UI & Styling](./ui-and-styling.md) | Tailwind v4 语义 token、shadcn/ui、特性组件、RHF+Zod 表单 | ✅ Filled |
| [Quality & Testing](./quality-and-testing.md) | bun 四道质量门（tsc/ESLint/Vitest/build）、React 19 + TS 约定、懒加载拆包、安全不变量 | ✅ Filled |

---

## Quick Reference

- **包管理**: bun（`bun install` / `bun run dev`，禁 npm/yarn/pnpm）
- **HTTP**: `apiFetch` / `api.get|post|put|del`（组件禁直接 `fetch`，唯一例外是 `lib/sse.ts` 流式）
- **服务端状态**: TanStack Query（禁 useEffect + useState 拉数据、禁把接口数据复制进 store）
- **客户端状态**: Zustand（扁平 state + actions，组件用 selector 订阅）
- **样式**: Tailwind v4 语义 token（`bg-surface` / `text-muted` / `border-line`，禁裸色值 `gray-500`/hex）
- **UI 组件**: shadcn/ui（`components/ui/`，new-york），条件类名用 `cn()`
- **表单**: react-hook-form + zodResolver（schema 推导 `FormValues`）
- **类型**: `import type`（verbatimModuleSyntax），DTO 镜像放 `types/`
- **测试**: Vitest no-globals（显式 import），纯逻辑优先，`__tests__/` 就近共置
- **质量门**: `bun run typecheck` && `bun run lint` && `bun run test:run` && `bun run build`（ESLint flat config：react-hooks v7 compiler 规则 + floating promise + import 方向强制；tsconfig 开 `noUncheckedIndexedAccess` / `erasableSyntaxOnly`）
