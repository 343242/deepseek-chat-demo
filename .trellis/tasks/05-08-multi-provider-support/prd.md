# PRD: 多模型厂商聚合（v2 — 修正版）

> 核心约束：**添加一个新厂商 = 加 1 个依赖 + 写 1 个类 + 加 1 段配置，零修改现有文件**

## 1. 背景与目标

当前项目硬绑定 DeepSeek 单一厂商（`ChatClientFactory` 构造 `DeepSeekApi` → `DeepSeekChatModel` → `ChatClient`）。

目标：聚合多个国内 LLM 厂商，用户在前端选择模型时可以跨厂商选择，后端通过统一的抽象层屏蔽各厂商差异。

### 目标厂商

| 厂商 | Spring AI Starter | 配置前缀 | 默认模型 |
|------|-------------------|----------|----------|
| DeepSeek | `spring-ai-starter-model-deepseek` | `spring.ai.deepseek` | `deepseek-chat` |
| 智谱 Zhipu | `spring-ai-starter-model-zhipuai` | `spring.ai.zhipuai` | `glm-4-air` |
| MiniMax | `spring-ai-starter-model-minimax` | `spring.ai.minimax` | `MiniMax-Text-01` |
| Moonshot/Kimi | community starter 或 OpenAI 兼容 | `spring.ai.moonshot` | `moonshot-v1-8k` |

## 2. 设计原则审查

### 2.1 设计模式

| 模式 | 应用场景 | 说明 |
|------|---------|------|
| **策略模式 (Strategy)** | `ModelProvider` 接口 | 每个厂商一个策略实现，运行时按 providerId 选择 |
| **工厂方法 (Factory Method)** | `ModelProvider.createClient()` | 各 Provider 封装自己的 ChatClient 创建逻辑 |
| **模板方法 (Template Method)** | `AbstractModelProvider` 基类 | 抽取公共逻辑（健康检查、日志、异常处理），子类只实现差异部分 |
| **依赖倒置 (DIP)** | ChatService 依赖 `ModelProvider` 接口 | 不依赖具体的 DeepSeek/MiniMax 类型 |
| **服务定位 (Service Locator)** | `ProviderRegistry` | 按 providerId 路由到对应实现 |

### 2.2 SOLID 原则

| 原则 | 遵守方式 |
|------|---------|
| **SRP** | `ModelProvider` 只负责"创建模型客户端 + 构建 Options"；`ProviderRegistry` 只负责"路由"；`ChatService` 只负责"对话流程" |
| **OCP** | 新增厂商 = 新增一个 `ModelProvider` 实现类，不修改 ChatService / ChatController / ProviderRegistry |
| **LSP** | 所有 `ModelProvider` 实现可互换，ChatService 不感知具体类型 |
| **ISP** | `ModelProvider` 接口精简（5 个方法），不强迫实现不需要的能力 |
| **DIP** | 高层模块（ChatService）依赖抽象（`ModelProvider`），不依赖具体厂商类 |

### 2.3 OOP 思想

- **封装**: 每个 Provider 封装自己的 API Key、Base URL、ChatOptions 类型、模型拉取逻辑。外部只看到 `ChatClient` 和 `ChatOptions`
- **多态**: ChatService 通过 `ModelProvider` 接口调用，运行时动态分派
- **抽象**: `ChatOptions` 的差异（`DeepSeekChatOptions` vs `ZhiPuAiChatOptions`）完全封装在 Provider 内部，不泄漏

## 3. 架构设计

### 3.1 类图（核心）

```
┌─────────────────────────────────────────────────────────┐
│  ChatService                                            │
│  - chat(ChatRequest)                                    │
│  - chatStream(ChatRequest)                              │
│  依赖: ProviderRegistry, ChatClientRegistry,            │
│        ChatMemory, SystemPromptService, ...             │
│  不依赖: 任何具体 Provider 实现类                         │
└──────────────┬──────────────────────────────────────────┘
               │ 注入
               ▼
┌──────────────────────────────────────────────────────────┐
│  ProviderRegistry                                        │
│  - get(providerId): ModelProvider                        │
│  - getAll(): Collection<ModelProvider>                    │
│  - isAvailable(providerId): boolean                      │
│                                                          │
│  通过 Spring 构造器注入 List<ModelProvider> 自动发现       │
│  过滤 isAvailable() == false 的 Provider（未配置 API Key）│
└──────────────┬───────────────────────────────────────────┘
               │ 路由
               ▼
┌──────────────────────────────────────────┐
│  <<interface>> ModelProvider             │
│  + getProviderId(): String               │
│  + getDisplayName(): String              │
│  + isAvailable(): boolean                │
│  + fetchModels(): List<ModelInfo>        │
│  + createClient(modelId, temp): ChatClient│
│  + buildOptions(params): ChatOptions     │
└──────────────────────────────────────────┘
           ▲          ▲          ▲          ▲
           │          │          │          │
  ┌────────┴──┐ ┌─────┴───┐ ┌───┴────┐ ┌───┴──────┐
  │DeepSeek   │ │Zhipu    │ │MiniMax │ │Moonshot  │
  │Provider   │ │Provider │ │Provider│ │Provider  │
  │@Component │ │@Component│@Component│@Component │
  └───────────┘ └─────────┘ └────────┘ └──────────┘
```

