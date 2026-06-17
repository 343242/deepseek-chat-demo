# O2 — Query Rewrite 自定义模型与 Temperature

**任务类型**: feature
**优先级**: P2
**分支**: rag-dev（基于现有）
**关联**: `05-18-hrag-optimization`（O2 延续）

## 背景

O1 已在 rewrite prompt 中加了守卫规则，解决了"短查询过度展开"的核心问题。但 H-RAG 推荐 query rewrite 使用低 temperature（0.2），当前全局 ChatClient 使用默认 0.7，随机性偏高。

O2 最初因"引入厂商硬编码（DeepSeekChatOptions）"被搁置。现在通过方案 B 解决：复用项目已有的 ModelProvider 路由体系，零厂商硬编码。

## 方案 B：通过 ProviderRegistry 动态创建 Rewrite 专用 ChatClient

**核心思路**：在 `RagRetrievalProperties` 新增 `queryRewriteModel` + `queryRewriteTemperature` 配置项，`RagConfig` 通过 `ProviderRegistry` + `ModelProvider.createClient(modelId, temperature)` 创建独立 ChatClient，再通过 `ChatClient.mutate()` 拿到 Builder 传给 `RewriteQueryTransformer`。

**优势**：
- 复用现有模型路由基础设施（ChatClientRegistry / ModelProvider / ProviderRegistry）
- 零厂商硬编码（不引入 DeepSeekChatOptions）
- 用户可在 yml 中指定任意已注册的模型和 temperature
- 默认值与当前行为一致（null = 使用全局默认 ChatClient.Builder）

## 修复项

### Phase 1: Properties 扩展

**文件**: `RagRetrievalProperties.java`
**改动**: record 新增两个字段

```java
String queryRewriteModel,       // 默认 null = 使用全局默认
Double queryRewriteTemperature  // 默认 null = 使用模型默认
```

### Phase 2: RagConfig 改造

**文件**: `RagConfig.java`
**改动**: `rewriteQueryTransformer()` 方法

```
1. 注入 ProviderRegistry + ModelRegistryRefresher
2. 若 queryRewriteModel 非空，通过 ProviderRegistry 找到对应 Provider
3. 调用 provider.createClient(modelId, temperature) 创建 ChatClient
4. 调用 chatClient.mutate() 获取 Builder 传给 RewriteQueryTransformer
5. 若 queryRewriteModel 为空，降级到原有全局 ChatClient.Builder
```

**依赖链**：
- `ProviderRegistry.get(providerId)` → `ModelProvider`
- `ModelProvider.createClient(modelId, temperature)` → `ChatClient`
- `ChatClient.mutate()` → `ChatClient.Builder`

**注意**：需要 `ModelRegistryRefresher` 来解析 modelId → providerId（已有 `getProviderIdForModel()` 方法）。

### Phase 3: 测试

**新增/更新测试**:
- [ ] `RagRetrievalPropertiesTest` — 新字段默认值
- [ ] `RagConfigTest` — queryRewriteModel 为 null 时使用全局 Builder
- [ ] `RagConfigTest` — queryRewriteModel 指定时走 Provider 路由
- [ ] `RagConfigTest` — temperature 透传到 createClient

### Phase 4: yml 配置示例

```yaml
app:
  rag:
    query-rewrite-model: deepseek/deepseek-chat  # 或 null 使用默认
    query-rewrite-temperature: 0.2                 # 或 null 使用模型默认
```

## 约束

- 不引入厂商特定的 ChatOptions 类
- queryRewriteModel 为 null 时行为与改动前完全一致
- 所有改动通过配置控制，默认值不变

## 文件改动清单

| 文件 | 改动类型 | Phase |
|------|----------|-------|
| `RagRetrievalProperties.java` | 新增 2 字段 + withOverrides 覆盖 | P1 |
| `RagConfig.java` | 改造 rewriteQueryTransformer() | P2 |
| `RagRetrievalPropertiesTest.java` | 新增测试 | P3 |
| `RagConfigTest.java` | 新增/更新测试 | P3 |

**总改动量**: ~30 行代码 + ~40 行测试
