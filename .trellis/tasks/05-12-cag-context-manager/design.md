# CAG 上下文管理器 — 设计文档

> **任务编号**: 05-12-cag-context-manager
> **日期**: 2026-05-12
> **来源**: [超越 RAG：用 Spring Boot 构建具备上下文感知能力的 AI 系统](https://3g.k.sohu.com/t/n1004942643)
> **状态**: 设计评审中

---

## 一、背景与动机

### 1.1 核心思想（源自文章）

CAG（Context-Augmented Generation，上下文增强生成）提出：

- **RAG** 解决的是"**检索什么信息**"
- **CAG** 解决的是"**这些信息对谁相关、在什么情境下相关、受哪些约束**"

文章的核心观点：在企业系统中，仅靠语义检索不够，还需要在调用 LLM 之前，将用户身份、会话状态、业务策略等运行时上下文统一组织并注入到生成流程中。

### 1.2 chat-demo 现状与缺口

| 维度 | 现状 | 缺口 |
|------|------|------|
| 用户隔离 | `RagAdvisorFactory.create(userId)` 做 pgvector filter + BM25 WHERE 隔离 | userId 仅用于数据隔离，LLM 完全不知道"谁在问" |
| 角色权限 | `sys_user` + `sys_role` + `sys_permission` 三表 | 角色和权限不影响检索范围和生成行为 |
| 会话记忆 | `MessageChatMemoryAdvisor` | 已有，可作为上下文一部分 |
| 策略约束 | 无 | 没有"该角色能看到哪些文档 / 回答应遵守什么规则"的机制 |
| 上下文注入 | 无 | System prompt 是静态配置，不包含任何运行时上下文 |

**核心问题**：LLM 不知道用户的角色、权限、当前会话状态，因此无法给出差异化回答。管理员和普通用户问同一个问题，得到完全一样的回答。

---

## 二、设计目标

1. **将运行时上下文提升为一等架构要素**：用户画像、会话状态、策略约束统一管理
2. **OCP 遵循**：新增功能 = 新增类，不改旧类（检索器、后处理器不动）
3. **策略模式**：上下文收集的每个维度独立策略，可替换、可扩展
4. **渐进式引入**：最小化第一步（system prompt 注入），后续可扩展到检索过滤
5. **降级容错**：任何上下文解析失败，返回默认值，不阻断主流程
6. **可观测性**：上下文组装过程可审计、可追踪

---

## 三、架构设计

### 3.1 在现有流程中的位置

```
ChatController
  ↓
ChatServiceImpl.prepareContext()
  ↓
★ RequestContextManager（新增）     ← CAG 核心
  ↓ 组装 RequestContext
ChatAdvisorChainFactory.buildChain()
  ↓
ChatRequestSpecFactory.createSpec()
  ↓ ContextPromptInjector 将上下文注入 system prompt
ChatClient → LLM
```

**关键约束**：ContextManager 位于请求组装阶段，不修改现有的 RAG 组件（检索器、后处理器、Advisor）。

### 3.2 新增类清单

```
com.demo.chat.chat.context/
├── RequestContext.java              // 上下文值对象（不可变 record）
├── RequestContextManager.java       // 上下文管理器接口
├── DefaultRequestContextManager.java// 默认实现（组合三个策略）
├── UserProfileResolver.java        // 用户画像解析策略接口
├── DefaultUserProfileResolver.java // 默认：DB 读角色+权限
├── SessionContextResolver.java     // 会话上下文解析策略接口
├── DefaultSessionContextResolver.java// 默认：从 ChatMemory 读摘要
├── PolicyConstraintResolver.java   // 策略约束解析策略接口
├── DefaultPolicyConstraintResolver.java// 默认：基于角色生成约束文本
└── ContextPromptInjector.java      // 将 RequestContext 注入 system prompt
```

### 3.3 类关系

```
RequestContextManager (接口)
  └── DefaultRequestContextManager
        ├── UserProfileResolver (策略)
        │     └── DefaultUserProfileResolver
        ├── SessionContextResolver (策略)
        │     └── DefaultSessionContextResolver
        └── PolicyConstraintResolver (策略)
              └── DefaultPolicyConstraintResolver

ContextPromptInjector (工具类，消费 RequestContext)
```

---

## 四、详细设计

### 4.1 RequestContext（不可变值对象）

```java
/**
 * 请求上下文 — CAG 的核心数据结构
 * 
 * 聚合三类运行时信号：用户画像、会话状态、策略约束。
 * 不可变对象，线程安全，一次构建全程使用。
 */
public record RequestContext(
    UserContext user,
    SessionContext session,
    PolicyContext policy
) {
    /**
     * 生成注入 LLM system prompt 的上下文文本段
     */
    public String toPromptSegment() {
        StringBuilder sb = new StringBuilder();
        if (user != null) {
            sb.append("当前用户：").append(user.nickname())
              .append("，角色：").append(String.join("、", user.roles()));
        }
        if (session != null && session.messageCount() > 0) {
            sb.append("\n会话状态：已交流 ").append(session.messageCount()).append(" 轮");
        }
        if (policy != null && !policy.constraints().isEmpty()) {
            sb.append("\n回答约束：\n");
            policy.constraints().forEach(c -> sb.append("- ").append(c).append("\n"));
        }
        return sb.toString();
    }

    /**
     * 生成检索阶段的额外提示（预留，当前版本不使用）
     */
    public Map<String, Object> toRetrievalHints() {
        return Map.of();
    }
}

public record UserContext(Long userId, String nickname, Set<String> roles, Set<String> permissions) {}
public record SessionContext(String conversationId, int messageCount, String recentTopic) {}
public record PolicyContext(List<String> constraints, boolean ragRestricted) {}
```

### 4.2 RequestContextManager（接口 + 默认实现）

```java
/**
 * 请求上下文管理器 — CAG 架构的核心组件
 * 
 * 职责：收集并规范化运行时信号，生成统一的 RequestContext。
 * 约束：只编排和整理上下文，不做业务决策。
 */
public interface RequestContextManager {
    /**
     * 收集并组装当前请求的完整上下文
     *
     * @param userId          当前用户 ID
     * @param conversationId  隔离后的对话 ID
     * @param ragEnabled      是否启用 RAG
     * @param messageCount    当前会话消息数量（上游传入，避免重复查 ChatMemory）
     */
    RequestContext buildContext(Long userId, String conversationId, 
                                boolean ragEnabled, int messageCount);
}
```

```java
@Component
public class DefaultRequestContextManager implements RequestContextManager {

    private static final Logger log = LoggerFactory.getLogger(DefaultRequestContextManager.class);

    private final UserProfileResolver userResolver;
    private final SessionContextResolver sessionResolver;
    private final PolicyConstraintResolver policyResolver;

    public DefaultRequestContextManager(UserProfileResolver userResolver,
                                        SessionContextResolver sessionResolver,
                                        PolicyConstraintResolver policyResolver) {
        this.userResolver = userResolver;
        this.sessionResolver = sessionResolver;
        this.policyResolver = policyResolver;
    }

    /**
     * 收集并组装当前请求的完整上下文
     *
     * @param userId          当前用户 ID
     * @param conversationId  隔离后的对话 ID（修正 9.2）
     * @param ragEnabled      是否启用 RAG
     * @param messageCount    当前会话消息数量（修正 9.3：上游传入，避免重复查 ChatMemory）
     */
    @Override
    public RequestContext buildContext(Long userId, String conversationId, 
                                        boolean ragEnabled, int messageCount) {
        // 各维度独立收集，互不耦合
        UserContext user = resolveSafe(() -> userResolver.resolve(userId),
                UserContext.class, userId);
        SessionContext session = resolveSafe(() -> sessionResolver.resolve(conversationId, messageCount),
                SessionContext.class, conversationId);
        PolicyContext policy = resolveSafe(() -> policyResolver.resolve(user, ragEnabled),
                PolicyContext.class, ragEnabled);

        RequestContext ctx = new RequestContext(user, session, policy);

        log.info("CAG context assembled: userId={}, roles={}, ragEnabled={}, constraints={}",
                userId,
                user != null ? user.roles() : "unknown",
                ragEnabled,
                policy != null ? policy.constraints().size() : 0);

        return ctx;
    }

    /**
     * 安全解析：任何策略失败不阻断主流程，记录警告并返回 null
     */
    private <T> T resolveSafe(Supplier<T> resolver, Class<T> type, Object hint) {
        try {
            return resolver.get();
        } catch (Exception e) {
            log.warn("CAG resolver failed for {} (hint={}): {}", 
                    type.getSimpleName(), hint, e.getMessage());
            return null;
        }
    }
}
```

### 4.3 UserProfileResolver（策略接口 + 默认实现）

```java
/**
 * 用户画像解析策略
 * 
 * 从系统数据源解析用户的基本信息和角色/权限。
 * 默认实现从 sys_user / sys_role / sys_permission 表查询。
 * 可替换为 LDAP、OAuth2 userinfo 等外部数据源。
 */
public interface UserProfileResolver {
    UserContext resolve(Long userId);
}
```

```java
@Component
public class DefaultUserProfileResolver implements UserProfileResolver {

    private final SysUserMapper userMapper;
    private final SysUserRoleMapper userRoleMapper;
    private final SysRolePermissionMapper rolePermissionMapper;

    @Override
    public UserContext resolve(Long userId) {
        SysUser user = userMapper.selectById(userId);
        if (user == null) {
            return new UserContext(userId, "unknown", Set.of(), Set.of());
        }

        // 查询角色
        List<SysUserRole> userRoles = userRoleMapper.selectByUserId(userId);
        Set<String> roles = userRoles.stream()
                .map(ur -> /* 查 role name */ ...)
                .collect(Collectors.toSet());

        // 查询权限
        Set<String> permissions = /* 基于 roleIds 查 sys_permission */ ...;

        return new UserContext(userId, user.getNickname(), roles, permissions);
    }
}
```

### 4.4 SessionContextResolver（策略接口 + 默认实现）

```java
/**
 * 会话上下文解析策略
 * 
 * 基于上游已知的会话信息构建上下文。
 * 
 * 注意（修正 9.3）：不自行查 ChatMemory，避免与 ConversationContextAdvisor 重复查询。
 * 消息数量由上游传入。
 */
public interface SessionContextResolver {
    SessionContext resolve(String conversationId, int messageCount);
}
```

```java
@Component
public class DefaultSessionContextResolver implements SessionContextResolver {

    @Override
    public SessionContext resolve(String conversationId, int messageCount) {
        return new SessionContext(conversationId, messageCount, null);
    }
}
```

### 4.5 PolicyConstraintResolver（策略接口 + 默认实现）

```java
/**
 * 策略约束解析策略
 * 
 * 基于用户画像和请求参数，生成回答时应遵守的约束列表。
 * 这是 CAG 中最核心的策略——将业务规则转化为 LLM 可理解的指令。
 */
public interface PolicyConstraintResolver {
    PolicyContext resolve(UserContext user, boolean ragEnabled);
}
```

```java
@Component
public class DefaultPolicyConstraintResolver implements PolicyConstraintResolver {

    @Override
    public PolicyContext resolve(UserContext user, boolean ragEnabled) {
        List<String> constraints = new ArrayList<>();

        if (user == null) {
            return new PolicyContext(constraints, false);
        }

        // 基于角色生成约束
        if (user.roles().contains("ADMIN")) {
            constraints.add("你是管理员，可以访问所有信息");
        } else {
            constraints.add("仅基于用户有权访问的文档回答");
        }

        // RAG 相关约束
        if (ragEnabled) {
            constraints.add("优先使用检索到的知识库内容回答");
        }

        return new PolicyContext(constraints, false);
    }
}
```

> **注意**：`DefaultPolicyConstraintResolver` 的约束规则是初始版本。后续可通过配置文件或数据库驱动，不需要改代码（OCP）。

### 4.6 ContextPromptInjector（上下文注入器）

```java
/**
 * 上下文 Prompt 注入器
 * 
 * 将 RequestContext 转化为文本段，注入到 system prompt 中。
 * 注入位置：在原有 system prompt 之前，用明确的标记分隔。
 */
@Component
public class ContextPromptInjector {

    /**
     * 注入上下文到 system prompt
     * 
     * @param originalPrompt 原有 system prompt（可能为 null）
     * @param context        请求上下文
     * @return 增强后的 system prompt
     */
    public String inject(String originalPrompt, RequestContext context) {
        if (context == null) {
            return originalPrompt;
        }

        String contextSegment = context.toPromptSegment();
        if (contextSegment == null || contextSegment.isBlank()) {
            return originalPrompt;
        }

        String basePrompt = (originalPrompt != null && !originalPrompt.isBlank())
                ? originalPrompt : "你是一个 AI 助手。";

        return """
            [用户上下文]
            %s
            
            [系统指令]
            %s
            """.formatted(contextSegment, basePrompt);
    }
}
```

---

## 五、集成点

### 5.1 ChatRequestSpecFactory（修改点 1）

```java
// 新增注入
private final ContextPromptInjector contextPromptInjector;

// createSpec 新增 RequestContext 参数（修正 9.1：避免重复查 userId）
public ChatClient.ChatClientRequestSpec createSpec(
        ChatClient chatClient, ModelRouter.Route route,
        ChatRequest request, String conversationId, ChatModeStrategy modeStrategy,
        RequestContext cagContext) {  // ← 由调用方传入，不再内部调 SecurityUtils

    List<Advisor> advisors = advisorChainFactory.buildChain(conversationId, request, modeStrategy);
    ChatClient.ChatClientRequestSpec spec = chatClient.prompt()
            .user(request.message())
            .advisors(advisors);

    if (advisorChainFactory.hasTools()) {
        spec = spec.tools((Object) advisorChainFactory.getToolCallbacks());
    }

    // CAG: 注入 system prompt
    String systemPrompt = resolveSystemPrompt(route);
    systemPrompt = contextPromptInjector.inject(systemPrompt, cagContext);
    if (systemPrompt != null && !systemPrompt.isBlank()) {
        spec = spec.system(systemPrompt);
    }

    ChatOptions options = resolveChatOptions(route);
    if (options != null) {
        spec = spec.options(options);
    }

    return spec;
}
```

### 5.2 RagAdvisorFactory（预留扩展点）

```java
// 未来版本：create 方法可接收 RequestContext
public RetrievalAugmentationAdvisor create(Long userId, RequestContext context) {
    // 如果 context.policy().ragRestricted() == true，可限制检索范围
    // 当前版本不修改
    ...
}
```

### 5.3 配置开关

```yaml
# application.yml
app:
  cag:
    enabled: true                    # CAG 总开关
    inject-prompt: true              # 是否注入到 system prompt
    log-context: true                # 是否记录上下文日志（可观测性）
```

对应配置类：

```java
@Component
@ConfigurationProperties(prefix = "app.cag")
public class CagProperties {
    private boolean enabled = true;
    private boolean injectPrompt = true;
    private boolean logContext = true;
    // getter/setter
}
```

---

## 六、设计原则对照

| 原则 | 体现 |
|------|------|
| **OCP（开闭原则）** | 新增 `context` 包 10 个类，不修改检索器/后处理器。扩展约束规则时加新 Resolver 实现，不改 DefaultPolicyConstraintResolver |
| **策略模式** | 三个 Resolver 接口，每个有默认实现，可独立替换 |
| **关注点分离** | ContextManager 只编排，不决策；检索器只检索；LLM 只生成 |
| **封装彻底** | LLM 和检索器不知道上下文从哪来，只消费 `RequestContext` record |
| **渐进式引入** | 第一步只注入 system prompt，不改 RAG 组件。后续可扩展到检索过滤 |
| **降级容错** | `resolveSafe()` 包裹每个 Resolver，失败返回 null 不阻断主流程 |
| **可测试** | 每个 Resolver 独立单测；`RequestContext` 是纯 record，无需 mock |
| **可观测** | 每次请求记录上下文摘要日志；CAG 开关可关闭 |

---

## 七、新增同类功能的步骤（OCP 验证）

**场景**：需要新增"基于部门的数据隔离"策略

1. 新增 `DepartmentContextResolver` 接口和默认实现
2. 扩展 `RequestContext` record，新增 `DepartmentContext` 字段
3. 在 `DefaultRequestContextManager` 中注入新的 Resolver
4. 在 `PolicyConstraintResolver` 实现中引用部门信息

**不需要修改的类**：`HybridDocumentRetriever`、`BailianRerankPostProcessor`、`MmrDocumentPostProcessor`、`ParentDocumentPostProcessor`、`QueryNormalizer`

---

## 八、实现计划

| 步骤 | 内容 | 依赖 |
|------|------|------|
| P0 | 创建 `context` 包、`RequestContext` 及子 record | 无 |
| P1 | 实现 `UserProfileResolver` + 默认实现 | SysUser/SysRole Mapper |
| P2 | 实现 `SessionContextResolver` + 默认实现 | ChatMemory |
| P3 | 实现 `PolicyConstraintResolver` + 默认实现 | 无 |
| P4 | 实现 `DefaultRequestContextManager` | P1-P3 |
| P5 | 实现 `ContextPromptInjector` | P0 |
| P6 | 修改 `ChatRequestSpecFactory` 集成 CAG | P4, P5 |
| P7 | 新增 `CagProperties` 配置类 + yml 配置 | 无 |
| P8 | 单元测试（每个 Resolver + ContextManager + Injector） | P0-P6 |
| P9 | 集成测试（端到端验证 prompt 注入效果） | P8 |

---

## 九、设计评审修正（Self-Critique 2026-05-12）

以下问题在设计评审中发现并修正：

### 9.1 `createSpec()` 消除重复 userId 查询

**问题**：`ChatServiceImpl.prepareContext()` 已调用 `SecurityUtils.getCurrentUserId()`，`ChatRequestSpecFactory.createSpec()` 内部又调用一次。

**修正**：`createSpec()` 新增 `RequestContext` 参数，由调用方（`ChatServiceImpl`）统一构建并传入。

```java
// 修改前
ChatClient.ChatClientRequestSpec createSpec(ChatClient chatClient, ModelRouter.Route route,
    ChatRequest request, String conversationId, ChatModeStrategy modeStrategy);

// 修改后
ChatClient.ChatClientRequestSpec createSpec(ChatClient chatClient, ModelRouter.Route route,
    ChatRequest request, String conversationId, ChatModeStrategy modeStrategy,
    RequestContext cagContext);  // 新增参数
```

### 9.2 conversationId 约定

**问题**：传给 `RequestContextManager` 的 conversationId 是原始值还是隔离后的值不明确。

**修正**：**必须传隔离后的 conversationId**（`ConversationIdUtil.buildIsolatedId(userId, rawId)` 的结果），因为 `SessionContextResolver` 内部用它与 `ChatMemory` 交互，ChatMemory 存储的是隔离后的 ID。

### 9.3 SessionContextResolver 避免重复查 ChatMemory

**问题**：`ConversationContextAdvisor` 在 Advisor 链中会访问 ChatMemory，`SessionContextResolver` 又查一次，重复。

**修正**：`SessionContextResolver.resolve()` 改为接收消息数量参数，由上游传入（上游可从 Advisor 链构建阶段已知的上下文中获取）。

```java
// 修改前
SessionContext resolve(String conversationId);

// 修改后
SessionContext resolve(String conversationId, int messageCount);
```

### 9.4 RequestContext record 扩展性

**问题**：record 加字段 = 改构造函数，似乎违反 OCP。

**结论**：`RequestContext` 是内部组装容器，不是对外契约。下游（检索器、LLM）完全不依赖这个类的具体字段，只消费 `toPromptSegment()` 的输出。加字段只影响 Manager 的组装逻辑和 `toPromptSegment()` 方法，影响面可控。这是可接受的 trade-off。

### 9.5 已知技术债务（v2 优化）

| 债务 | 当前做法 | v2 目标 |
|------|----------|--------|
| PolicyConstraintResolver 硬编码角色映射 | Java if-else | 配置驱动（yml 或数据库） |
| ContextPromptInjector 模板硬编码 | Java 文本块 | 模板外置到 SystemPromptService 或配置 |
| 用户画像无缓存 | 每次查 DB | `@Cacheable` + Caffeine 本地缓存 |

---

## 十、风险与缓解

| 风险 | 缓解 |
|------|------|
| Prompt 注入增加 token 消耗 | 上下文段控制在 200 token 以内，可通过 `CagProperties` 关闭 |
| 用户信息泄露到 LLM | `ContextPromptInjector` 只注入昵称和角色，不注入敏感字段 |
| Resolver 查询增加延迟 | 用户画像可通过缓存优化；Session 查询已有 ChatMemory |
| 上下文段影响 LLM 输出质量 | 可通过 A/B 测试对比有无 CAG 的回答质量 |
| record 扩展性 | 内部容器，非公共 API，影响面可控（见 9.4） |