### 3.2 ModelProvider 接口设计

```java
public interface ModelProvider {

    /** 厂商唯一标识，如 "deepseek", "zhipu" */
    String getProviderId();

    /** 显示名称，如 "DeepSeek", "智谱 AI" */
    String getDisplayName();

    /**
     * 该 Provider 是否可用（API Key 已配置）
     * 返回 false → ProviderRegistry 不注册 → 不影响其他 Provider
     */
    boolean isAvailable();

    /** 从厂商 API 拉取可用模型列表（失败返回空列表，不抛异常） */
    List<ModelInfo> fetchModels();

    /** 为指定模型创建 ChatClient（含默认 temperature） */
    ChatClient createClient(String modelId, Double temperature);

    /**
     * 将统一的 ModelParams 转换为厂商特定的 ChatOptions
     * 封装 DeepSeekChatOptions / ZhiPuAiChatOptions 等差异
     */
    ChatOptions buildOptions(ModelParams params);
}
```

### 3.3 ProviderRegistry 设计

```java
@Component
public class ProviderRegistry {

    private final Map<String, ModelProvider> providers;

    /**
     * Spring 自动注入所有 ModelProvider 实现
     * 过滤掉 isAvailable() == false 的（未配置 API Key 的厂商）
     */
    public ProviderRegistry(List<ModelProvider> providerList) {
        this.providers = providerList.stream()
                .filter(ModelProvider::isAvailable)
                .collect(Collectors.toUnmodifiableMap(
                        ModelProvider::getProviderId,
                        Function.identity()));
        log.info("Registered {} providers: {}", providers.size(), providers.keySet());
    }

    public ModelProvider get(String providerId) {
        ModelProvider p = providers.get(providerId);
        if (p == null) throw new ProviderNotFoundException(providerId);
        return p;
    }

    public Collection<ModelProvider> getAll() { return providers.values(); }
    public Set<String> getAvailableProviderIds() { return providers.keySet(); }
}
```

### 3.4 模型 ID 路由策略

```
用户请求 model 字段:
  "deepseek/deepseek-chat"  → providerId="deepseek", modelId="deepseek-chat"
  "zhipu/glm-4-air"        → providerId="zhipu", modelId="glm-4-air"
  "deepseek-chat"           → 向后兼容，默认 providerId="deepseek"
```

路由逻辑封装在 `ModelRouter` 类中（单一职责）：

```java
@Component
public class ModelRouter {

    private static final String DEFAULT_PROVIDER = "deepseek";

    public record Route(String providerId, String modelId) {}

    public Route resolve(String rawModelId) {
        int slash = rawModelId.indexOf('/');
        if (slash > 0) {
            return new Route(rawModelId.substring(0, slash), rawModelId.substring(slash + 1));
        }
        return new Route(DEFAULT_PROVIDER, rawModelId);
    }
}
```

### 3.5 ChatService 变更（仅改 2 处，后续加厂商零改动）

```java
// 变更 1: 注入 ProviderRegistry + ModelRouter 替代直接用 ChatClientRegistry
private final ProviderRegistry providerRegistry;
private final ModelRouter modelRouter;

// 变更 2: buildRequestSpec 中委托 Provider
private ChatClient.ChatClientRequestSpec buildRequestSpec(
        String rawModelId, String message, String conversationId) {

    ModelRouter.Route route = modelRouter.resolve(rawModelId);
    ModelProvider provider = providerRegistry.get(route.providerId());

    ChatClient client = registry.get(rawModelId);  // ChatClientRegistry 仍负责缓存
    ChatClient.ChatClientRequestSpec spec = client.prompt()
            .user(message)
            .advisors(buildAdvisors(conversationId));

    // 动态参数 — 完全委托给 Provider，ChatService 不感知具体 Options 类型
    ModelParams params = modelParamsService.getParams(route.providerId(), route.modelId());
    if (params != null) {
        spec = spec.options(provider.buildOptions(params));
    }

    return spec;
}
```

