# 补齐 LLM 模块单元测试覆盖

## Goal

为 `infrastructure/llm/` 模块的核心组件补齐单元测试，覆盖弹性（重试/熔断/降级）、注册中心一致性视图、Spring AI 桥接适配器等关键路径。该模块当前零测试覆盖，承担了生产环境最重要的稳定性逻辑。

## Background

`infrastructure/llm/` 模块在前序任务（9.x/10.x 代码评审修复）中已完成核心重构：
- RetryPolicy — 同步指数退避 + 流式 Reactor 重试
- FallbackExecutor — 跨模型降级链（阻塞 + 流式）
- CircuitBreaker — 三态熔断（CLOSED → OPEN → HALF_OPEN → CLOSED）
- RegistrySnapshot — 基于 AtomicReference 的不可变快照，预计算 filteredChains
- ChatModelAdapter — Spring AI ChatModel ↔ ChatCapable 桥接

10.5 在评审中被推迟到本期：这些类承载了所有 LLM 调用的弹性语义，无测试意味着任何回归都不可观测。

## Scope

### In Scope（5 个核心类）

| 类 | 测试目标 |
|---|---|
| `RetryPolicy` | `isRetryable` 全分支；`executeWithBackoff` 成功/可重试/不可重试/重试耗尽；`executeDirect`；`retryStream`（已发送不重试） |
| `FallbackExecutor` | `execute` 空链/不可降级短路/首成功/链式降级/全失败 + metrics+event 发布；`executeStream` |
| `CircuitBreaker` | `execute` OPEN 拒绝/成功记录/可降级失败记录/不可降级不记录；`executeStream`；`recordProbeSuccess` |
| `RegistrySnapshot` | 不可变性；filteredChains 预计算（禁用排除）；getClient/isDisabled；getDefaultClient/getDeepThinkingClient 禁用返回 null；empty/size |
| `ChatModelAdapter` | `call` 双向映射（Prompt→ChatRequest、LlmResponse→ChatResponse、truncated→length、TokenUsage）；`stream` |

### Out of Scope（本期不做）

- 5 个客户端类的 HTTP 调用测试（GenericChatClient / BailianEmbeddingClient 等）—— 涉及 OkHttp/RestClient mock，单独立项
- LlmAutoConfiguration / GenericOpenAiProviderRegistrar 集成测试 —— Spring Boot 上下文测试，单独立项
- ResilientClient / ProbeHandler 装饰器组合测试 —— 单独立项
- ChatRequest.Builder / ModelCandidate 等纯 POJO —— 无逻辑，不单测

## Requirements

- 5 个测试类，每个位于 `src/test/java/com/smart/rag/infrastructure/llm/<subpackage>/`
- 使用 JUnit Jupiter + Mockito + AssertJ（项目既有依赖）
- 使用 `@ExtendWith(MockitoExtension.class)` + `@Mock` 注解（项目既有风格）
- 不使用 Spring Boot 上下文（纯单元测试）
- 测试覆盖率：
  - RetryPolicy: ≥ 90% lines
  - FallbackExecutor: ≥ 85% lines
  - CircuitBreaker: ≥ 85% lines
  - RegistrySnapshot: ≥ 95% lines
  - ChatModelAdapter: ≥ 90% lines

## Acceptance Criteria

- [ ] `src/test/java/com/smart/rag/infrastructure/llm/resilience/RetryPolicyTest.java` 通过
- [ ] `src/test/java/com/smart/rag/infrastructure/llm/resilience/FallbackExecutorTest.java` 通过
- [ ] `src/test/java/com/smart/rag/infrastructure/llm/resilience/CircuitBreakerTest.java` 通过
- [ ] `src/test/java/com/smart/rag/infrastructure/llm/registry/RegistrySnapshotTest.java` 通过
- [ ] `src/test/java/com/smart/rag/infrastructure/llm/adapter/ChatModelAdapterTest.java` 通过
- [ ] `mvn test -Dtest='com.smart.rag.infrastructure.llm.**'` 全绿
- [ ] `mvn compile` 不破坏既有代码

## Definition of Done

- [ ] 所有 5 个测试文件已创建并通过
- [ ] `mvn test` 全部通过
- [ ] 代码已提交并推送
- [ ] 任务已归档

## Technical Approach

### 测试策略

1. **RetryPolicy** — 构造时使用 `RetryConfig(1, 1L, 1L, 1.0)` 极短退避避免测试慢；用 Mockito spy 计数调用次数；用 `assertThrows` 验证异常类型
2. **FallbackExecutor** — Mock `CapabilityClient` 子接口实现；Mock `FallbackEligibility` 返回值；自定义 `Consumer<FallbackEvent>` 收集器验证事件
3. **CircuitBreaker** — Mock `ModelCircuitBreakerRegistry` 返回 `isCallAllowed=false` 模拟 OPEN；Mock `FallbackEligibility` 区分可/不可降级
4. **RegistrySnapshot** — 构造时显式传入 disabledSet；验证 filteredChains 排除被禁用项；验证 unmodifiableMap 抛 UnsupportedOperationException
5. **ChatModelAdapter** — Mock `ChatCapable`；构造 Prompt 用 Spring AI 的 SystemMessage/UserMessage/AssistantMessage

