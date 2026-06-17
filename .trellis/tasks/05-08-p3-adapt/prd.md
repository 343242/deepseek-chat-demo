# Phase 3: 适配层 — 重构现有代码

## 目标

将 ChatService、ModelRegistryRefresher、ModelParamsService 等现有代码适配到新的 Provider 架构。本阶段改动现有文件，但遵循最小化侵入原则。

## 前置条件

- Phase 1 完成
- Phase 2 完成（至少 DeepSeekProvider）

## 交付物

### 1. ModelInfo 扩展（如 Phase 1 未完成）

- 增加 `providerId` 字段
- 增加 `displayName` 字段

### 2. ModelRegistryRefresher 重构

- 注入 `ProviderRegistry`
- 遍历所有可用 Provider，调用 `fetchModels()` + `createClient()` 聚合到 `ChatClientRegistry`
- 单个 Provider 拉取失败不影响其他 Provider（try-catch 隔离）

### 3. ChatService 适配

- 注入 `ProviderRegistry` + `ModelRouter`
- `buildRequestSpec` 中：用 `ModelRouter.resolve()` 解析 providerId + modelId
- `buildOptions` 委托给 `ModelProvider.buildOptions()`，移除硬编码的 `DeepSeekChatOptions`
- `chat()` 和 `chatStream()` 方法签名不变，对外接口零改动

### 4. ModelParamsService 适配

- key 从 `modelId` 改为 `providerId + modelId`
- `ModelParams` 实体增加 `providerId` 字段
- 数据库 migration: `ALTER TABLE model_params ADD COLUMN provider_id VARCHAR(32) NOT NULL DEFAULT 'deepseek'`

### 5. ChatController / ModelService

- `GET /api/models` 返回结果包含 `providerId`
- 向后兼容：`id` 字段格式不变（纯 modelId），新增 `providerId` 字段

### 6. 删除旧代码

- `ChatClientFactory` → 逻辑已迁移到 `DeepSeekModelProvider`，标记 `@Deprecated` 或删除
- `DeepSeekAutoConfiguration` 中与 ChatClientFactory 相关的部分迁移到 Provider

## 验收标准

- [ ] `mvn compile` 成功
- [ ] `mvn test` 全部通过（包括之前写的 101 个测试）
- [ ] `/api/models` 返回多厂商模型列表
- [ ] 纯 modelId 请求（如 `"deepseek-chat"`）向后兼容
- [ ] `providerId/modelId` 格式正确路由

## 设计原则验证

- **OCP**: ChatService 改动是一次性的架构适配，后续加 Provider 不再改 ✅
- **DIP**: ChatService 依赖 ModelProvider 接口，不依赖具体厂商 ✅
- **最小侵入**: 只改 ChatService 的 buildRequestSpec 和 buildOptions，方法签名不变 ✅
