# Directory Structure

> How backend code is organized in this project.

---

## Overview

Java 21 + Spring Boot 3.5 monolith，按功能模块分包。所有代码在 `com.demo.deepseekchat` 下。

---

## Directory Layout

```
src/main/java/com/demo/deepseekchat/
├── DeepseekChatApplication.java          # @MapperScan 启动类
│
├── common/                               # 公共模块
│   └── snowflake/                        #   自研雪花 ID 生成器
│       ├── SnowflakeProperties.java      #     配置
│       ├── SnowflakeIdGenerator.java     #     核心算法
│       └── SnowflakeConfiguration.java   #     Spring Bean
│
├── config/                               # 基础配置（无业务逻辑）
│   ├── DeepSeekAutoConfiguration.java    #   模型列表拉取 + ChatClient 注册
│   ├── AdvisorAutoConfiguration.java     #   Advisor 编排
│   ├── MyBatisPlusConfig.java            #   分页插件
│   ├── RedisConfig.java                  #   Redis 序列化
│   └── TransactionConfig.java            #   TransactionTemplate Bean
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
├── chat/                                 # ChatClient 管理层
│   ├── ChatClientFactory.java            #   构建工厂
│   └── ChatClientRegistry.java           #   注册中心
│
├── advisor/                              # Spring AI Advisor 链
│   ├── RateLimiter.java                  #   限流器接口
│   ├── TokenBucketLimiter.java           #   令牌桶实现
│   ├── RateLimitAdvisor.java             #   限流 Advisor (order=0)
│   ├── ContentFilterAdvisor.java         #   内容安全 Advisor (order=1)
│   └── ConversationContextAdvisor.java   #   对话上下文注入
│
├── content/                              # 内容安全
│   ├── ContentFilterService.java         #   过滤服务接口
│   └── SensitiveWordFilterService.java   #   sensitive-word DFA 实现
│
├── service/                              # 业务服务
│   ├── ChatService.java                  #   聊天（阻塞 + 流式 + 记忆）
│   ├── ModelService.java                 #   模型管理
│   ├── ConversationService.java          #   对话管理
│   ├── SystemPromptService.java          #   System Prompt（Caffeine 缓存）
│   ├── PromptLoaderService.java          #   XML 模板加载
│   ├── ModelParamsService.java           #   模型参数（Caffeine 缓存）
│   └── UsageService.java                 #   用量统计
│
├── controller/                           # REST 接口
│   ├── ChatController.java               #   /api/chat, /api/models
│   ├── ConversationController.java       #   /api/conversations
│   ├── PromptController.java             #   /api/prompts
│   ├── ModelParamsController.java        #   /api/params
│   └── UsageController.java              #   /api/usage
│
├── model/                                # 聊天业务模型
│   ├── entity/                           #   SystemPrompt, ModelParams, TokenUsage
│   ├── mapper/                           #   MyBatis-Plus Mapper
│   └── dto/                              #   ChatRequest, ChatResponse, ...
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
| 功能模块（有独立 entity/mapper/service/controller） | `src/.../模块名/` | `user/`, `model/` |
| 跨模块基础设施工具 | `common/` | `common/snowflake/` |
| 安全相关 | `security/` | JWT, 验证码, 过滤器 |
| Spring AI 相关 | `advisor/`, `chat/`, `content/` | Advisor 链, ChatClient |
| 异常 | `exception/` | 全局异常处理 |

**规则：**
- Controller 只做参数接收和响应封装，业务逻辑下沉到 Service
- Entity 不暴露给前端，通过 DTO 隔离
- 一个功能模块的 entity/mapper/service/dto/controller 放在同一个包下

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