### 关键测试矩阵

**RetryPolicy.isRetryable**（共 8 个分支）：
- ModelCircuitOpenException → false
- UnsupportedOperationException → false
- RemoteException(LLM_RATE_LIMITED) → true
- RemoteException(LLM_TRANSIENT_ERROR) → true
- RemoteException(LLM_ALL_MODELS_FAILED) → false
- IOException → true
- ProbeTimeoutException → true
- TimeoutException → true
- NPE/IAE/ISE → false
- 嵌套 cause 为 IOException → true

**FallbackExecutor.execute**：
- 空链（全部 isAvailable=false）→ 抛 LLM_CONFIG_ERROR
- 首个成功 → 返回值，不调用后续
- 不可降级异常 → 直接抛出，不降级
- 可降级异常 + 后续成功 → 降级，发布 metrics + event
- 全失败 → 抛 LLM_ALL_MODELS_FAILED

**CircuitBreaker.execute**：
- isCallAllowed=false → 抛 ModelCircuitOpenException，不调用 action
- action 成功 → recordSuccess 调用一次
- action 抛可降级异常 → recordFailure 调用一次
- action 抛不可降级异常 → recordFailure 不调用

**RegistrySnapshot**：
- 构造后修改原始 Map → 快照不变（不可变）
- disabledSet 包含 c1 → filteredChains 中无 c1
- getClient(disabledId) → null
- getDefaultClient(capability) 当默认被禁用 → null

**ChatModelAdapter.call**：
- Prompt 含 SystemMessage → ChatRequest.systemPrompt 非空
- Prompt 含多条 UserMessage/AssistantMessage → ChatRequest.history 排除最后一条 UserMessage
- LlmResponse.truncated=true → ChatResponse finishReason="length"
- LlmResponse.tokenUsage 非空 → ChatResponse metadata.usage 非空

## Decision (ADR-lite)

**Context**: 模块零测试覆盖，55 个源文件，全量补齐约 100+ 测试用例工作量大。

**Decision**: 本期只补齐 5 个最关键的类（弹性 + 注册中心 + 桥接）。这 5 个类承载了所有 LLM 调用的稳定性语义，任何回归都会导致生产事故。

**Consequences**:
- Pro: 测试聚焦最高价值，1-2 天可完成
- Pro: 5 个类覆盖后，重构/优化时已有安全网
- Con: 客户端类、自动配置、装饰器组合暂无测试
- Con: 后续仍需独立任务补齐剩余覆盖

## Technical Notes

### 相关文件（仅本期涉及）

源文件：
- `src/main/java/com/smart/rag/infrastructure/llm/resilience/RetryPolicy.java`
- `src/main/java/com/smart/rag/infrastructure/llm/resilience/FallbackExecutor.java`
- `src/main/java/com/smart/rag/infrastructure/llm/resilience/CircuitBreaker.java`
- `src/main/java/com/smart/rag/infrastructure/llm/resilience/FallbackEvent.java`
- `src/main/java/com/smart/rag/infrastructure/llm/registry/RegistrySnapshot.java`
- `src/main/java/com/smart/rag/infrastructure/llm/adapter/ChatModelAdapter.java`

支撑类型（mock 或实例化）：
- `RetryConfig` — record，可直接 `new RetryConfig(null, null, null, null)` 触发默认值
- `FallbackEligibility` — `@Component`，可直接 `new FallbackEligibility()` 或 mock
- `ModelCircuitBreakerRegistry` — `@Component`，测试中 mock
- `CircuitBreakerState` — enum，无需 mock
- `CapabilityClient` — interface，测试中实现简单 stub
- `LlmCapability` — enum（CHAT, EMBEDDING, RERANKING）
- `ChatRequest` / `LlmResponse` / `MessageInformation` — record/class，可直接构造
- `LlmMetrics` — 普通类（不是 interface），可 mock 或传 null

### 测试风格参考

参考既有测试：
- `src/test/java/com/smart/rag/infrastructure/exception/AbstractExceptionTest.java` — `@Nested` + AssertJ
- `src/test/java/com/smart/rag/chat/service/ChatConversationHelperTest.java` — `@ExtendWith(MockitoExtension.class)` + `@Mock`

### 错误码引用

- `RemoteErrorCode.LLM_TRANSIENT_ERROR` — 重试耗尽包装
- `RemoteErrorCode.LLM_RATE_LIMITED` — 可重试
- `RemoteErrorCode.LLM_ALL_MODELS_FAILED` — 降级链耗尽
- `RemoteErrorCode.LLM_CONFIG_ERROR` — 空链
