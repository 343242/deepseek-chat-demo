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
- [ ] [Logging Guidelines](./logging-guidelines.md) — 日志级别和规范

---

## Guidelines Index

| Guide | Description | Status |
|-------|-------------|--------|
| [Directory Structure](./directory-structure.md) | 模块组织、文件布局、命名规则 | ✅ Filled |
| [Database Guidelines](./database-guidelines.md) | MyBatis-Plus、Schema、Redis、Caffeine | ✅ Filled |
| [Error Handling](./error-handling.md) | 异常体系、错误格式、校验模式 | ✅ Filled |
| [Quality Guidelines](./quality-guidelines.md) | 安全检查、禁止模式、DTO 规则 | ✅ Filled |
| [Logging Guidelines](./logging-guidelines.md) | 日志级别、Profile 差异 | ✅ Filled |

---

## Quick Reference

- **ORM**: MyBatis-Plus（禁 JPA）
- **事务**: TransactionTemplate（禁 @Transactional）
- **异常**: BusinessException（禁 IllegalArgumentException）
- **DTO**: record + @Valid
- **Token**: HttpOnly Cookie（禁 JSON body 返回）
- **状态字段**: 枚举校验（禁裸 Integer）
- **Schema**: Flyway（`db/migration/`）+ `sql/schema.sql` 参考
- **Docker**: 仅 bookworm 变体，需授权后拉取
