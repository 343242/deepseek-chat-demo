# 修复 LLM 模块代码审查发现 9.x/10.x

## Goal

修复 infrastructure/llm 模块代码审查发现的 6 个问题（9.1, 9.2, 10.1, 10.2, 10.3, 10.5），提升代码质量和可维护性。

## What I already know

### 9.1 — LlmAutoConfiguration 使用 @Autowired（已确认）
- 文件: `LlmAutoConfiguration.java:38`
- `@Autowired(required = false) MeterRegistry meterRegistry` 在构造器参数上
- 不是字段注入，是构造器参数注入，但项目规范偏好 `@Nullable`
- 修复: 改用 `org.springframework.lang.@Nullable`

### 9.2 — GenericOpenAiProviderRegistrar 硬编码 "reranking" → "rerank"（已确认）
- 文件: `GenericOpenAiProviderRegistrar.java:80`
- `String yamlKey = mapKey.equals("reranking") ? "rerank" : mapKey;`
- 原因: YAML 配置 key 是 `rerank`，枚举名是 `RERANKING`
- 修复: 在 `LlmCapability` 枚举添加 `yamlKey()` 方法，集中映射

### 10.1 — GenericChatClient.parseResponse 40 行（已确认）
- 文件: `GenericChatClient.java:216-256`（精确 40 行）
- 三段逻辑混合: choices 解析、tool_calls 解析、usage 解析
- 修复: 提取 `parseToolCalls(JsonNode message)` 和 `parseTokenUsage(JsonNode usage)`

### 10.2 — 5 客户端重复 HTTP 初始化模板（已确认）
- GenericChatClient, GenericEmbeddingClient, GenericRerankClient, BailianEmbeddingClient, BailianRerankClient
- 每个重复 ~10-15 行 HttpClient + JdkClientHttpRequestFactory + RestClient 构建
- 差异: 超时值不同；GenericChatClient 还用 OkHttp（SSE）

### 10.3 — 5 客户端重复 close() 方法（已确认）
- 4 个都是 `if (httpClient != null) httpClient.close();`
- GenericChatClient 额外有 OkHttp 清理

### 10.4 — CandidateProperties 纯 POJO（已确认 ✅ 合格）
- 无需修改

### 10.5 — 整个模块无单元测试（已确认）
- `src/test/java/com/smart/rag/infrastructure/llm/` 目录不存在
- 约 40 个源文件零测试

## Decision (ADR-lite)

**Context**: 10.2/10.3 HTTP 初始化模板与 close() 在 5 个客户端重复；10.5 测试缺口
**Decision**: 
- 10.2/10.3 采用**方案 B（HttpClientFactory 工具类）**——基于 SRP/OCP/组合优于继承原则
- 10.5 测试**本期不补**，单独排期
**Consequences**: 基类层级保持纯粹（只管能力契约），工厂封装传输；测试债务显式 deferred

## Requirements

* 9.1: LlmAutoConfiguration 移除 @Autowired，改用 @Nullable
* 9.2: LlmCapability 添加 yamlKey()，消除硬编码映射
* 10.1: GenericChatClient.parseResponse 拆分为 ≤20 行
* 10.2: 新建 HttpClientFactory，5 客户端复用构建逻辑
* 10.3: 工厂提供 close 辅助，减少 close() 重复
* 10.5: 本期不补测试（deferred）

## Acceptance Criteria

- [ ] 9.1 LlmAutoConfiguration 不再使用 @Autowired
- [ ] 9.2 GenericOpenAiProviderRegistrar 不再有硬编码字符串映射
- [ ] 10.1 parseResponse 拆分后主方法 ≤20 行
- [ ] 10.2 5 客户端通过 HttpClientFactory 构建 RestClient，无重复模板
- [ ] 10.3 close() 重复通过工厂辅助消除（GenericChatClient 的 OkHttp 额外清理仍保留）
- [ ] 编译通过 + 现有功能不回归
- [ ] 10.5 测试 deferred（不在本期范围）

## Out of Scope

* 10.4 CandidateProperties（已合格）
* 10.5 单元测试补全（单独排期）
* 完整的 40 文件测试覆盖

## Technical Notes

### 10.2/10.3 方案选项

**方案 A: Abstract*Client 基类模板方法**
- 在 AbstractChatClient/AbstractEmbeddingClient/AbstractRerankClient 提供 `buildRestClient()` 模板
- httpClient 字段上移到基类，统一 close()
- 风险: 改动基类层级，5 个客户端都要改

**方案 B: HttpClientFactory 工具类**
- 新建 `HttpClientFactory` 工具类，封装 HttpClient + RestClient 构建
- 各客户端调用工厂方法，差异通过参数传递
- close() 仍各客户端自行处理（或工厂提供 close 辅助方法）
- 改动更局部

### 10.5 测试范围选项

**选项 A（推荐 MVP）: 仅审查报告点名的 5 个关键类**
- RetryPolicy.executeWithBackoff（指数退避、重试耗尽）
- FallbackExecutor（降级链遍历、异常过滤）
- CircuitBreaker（状态转换 CLOSED→OPEN→HALF_OPEN→CLOSED）
- RegistrySnapshot（禁用/启用、filteredChains 预计算）
- ChatModelAdapter（Prompt → ChatRequest 转换）

**选项 B: 加上客户端解析逻辑**
- 选项 A + 各 client 的 response 解析方法（纯函数，易测试）

**选项 C: 全面覆盖（40 文件）**
- 工作量过大，建议单独排期