## 4. 数据库变更

`model_params` 表增加 `provider_id` 列：

```sql
ALTER TABLE model_params ADD COLUMN provider_id VARCHAR(32) NOT NULL DEFAULT 'deepseek';
-- 联合唯一索引: (provider_id, model_id) 替代原来的 (model_id)
```

`ModelParams` 实体增加 `providerId` 字段。`ModelParamsService` 的 key 从 `modelId` 改为 `providerId + modelId`。

## 5. 向后兼容

| 场景 | 兼容策略 |
|------|---------|
| 纯 modelId（如 `"deepseek-chat"`） | ModelRouter 默认 providerId=`deepseek` |
| 前端未更新 | `/api/models` 返回格式扩展（增加 `providerId` 字段），但 `id` 字段不变 |
| 现有 model_params 数据 | `provider_id` 默认值 `deepseek`，迁移无痛 |

## 6. 配置结构

```yaml
spring:
  ai:
    deepseek:
      base-url: https://api.deepseek.com
      api-key: ${DEEPSEEK_API_KEY:}
      chat:
        model: deepseek-chat
        temperature: 0.7
    zhipuai:
      base-url: https://open.bigmodel.cn/api/paas
      api-key: ${ZHIPU_API_KEY:}
      chat:
        model: glm-4-air
        temperature: 0.7
    minimax:
      api-key: ${MINIMAX_API_KEY:}
      chat:
        model: MiniMax-Text-01
        temperature: 0.7
    moonshot:
      base-url: https://api.moonshot.cn/v1
      api-key: ${MOONSHOT_API_KEY:}
      chat:
        model: moonshot-v1-8k
        temperature: 0.7
```

API Key 默认空字符串 → `isAvailable()` 返回 false → 不注册。

## 7. 实现步骤

### Phase 1: 基础设施（不改现有代码）
1. pom.xml 添加 3 个新 starter 依赖
2. application-dev.yml 添加各厂商配置
3. 创建 `ModelProvider` 接口
4. 创建 `ProviderRegistry`
5. 创建 `ModelRouter`
6. 创建 `ProviderNotFoundException`
7. 验证编译通过

### Phase 2: Provider 实现（每个 Provider 独立，互不影响）
1. 实现 `DeepSeekModelProvider`（从 ChatClientFactory 重构提取）
2. 实现 `ZhipuModelProvider`
3. 实现 `MiniMaxModelProvider`
4. 实现 `MoonshotModelProvider`

### Phase 3: 适配层（最小化改动现有文件）
1. 扩展 `ModelInfo` 增加 `providerId` 和 `displayName` 字段
2. 重构 `ModelRegistryRefresher` → 遍历 ProviderRegistry
3. 修改 `ChatService` → 注入 ProviderRegistry + ModelRouter
4. 修改 `ModelParamsService` → key 改为 providerId + modelId
5. 数据库 migration: model_params 加 provider_id 列

### Phase 4: 测试
1. 为 `ModelProvider` 接口编写契约测试（抽象测试基类）
2. 为每个 Provider 编写单元测试
3. 为 `ProviderRegistry` 编写测试（含 "部分 Provider 不可用" 场景）
4. 为 `ModelRouter` 编写测试（含向后兼容场景）
5. 更新 ChatService 测试
6. 编译 + 全量测试通过

### Phase 5: 集成验证
1. 启动应用，验证 `/api/models` 返回多厂商模型列表
2. 分别用各厂商模型进行对话测试
3. git commit + push

## 8. 新增厂商 Checklist（验证 OCP）

添加"百度文心"厂商的步骤：

- [ ] pom.xml 加 `spring-ai-starter-model-qianfan`（或 OpenAI 兼容）
- [ ] application.yml 加 `spring.ai.qianfan.*` 配置
- [ ] 新建 `QianfanModelProvider.java implements ModelProvider`
- [ ] **不需要修改任何现有文件** ✅

如果上述第 4 步不成立，说明设计有缺陷，必须先修正架构再继续。

## 9. 约束

- Spring AI 版本保持 1.1.5
- Moonshot 优先使用社区 starter；不可用时 fallback 到 OpenAI 兼容模式
- 未配置 API Key 的厂商静默跳过，不阻止应用启动
- 所有 Provider 的 `fetchModels()` 失败时返回空列表，不抛异常（不影响其他 Provider）
- `ChatOptions` 类型差异完全封装在 Provider 内，不泄漏到 ChatService
