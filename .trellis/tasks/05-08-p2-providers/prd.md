# Phase 2: Provider 实现 — 4 个厂商

## 目标

为 4 个厂商各实现一个 `ModelProvider`，每个 Provider 封装自己的 ChatClient 创建、Options 构建和模型列表拉取。

## 前置条件

- Phase 1 完成（ModelProvider 接口 + ProviderRegistry + 依赖就绪）

## 交付物

### 1. DeepSeekModelProvider

路径: `src/main/java/com/demo/deepseekchat/chat/provider/DeepSeekModelProvider.java`

- 从现有 `ChatClientFactory` 提取逻辑
- `isAvailable()`: 检查 `DeepSeekProperties.apiKey()` 非空
- `fetchModels()`: 调用 DeepSeek `/models` API
- `createClient()`: 复用现有 DeepSeekApi → DeepSeekChatModel → ChatClient 链路
- `buildOptions()`: 构造 `DeepSeekChatOptions`
- `@Component` — Spring 自动发现

### 2. ZhipuModelProvider

路径: `src/main/java/com/demo/deepseekchat/chat/provider/ZhipuModelProvider.java`

- 使用 `spring-ai-starter-model-zhipuai` 提供的 `ZhiPuAiChatModel`
- `isAvailable()`: 检查 `spring.ai.zhipuai.api-key` 非空
- `fetchModels()`: 返回硬编码模型列表（智谱无 /models API）
- `createClient()`: `ZhiPuAiApi` → `ZhiPuAiChatModel` → `ChatClient`
- `buildOptions()`: 构造 `ZhiPuAiChatOptions`

### 3. MiniMaxModelProvider

路径: `src/main/java/com/demo/deepseekchat/chat/provider/MiniMaxModelProvider.java`

- 使用 `spring-ai-starter-model-minimax` 提供的 `MiniMaxChatModel`
- `isAvailable()`: 检查 `spring.ai.minimax.api-key` 非空
- `fetchModels()`: 返回硬编码模型列表
- `createClient()`: `MiniMaxApi` → `MiniMaxChatModel` → `ChatClient`
- `buildOptions()`: 构造 `MiniMaxChatOptions`

### 4. MoonshotModelProvider

路径: `src/main/java/com/demo/deepseekchat/chat/provider/MoonshotModelProvider.java`

- 方案 A: 社区 starter（优先尝试）
- 方案 B: OpenAI 兼容模式 — 用 `spring-ai-starter-model-openai`，base-url 指向 `https://api.moonshot.cn/v1`
- `isAvailable()`: 检查 API Key
- `fetchModels()`: 返回硬编码模型列表（moonshot-v1-8k, moonshot-v1-32k, moonshot-v1-128k）

## 验收标准

- [ ] 每个 Provider 编译通过
- [ ] 每个 Provider 有独立单元测试（mock API 调用）
- [ ] 未配置 API Key 的 Provider `isAvailable()` 返回 false，不阻止启动
- [ ] ProviderRegistry 自动发现所有 `@Component` Provider

## 设计原则验证

- **策略模式**: 4 个 Provider 是 4 个策略实现 ✅
- **工厂方法**: `createClient()` 封装各自的创建逻辑 ✅
- **OCP**: 新增 Provider 只加新类 ✅
- **封装**: ChatOptions 类型差异不泄漏到外部 ✅
