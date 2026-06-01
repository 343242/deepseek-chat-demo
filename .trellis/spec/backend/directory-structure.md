# Directory Structure

> How backend code is organized in this project.

---

## Overview

Java 21 + Spring Boot 3.5 monolith，按功能模块分包。所有代码在 `com.smart.rag` 下。

---

## Directory Layout

```
src/main/java/com/smart/rag/
├── SmartRagApplication.java              # @MapperScan 启动类
│
├── common/                               # 通用工具和跨模块基础能力
│   ├── concurrent/                       #   结构化并发基础设施
│   ├── request/                          #   通用请求对象
│   ├── response/                         #   通用响应对象
│   └── util/                             #   通用工具
│
├── config/                               # Spring 配置装配（无业务逻辑）
│   ├── ModelProviderAutoConfiguration.java
│   ├── AdvisorAutoConfiguration.java     #   Advisor 编排
│   ├── RedisConfig.java                  #   Redis 序列化
│   └── TransactionConfig.java            #   TransactionTemplate Bean
│
├── infrastructure/                       # 与业务无关的技术基础设施
│   ├── ai/                               #   Spring AI/模型厂商/Advisor/记忆/降级
│   │   ├── advisor/
│   │   ├── client/
│   │   ├── content/
│   │   ├── fallback/
│   │   ├── memory/
│   │   └── provider/
│   └── agent/                            #   Agent 运行时支撑能力
│       ├── guardrail/
│       ├── trace/
│       └── workspace/
│
├── security/                             # 安全模块（认证/授权/验证码）
│   ├── config/                           #   SecurityConfig, JwtProperties
│   ├── dto/                              #   CaptchaResult
│   ├── filter/                           #   JwtAuthenticationFilter
│   ├── service/                          #   TokenCacheService, CaptchaService
│   └── util/                             #   JwtTokenProvider, SecurityUtils
│
├── user/                                 # RBAC 用户模块
│   ├── entity/                           #   SysUser, SysRole, SysPermission, SysUserRole
│   ├── enums/                            #   UserStatus
│   ├── mapper/                           #   MyBatis-Plus BaseMapper + 自定义查询
│   ├── service/                          #   AuthService, SysUserService, SysRoleService
│   ├── dto/                              #   Login/Register/Refresh 等 DTO
│   └── controller/                       #   AuthController, UserController, RoleController
│
├── chat/                                 # 对话业务模块
│   ├── controller/
│   ├── dto/
│   ├── entity/
│   ├── mapper/
│   ├── mode/
│   └── service/
│
├── rag/                                  # RAG 文档、切分、检索、ETL 业务模块
│   ├── chunk/
│   ├── controller/
│   ├── embedding/
│   ├── etl/
│   ├── parser/
│   ├── retrieval/
│   └── service/
│
├── evaluation/                           # RAG 评估业务模块（顶层独立域）
│   ├── config/
│   ├── controller/
│   ├── dataset/
│   ├── judge/
│   ├── metrics/
│   ├── result/
│   └── runner/
│
└── exception/                            # 异常处理
    ├── GlobalExceptionHandler.java       #   统一错误响应
    ├── BusinessException.java            #   业务异常
    └── RateLimitExceededException.java   #   限流异常
```

---

## Module Organization

新功能按以下规则放置：

| 类型 | 位置 | 示例 |
|------|------|------|
| 功能模块（有独立 entity/mapper/service/controller） | `src/.../模块名/` | `user/`, `chat/`, `rag/`, `evaluation/` |
| 业务无关技术基础设施 | `infrastructure/` | `infrastructure/ai/provider/`, `infrastructure/agent/workspace/` |
| 跨模块通用工具/值对象 | `common/` | `common/concurrent/`, `common/response/` |
| 安全相关 | `security/` | JWT, 验证码, 过滤器 |
| Spring AI 技术支撑 | `infrastructure/ai/` | Advisor 链, ChatClient 注册, Provider, 记忆, 降级 |
| 异常 | `exception/` | 全局异常处理 |

**规则：**
- Controller 只做参数接收和响应封装，业务逻辑下沉到 Service
- Entity 不暴露给前端，通过 DTO 隔离
- 一个功能模块的 entity/mapper/service/dto/controller 放在同一个包下
- 与业务语义无关、会被多个模块复用的技术对象放入 `infrastructure/`，不要藏在 `chat/`、`rag/`、`agent/` 等业务模块下
- `evaluation/` 是顶层业务域，不放在 `rag/evaluation/` 下；它可以依赖 RAG 业务服务，但自身的数据集、指标、运行记录和控制器保持独立包边界

---

## Naming Conventions

| 类别 | 命名规则 | 示例 |
|------|---------|------|
| Entity | PascalCase，表名去前缀 | `SysUser`, `TokenUsage` |
| Mapper | `{Entity}Mapper` | `SysUserMapper` |
| Service | `{模块}Service` | `AuthService`, `ChatService` |
| Controller | `{模块}Controller` | `AuthController` |
| DTO (Request) | `{动作}Request` | `LoginRequest`, `ChatRequest` |
| DTO (Response) | `{动作}Response` | `LoginResponse`, `ChatResponse` |
| DTO (通用) | `{实体}DTO` | `TokenUsageDTO`, `SystemPromptDTO` |
| 配置类 | `{功能}Config` / `{功能}Configuration` | `RedisConfig` |
| Advisor | `{功能}Advisor` | `RateLimitAdvisor` |

---

## Examples

良好组织的模块参考：
- `user/` — 完整的 entity/mapper/service/dto/controller 分层
- `security/` — 独立的安全子模块，内部按 config/dto/filter/service/util 分包
