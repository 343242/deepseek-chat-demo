# Phase 1: 基础设施 — 依赖 / 接口 / Registry

## 目标

搭建多 Provider 架构的基础设施层。本阶段 **不修改任何现有代码**，只添加新文件和新依赖。

## 交付物

### 1. Maven 依赖 (pom.xml)

在 `<dependencies>` 中添加：

```xml
<!-- 智谱 Zhipu -->
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-starter-model-zhipuai</artifactId>
</dependency>

<!-- MiniMax -->
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-starter-model-minimax</artifactId>
</dependency>

<!-- Moonshot (社区 starter，需确认 Maven Central 可用性) -->
<!-- 如果社区 starter 不可用，Phase 2 改用 OpenAI 兼容模式 -->
```

### 2. ModelProvider 接口

路径: `src/main/java/com/demo/deepseekchat/chat/provider/ModelProvider.java`

```java
public interface ModelProvider {
    String getProviderId();
    String getDisplayName();
    boolean isAvailable();
    List<ModelInfo> fetchModels();
    ChatClient createClient(String modelId, Double temperature);
    ChatOptions buildOptions(ModelParams params);
}
```

### 3. ProviderRegistry

路径: `src/main/java/com/demo/deepseekchat/chat/provider/ProviderRegistry.java`

- 构造器注入 `List<ModelProvider>`
- 过滤 `isAvailable() == false`
- 提供 `get(providerId)` / `getAll()` / `getAvailableProviderIds()`
- 不可用时抛 `ProviderNotFoundException`

### 4. ModelRouter

路径: `src/main/java/com/demo/deepseekchat/chat/provider/ModelRouter.java`

- 解析 `"deepseek/deepseek-chat"` → `Route("deepseek", "deepseek-chat")`
- 向后兼容：纯 modelId 默认 providerId = `"deepseek"`

### 5. ProviderNotFoundException

路径: `src/main/java/com/demo/deepseekchat/exception/ProviderNotFoundException.java`

- 继承 `BusinessException`

### 6. ModelInfo 扩展

- 增加 `providerId` 字段（String）
- 增加 `displayName` 字段（String，厂商名）

### 7. 配置文件

`application-dev.yml` 添加各厂商配置段（API Key 默认空，不影响启动）。

## 验收标准

- [ ] 编译通过 (`mvn compile` 成功)
- [ ] 应用正常启动（新依赖不影响现有功能）
- [ ] ModelProvider 接口、ProviderRegistry、ModelRouter 都有对应的单元测试
- [ ] 零修改现有 Java 源文件（pom.xml 和 yml 除外）

## 设计原则验证

- **OCP**: 新接口 + 新类，不改旧代码 ✅
- **SRP**: ProviderRegistry 只管路由，ModelRouter 只管解析 ✅
- **DIP**: 未来 ChatService 将依赖 ModelProvider 接口，不依赖具体厂商 ✅
