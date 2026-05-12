# CAG 上下文管理器 — 设计文档

> **任务编号**: 05-12-cag-context-manager
> **日期**: 2026-05-12
> **来源**: [超越 RAG：用 Spring Boot 构建具备上下文感知能力的 AI 系统](https://3g.k.sohu.com/t/n1004942643)
> **状态**: 设计评审通过（v2 修正版）

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
2. **OCP 遵循**：新增功能 = 新增类，检索器/后处理器不动
3. **策略模式**：上下文收集的每个维度独立策略，可替换、可扩展
4. **渐进式引入**：最小化第一步（system prompt 注入），后续可扩展到检索过滤
5. **降级容错**：任何上下文解析失败，返回默认值，不阻断主流程
6. **可观测性**：上下文组装过程可审计、可追踪
7. **安全纵深**：注入 LLM 的文本必须做 sanitize，防止间接 prompt injection

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

### 3.2 会修改的旧类 vs 不会修改的旧类

**会修改的旧类**：

| 类 | 修改内容 | 影响面 |
|----|----------|--------|
| `ChatServiceImpl` | 新增 `RequestContextManager` + `CagProperties` 注入；`doChat()`/`doStream()` 中构建 CAG 上下文并传给 `createSpec()` | 注入扩展 + 参数传递 |
| `ChatRequestSpecFactory` | `createSpec()` 新增 `RequestContext` 参数；新增 `ContextPromptInjector` 注入 | 签名扩展 + prompt 增强 |

**不会修改的旧类**：

| 类 | 原因 |
|----|------|
| `RagAdvisorFactory` | 用户隔离逻辑不变 |
| `HybridDocumentRetriever` | 检索逻辑不变 |
| `BailianRerankPostProcessor` | 后处理器不变 |
| `MmrDocumentPostProcessor` | 后处理器不变 |
| `ParentDocumentPostProcessor` | 后处理器不变 |
| `QueryNormalizer` | 查询归一化不变 |
| `ChatAdvisorChainFactory` | Advisor 链构建逻辑不变 |
| `RagConfig` | Bean 定义不变 |

### 3.3 新增类清单

```
com.demo.chat.chat.context/
├── RequestContext.java              // 上下文值对象（不可变 record）
├── RequestContextManager.java       // 上下文管理器接口
├── DefaultRequestContextManager.java// 默认实现（组合三个策略）
├── UserProfileResolver.java        // 用户画像解析策略接口
├── DefaultUserProfileResolver.java // 默认：DB 读角色+权限
├── SessionContextResolver.java     // 会话上下文解析策略接口
├── DefaultSessionContextResolver.java// 默认：推断对话阶段
├── PolicyConstraintResolver.java   // 策略约束解析策略接口
├── DefaultPolicyConstraintResolver.java// 默认：基于角色生成约束文本
├── ContextPromptInjector.java      // 将 RequestContext 注入 system prompt
└── CagProperties.java              // CAG 配置开关
```

### 3.4 类关系

```
RequestContextManager (接口)
  └── DefaultRequestContextManager
        ├── UserProfileResolver (策略)
        │     └── DefaultUserProfileResolver → SysUserMapper, SysUserRoleMapper, SysRoleMapper
        ├── SessionContextResolver (策略)
        │     └── DefaultSessionContextResolver (推断对话阶段)
        └── PolicyConstraintResolver (策略)
              └── DefaultPolicyConstraintResolver (角色→约束映射)

ContextPromptInjector (工具类，消费 RequestContext + CagProperties)
CagProperties (@ConfigurationProperties)
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
     * 
     * 安全边界：只输出昵称和角色名，不输出权限列表（permissions）、
     * 用户 ID、邮箱等敏感字段。
     */
    public String toPromptSegment() {
        StringBuilder sb = new StringBuilder();
        if (user != null) {
            sb.append("当前用户：").append(sanitize(user.nickname()))
              .append("，角色：").append(user.roles().stream()
                      .map(RequestContext::sanitize)
                      .collect(Collectors.joining("、")));
        }
        if (session != null && session.stage() != null) {
            sb.append("\n对话阶段：").append(sanitize(session.stage()));
        }
        if (policy != null && !policy.constraints().isEmpty()) {
            sb.append("\n回答约束：\n");
            policy.constraints().forEach(c -> sb.append("- ").append(sanitize(c)).append("\n"));
        }
        return sb.toString();
    }

    /**
     * 清理注入文本，移除控制字符，防止间接 prompt injection。
     * 防御纵深：即使角色名由管理员设置，注入 LLM 的文本也应做清理。
     */
    private static String sanitize(String input) {
        if (input == null) return "";
        return input.replaceAll("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F]", "")
                     .replaceAll("[\\r\\n]", " ");
    }

    /**
     * 生成检索阶段的额外提示（预留，当前版本不使用）
     */
    public Map<String, Object> toRetrievalHints() {
        return Map.of();
    }
}

/** 用户画像（安全边界：permissions 不注入到 LLM，仅供内部策略判断） */
public record UserContext(Long userId, String nickname, Set<String> roles, Set<String> permissions) {}

/** 会话上下文（stage 由 SessionContextResolver 推断） */
public record SessionContext(String conversationId, int messageCount, String stage) {}

/** 策略约束 */
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
     * @param conversationId  隔离后的对话 ID（必须传 ConversationIdUtil.buildIsolatedId 的结果）
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
    private final CagProperties cagProperties;

    public DefaultRequestContextManager(UserProfileResolver userResolver,
                                        SessionContextResolver sessionResolver,
                                        PolicyConstraintResolver policyResolver,
                                        CagProperties cagProperties) {
        this.userResolver = userResolver;
        this.sessionResolver = sessionResolver;
        this.policyResolver = policyResolver;
        this.cagProperties = cagProperties;
    }

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

        if (cagProperties.isLogContext()) {
            log.info("CAG context assembled: userId={}, roles={}, ragEnabled={}, constraints={}",
                    userId,
                    user != null ? user.roles() : "unknown",
                    ragEnabled,
                    policy != null ? policy.constraints().size() : 0);
        }

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
    private final SysRoleMapper roleMapper;
    private final SysRolePermissionMapper rolePermissionMapper;

    @Override
    public UserContext resolve(Long userId) {
        SysUser user = userMapper.selectById(userId);
        if (user == null) {
            return new UserContext(userId, "unknown", Set.of(), Set.of());
        }

        // 1. 查询用户关联的角色 ID
        List<SysUserRole> userRoles = userRoleMapper.selectByUserId(userId);
        List<Long> roleIds = userRoles.stream()
                .map(SysUserRole::getRoleId)
                .toList();

        if (roleIds.isEmpty()) {
            return new UserContext(userId, user.getNickname(), Set.of(), Set.of());
        }

        // 2. 批量查角色名（避免 N+1）
        List<SysRole> roles = roleMapper.selectByIds(roleIds);
        Set<String> roleNames = roles.stream()
                .map(SysRole::getRoleName)
                .collect(Collectors.toSet());

        // 3. 批量查权限
        Set<String> permissions = rolePermissionMapper.selectPermissionsByRoleIds(roleIds)
                .stream()
                .map(SysPermission::getPermissionName)
                .collect(Collectors.toSet());

        return new UserContext(userId, user.getNickname(), roleNames, permissions);
    }
}
```

> **依赖**：需要在 `SysRoleMapper` 中新增 `List<SysRole> selectByIds(List<Long> ids)` 批量方法。

### 4.4 SessionContextResolver（策略接口 + 默认实现）

```java
/**
 * 会话上下文解析策略
 * 
 * 基于上游传入的消息数量，推断对话阶段并构建会话上下文。
 * 不自行查 ChatMemory，避免与 ConversationContextAdvisor 重复查询。
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
        String stage = inferStage(messageCount);
        return new SessionContext(conversationId, messageCount, stage);
    }

    /**
     * 从消息数量推断对话阶段，帮助 LLM 理解当前对话的深度
     */
    private String inferStage(int messageCount) {
        if (messageCount == 0) return "首次对话";
        if (messageCount < 5) return "对话初期";
        if (messageCount < 15) return "深入交流";
        return "长对话";
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

        // 基于角色生成约束（v1 硬编码，v2 改为配置驱动）
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

> **注意**：`DefaultPolicyConstraintResolver` 的约束规则是 v1 硬编码版本。v2 改为配置驱动（yml 或数据库映射），加新约束不改代码。

### 4.6 ContextPromptInjector（上下文注入器）

```java
/**
 * 上下文 Prompt 注入器
 * 
 * 将 RequestContext 转化为文本段，注入到 system prompt 中。
 * 注入位置：在原有 system prompt 之前，用明确的标记分隔。
 * 受 CagProperties.injectPrompt 开关控制。
 */
@Component
public class ContextPromptInjector {

    private final CagProperties cagProperties;

    public ContextPromptInjector(CagProperties cagProperties) {
        this.cagProperties = cagProperties;
    }

    /**
     * 注入上下文到 system prompt
     * 
     * @param originalPrompt 原有 system prompt（可能为 null）
     * @param context        请求上下文
     * @return 增强后的 system prompt
     */
    public String inject(String originalPrompt, RequestContext context) {
        if (!cagProperties.isInjectPrompt()) {
            return originalPrompt;
        }

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

### 4.7 CagProperties（配置类）

```java
/**
 * CAG 上下文增强配置
 * 
 * 对应 application.yml 中 app.cag.* 配置项。
 * 所有开关默认开启，可按需关闭。
 */
@Component
@ConfigurationProperties(prefix = "app.cag")
public class CagProperties {
    /** 是否启用 CAG（总开关，关闭后不构建上下文） */
    private boolean enabled = true;
    /** 是否将上下文注入到 system prompt */
    private boolean injectPrompt = true;
    /** 是否记录上下文组装日志（可观测性） */
    private boolean logContext = true;
    // getter/setter
}
```

配置文件：

```yaml
# application.yml
app:
  cag:
    enabled: true       # CAG 总开关
    inject-prompt: true  # 是否注入到 system prompt
    log-context: true    # 是否记录上下文日志
```

---

## 五、集成点

### 5.1 ChatServiceImpl（修改点 1：构建 CAG 上下文）

```java
@Service
public class ChatServiceImpl implements ChatService {

    // 新增注入
    private final RequestContextManager cagContextManager;
    private final CagProperties cagProperties;

    public ChatServiceImpl(ChatClientRegistry registry,
                           ModelRouter modelRouter,
                           ModeRouter modeRouter,
                           ChatRequestSpecFactory requestSpecFactory,
                           UsageService usageService,
                           ChatMemory chatMemory,
                           ChatFallbackProperties fallbackProperties,
                           FallbackChainProvider fallbackChainProvider,
                           FallbackEligibility fallbackEligibility,
                           StreamRetryHandler streamRetryHandler,
                           RequestContextManager cagContextManager,   // 新增
                           CagProperties cagProperties) {             // 新增
        // ... 现有赋值
        this.cagContextManager = cagContextManager;
        this.cagProperties = cagProperties;
    }

    private ChatResponse doChat(ChatRequest request, FallbackMeta fallback) {
        ChatContext ctx = prepareContext(request);

        // CAG: 构建上下文（仅在 CAG 启用时）
        RequestContext cagCtx = buildCagContext(ctx, request);

        ChatClient.ChatClientRequestSpec requestSpec = requestSpecFactory.createSpec(
                ctx.chatClient, ctx.route, request, ctx.conversationId, ctx.modeStrategy,
                cagCtx);  // ← 传入 CAG 上下文

        // ... 后续逻辑不变
    }

    private Flux<String> doStream(String modelId, ChatRequest request) {
        ChatContext ctx = prepareContext(request);

        // CAG: 构建上下文（仅在 CAG 启用时）
        RequestContext cagCtx = buildCagContext(ctx, request);

        ChatClient.ChatClientRequestSpec requestSpec = requestSpecFactory.createSpec(
                ctx.chatClient, ctx.route, request, ctx.conversationId, ctx.modeStrategy,
                cagCtx);  // ← 传入 CAG 上下文

        // ... 后续逻辑不变
    }

    /**
     * 构建 CAG 上下文
     * 
     * @return RequestContext，CAG 未启用时返回 null
     */
    private RequestContext buildCagContext(ChatContext ctx, ChatRequest request) {
        if (!cagProperties.isEnabled()) {
            return null;
        }
        int msgCount = chatMemory.get(ctx.conversationId).size();
        return cagContextManager.buildContext(
                ctx.userId, ctx.conversationId, request.isRagEnabled(), msgCount);
    }

    /**
     * 请求上下文 — 扩展以包含 userId（原实现中 userId 在 prepareContext 内局部使用）
     */
    private static class ChatContext {
        final ChatClient chatClient;
        final ModelRouter.Route route;
        final String conversationId;
        final ChatModeStrategy modeStrategy;
        final Long userId;            // 新增：供 CAG 使用
        final long startTimeMs;

        ChatContext(ChatClient chatClient, ModelRouter.Route route,
                    String conversationId, ChatModeStrategy modeStrategy, Long userId) {
            this.chatClient = chatClient;
            this.route = route;
            this.conversationId = conversationId;
            this.modeStrategy = modeStrategy;
            this.userId = userId;
            this.startTimeMs = System.currentTimeMillis();
        }

        long elapsed() {
            return System.currentTimeMillis() - startTimeMs;
        }
    }
}
```

### 5.2 ChatRequestSpecFactory（修改点 2：接收 CAG 上下文）

```java
@Component
public class ChatRequestSpecFactory {

    private final ChatAdvisorChainFactory advisorChainFactory;
    private final SystemPromptService systemPromptService;
    private final ModelParamsService modelParamsService;
    private final ProviderRegistry providerRegistry;
    private final ContextPromptInjector contextPromptInjector;  // 新增注入

    public ChatClient.ChatClientRequestSpec createSpec(
            ChatClient chatClient, ModelRouter.Route route,
            ChatRequest request, String conversationId, ChatModeStrategy modeStrategy,
            RequestContext cagContext) {  // ← 新增参数

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
}
```

### 5.3 RagAdvisorFactory（预留扩展点）

```java
// 未来版本：create 方法可接收 RequestContext
public RetrievalAugmentationAdvisor create(Long userId, RequestContext context) {
    // 如果 context.policy().ragRestricted() == true，可限制检索范围
    // 当前版本不修改
    ...
}
```

---

## 六、设计原则对照

| 原则 | 体现 |
|------|------|
| **OCP（开闭原则）** | 新增 `context` 包 11 个类；仅修改 2 个旧类的签名/注入。检索器、后处理器、Advisor 工厂不变 |
| **策略模式** | 三个 Resolver 接口，每个有默认实现，可独立替换 |
| **关注点分离** | ContextManager 只编排，不决策；检索器只检索；LLM 只生成 |
| **封装彻底** | LLM 和检索器不知道上下文从哪来，只消费 `RequestContext` record |
| **渐进式引入** | `CagProperties.enabled` 总开关，关闭后 `RequestContext` 为 null，零风险上线 |
| **降级容错** | `resolveSafe()` 包裹每个 Resolver，失败返回 null 不阻断主流程 |
| **可测试** | 每个 Resolver 独立单测；`RequestContext` 是纯 record，无需 mock |
| **可观测** | `CagProperties.logContext` 控制日志；MDC 字段可扩展（v2） |
| **安全纵深** | `toPromptSegment()` 中 `sanitize()` 清理控制字符；permissions 不注入 LLM |

---

## 七、新增同类功能的步骤（OCP 验证）

**场景**：需要新增"基于部门的数据隔离"策略

1. 新增 `DepartmentContextResolver` 接口和默认实现
2. 扩展 `RequestContext` record，新增 `DepartmentContext` 字段
3. 在 `DefaultRequestContextManager` 中注入新的 Resolver
4. 在 `PolicyConstraintResolver` 实现中引用部门信息

**不需要修改的类**：`HybridDocumentRetriever`、`BailianRerankPostProcessor`、`MmrDocumentPostProcessor`、`ParentDocumentPostProcessor`、`QueryNormalizer`、`ChatAdvisorChainFactory`

---

## 八、实现计划

| 步骤 | 内容 | 依赖 |
|------|------|------|
| P0 | 创建 `context` 包、`RequestContext` 及子 record、`CagProperties` | 无 |
| P1 | `SysRoleMapper` 新增 `selectByIds()` 批量方法 | 现有 Mapper |
| P2 | 实现 `UserProfileResolver` + 默认实现 | P1 |
| P3 | 实现 `SessionContextResolver` + 默认实现 | 无 |
| P4 | 实现 `PolicyConstraintResolver` + 默认实现 | 无 |
| P5 | 实现 `DefaultRequestContextManager` + `ContextPromptInjector` | P2-P4, P0 |
| P6 | 修改 `ChatServiceImpl` + `ChatRequestSpecFactory` 集成 CAG | P5 |
| P7 | yml 配置 + Bean 注册验证 | P0 |
| P8 | 单元测试（每个 Resolver + Manager + Injector + 降级逻辑） | P0-P6 |
| P9 | 集成测试（端到端验证 prompt 注入效果） | P8 |

---

## 九、设计评审修正记录

### 9.1 自审修正（Self-Critique）

| # | 问题 | 修正 |
|---|------|------|
| 1 | `createSpec()` 内重复调 `SecurityUtils.getCurrentUserId()` | `createSpec()` 改为接收 `RequestContext` 参数，由 `ChatServiceImpl` 统一构建 |
| 2 | conversationId 传入 CAG 的版本不明确 | 明确传隔离后的 conversationId |
| 3 | SessionContextResolver 与 ConversationContextAdvisor 重复查 ChatMemory | `SessionContextResolver.resolve()` 接收 messageCount 参数 |
| 4 | RequestContext record 扩展性 | 内部容器，非公共契约，影响面可控 |
| 5 | CagProperties 的开关未在代码中使用 | `ChatServiceImpl` 检查 `enabled`；`ContextPromptInjector` 检查 `injectPrompt`；Manager 检查 `logContext` |

### 9.2 DeepSeek V4 Pro 评审修正

| # | 问题 | 修正 |
|---|------|------|
| 1 | `ChatServiceImpl` 集成代码缺失 | 新增 5.1 节，完整展示 `doChat()`/`doStream()`/`buildCagContext()`/`ChatContext` 扩展 |
| 2 | 未明确列出会修改的旧类 | 新增 3.2 节，分别列出"会修改"和"不会修改"的旧类 |
| 3 | `SessionContextResolver` 只是透传，设计不足 | 增加对话阶段推断逻辑（`inferStage()`），从 messageCount 推断"首次对话/对话初期/深入交流/长对话" |
| 4 | `CagProperties` 开关形同虚设 | 在 `ChatServiceImpl.buildCagContext()`、`ContextPromptInjector.inject()`、`DefaultRequestContextManager.buildContext()` 中使用开关 |
| 5 | `toPromptSegment()` 缺少 sanitize | 新增 `sanitize()` 方法，移除控制字符和换行，防御间接 prompt injection |
| 6 | 角色名查询缺少批量方法 | 新增 P1 步骤：`SysRoleMapper.selectByIds()` 批量方法 |
| 7 | 安全边界未标注 | `toPromptSegment()` 注释明确：只输出昵称和角色名，不输出 permissions |

### 9.3 已知技术债务（v2 优化）

| 债务 | 当前做法 | v2 目标 |
|------|----------|--------|
| PolicyConstraintResolver 硬编码角色映射 | Java if-else | 配置驱动（yml 或数据库） |
| ContextPromptInjector 模板硬编码 | Java 文本块 | 模板外置到 SystemPromptService 或配置 |
| 用户画像无缓存 | 每次查 DB（3 次） | `@Cacheable` + Caffeine 本地缓存，TTL 5-10 min |
| MDC 日志字段缺失 | 普通日志 | 添加 `cag.userId`、`cag.roles` MDC 字段便于 ELK 检索 |

---

## 十、风险与缓解

| 风险 | 缓解 |
|------|------|
| Prompt 注入增加 token 消耗 | 上下文段控制在 200 token 以内，可通过 `CagProperties.injectPrompt` 关闭 |
| 用户信息泄露到 LLM | `ContextPromptInjector` 只注入昵称和角色名，不注入 permissions / userId / email |
| Resolver 查询增加延迟 | 每次请求约 3 次 DB 查询（< 5ms），v2 加缓存后接近零 |
| 间接 Prompt Injection | `sanitize()` 清理控制字符和换行；角色名由管理员设置，非用户输入 |
| 上下文段影响 LLM 输出质量 | 可通过 A/B 测试对比有无 CAG 的回答质量 |
| record 扩展性 | 内部容器，非公共 API，影响面可控（见 9.1 #4） |
| CAG 关闭时行为一致 | `enabled=false` → `buildCagContext()` 返回 null → `inject()` 直接返回原 prompt |
