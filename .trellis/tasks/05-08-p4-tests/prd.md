# Phase 4: 测试 — 单元测试 + 集成验证

## 目标

为多 Provider 架构编写全面的单元测试，并完成集成验证。

## 前置条件

- Phase 1-3 全部完成

## 交付物

### 1. ModelProvider 契约测试（抽象测试基类）

路径: `src/test/java/com/demo/deepseekchat/chat/provider/AbstractModelProviderTest.java`

- 定义通用测试契约：`isAvailable_whenApiKeyConfigured`、`createClient_returnsNonNull`、`buildOptions_mapsFields`
- 各 Provider 测试继承此基类，提供各自 mock

### 2. 各 Provider 单元测试

- `DeepSeekModelProviderTest`
- `ZhipuModelProviderTest`
- `MiniMaxModelProviderTest`
- `MoonshotModelProviderTest`

### 3. ProviderRegistry 测试

- 所有 Provider 可用 → 全部注册
- 部分 Provider 不可用 → 只注册可用的
- 全部不可用 → 空注册表（不报错）

### 4. ModelRouter 测试

- `"deepseek/deepseek-chat"` → Route("deepseek", "deepseek-chat")
- `"deepseek-chat"` → Route("deepseek", "deepseek-chat")（向后兼容）
- `"zhipu/glm-4-air"` → Route("zhipu", "glm-4-air")

### 5. ChatService 测试更新

- 更新现有 ChatServiceTest 适配 ProviderRegistry 注入
- 验证多 Provider 路由正确

### 6. 集成验证

- 启动应用
- `GET /api/models` 返回多厂商列表
- 用各厂商模型发送对话请求（至少验证 DeepSeek + 1 个新厂商）
- git commit + push

## 验收标准

- [ ] `mvn test` 全部通过
- [ ] 新增测试 ≥ 30 个
- [ ] 集成验证通过
- [ ] git commit + push

## 设计原则验证

- **契约测试**: 抽象基类确保所有 Provider 遵循相同契约 ✅
- **边界测试**: "部分不可用" 场景验证容错性 ✅
- **向后兼容测试**: 确保 modelId 旧格式仍工作 ✅
