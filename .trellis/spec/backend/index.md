# Backend Development Guidelines

> Best practices for backend development in this project.

---

## Overview

Spring Boot 3.5 + MyBatis-Plus + PostgreSQL + Redis 的 DeepSeek 聊天助手后端。

---

## Pre-Development Checklist

开始编码前，确认已阅读：

- [ ] [Directory Structure](./directory-structure.md) — 模块组织和命名规则
- [ ] [Database Guidelines](./database-guidelines.md) — ORM 用法、Schema 管理、Redis/缓存策略
- [ ] [Error Handling](./error-handling.md) — 异常体系和统一错误格式
- [ ] [Quality Guidelines](./quality-guidelines.md) — 设计原则、安全检查清单、禁止模式
- [ ] [Code Review Checklist](./code-review-checklist.md) — Review 维度、检查项、常见陷阱
- [ ] [Logging Guidelines](./logging-guidelines.md) — 日志级别和规范
- [ ] [LLM SPI](./llm-spi.md) — 基础设施层 LLM 客户端契约（禁止注入 `ChatClient.Builder`）
- [ ] [RAG 引用与上下文工程](./rag-citation-context.md) — RetrievedDocument 契约、检索段 SystemMessage 注入、静态/动态 prompt 拆分（改 RAG 检索/prompt/DTO 前必读）

---

## Guidelines Index

| Guide | Description | Status |
|-------|-------------|--------|
| [Directory Structure](./directory-structure.md) | 模块组织、文件布局、命名规则 | ✅ Filled |
| [Database Guidelines](./database-guidelines.md) | MyBatis-Plus、Schema、Redis、Caffeine | ✅ Filled |
| [Error Handling](./error-handling.md) | 异常体系、错误格式、校验模式 | ✅ Filled |
| [Quality Guidelines](./quality-guidelines.md) | 安全检查、禁止模式、DTO 规则 | ✅ Filled |
| [Code Review Checklist](./code-review-checklist.md) | Review 维度、检查项、常见陷阱 | ✅ Filled |
| [Logging Guidelines](./logging-guidelines.md) | 日志级别、Profile 差异 | ✅ Filled |
| [LLM SPI](./llm-spi.md) | LLM 客户端契约、Resolver 模式、fail-fast | ✅ Filled |
| [RAG 引用与上下文工程](./rag-citation-context.md) | RetrievedDocument 契约、Reference DTO、`<<REF>>` SystemMessage 注入、静态/动态 prompt 拆分 | ✅ Filled |

---

## Quick Reference

- **ORM**: MyBatis-Plus（禁 JPA）
- **事务**: TransactionTemplate（禁 @Transactional）
- **异常**: ClientException / ServiceException / RemoteException 三级体系（禁 IllegalArgumentException，禁 BusinessException）
- **DTO**: record + @Valid
- **Token**: HttpOnly Cookie（禁 JSON body 返回）
- **状态字段**: 枚举校验（禁裸 Integer）
- **Schema**: Flyway（`db/migration/`）+ `sql/schema.sql` 参考
- **Docker**: 仅 bookworm 变体，需授权后拉取
