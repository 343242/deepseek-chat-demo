# Journal - instant (Part 1)

> AI development session journal
> Started: 2026-05-08

---



## Session 1: ChatModeStrategy 策略模式重构

**Date**: 2026-05-28
**Task**: ChatModeStrategy 策略模式重构
**Branch**: `agentic-rag-dev`

### Summary

将 ChatModeStrategy 从特征查询升级为行为委托。新增 ModeChainResult/AdvisorInfrastructure/AdvisorChainContext，策略各自组装 Advisor 链，ChatAdvisorChainFactory 精简为薄门面。4 轮 Codex Review 共 19 个修正全部落地。AGENT 流式暂不支持，抛 BusinessException 拒绝。

### Main Changes

(Add details)

### Git Commits

| Hash | Message |
|------|---------|
| `430de39` | (see git log) |

### Testing

- [OK] (Add test results)

### Status

[OK] **Completed**

### Next Steps

- None - task complete


## Session 2: OCR code review: fix 3 bugs in structured concurrency

**Date**: 2026-06-02
**Task**: OCR code review: fix 3 bugs in structured concurrency
**Branch**: `structured-concurrency-feature`

### Summary

Ran OpenCodeReview on last 2 commits (7311854, 49a1631). Fixed 3 HIGH bugs: (1) ScopeNestingGuard thread-local corruption on thread reuse — finally now restores previous values instead of remove(); (2) StandardStrategy.loadAll missing error filter — failed loads were silently reported as success; (3) QuorumSuccessPolicy missing early termination when quorum becomes impossible. Also added merge functions to all Collectors.toMap calls in StandardStrategy.

### Main Changes

(Add details)

### Git Commits

| Hash | Message |
|------|---------|
| `10cd850` | (see git log) |

### Testing

- [OK] (Add test results)

### Status

[OK] **Completed**

### Next Steps

- None - task complete


## Session 3: Probe cache optimization: shared dedup + Redis health cache

**Date**: 2026-06-03
**Task**: Probe cache optimization: shared dedup + Redis health cache
**Branch**: `agentic-rag-dev`

### Summary

Implemented two-layer first-packet probe optimization: SharedProbeRegistry (ConcurrentHashMap dedup) + ModelHealthCache (Redisson RMapCache with per-entry TTL). Added ModelHealthPreProber scheduled task. Modified StreamRetryHandler with cache-aware factory (createDirect). All @ConditionalOnProperty, disabled by default. 697 tests pass.

### Main Changes

(Add details)

### Git Commits

| Hash | Message |
|------|---------|
| `07e9401` | (see git log) |

### Testing

- [OK] (Add test results)

### Status

[OK] **Completed**

### Next Steps

- None - task complete


## Session 4: Chat Module 15-Dimension Audit Fixes

**Date**: 2026-06-03
**Task**: Chat Module 15-Dimension Audit Fixes
**Branch**: `agentic-rag-dev`

### Summary

完成 chat 模块 15 维度审计的全部修复：C-1/C-2/C-3/C-4 异常处理与安全加固，E-1 ErrorHandler 统一，M-1 AbstractModeStrategy 模板方法重构，M-3 参数校验，RCR-1 死信队列补偿机制，T-1 MDC 传播，X-1 XSS 防护，X-3 导出校验。13 个单元测试全部通过。

### Main Changes

(Add details)

### Git Commits

| Hash | Message |
|------|---------|
| `b3eb90c` | (see git log) |

### Testing

- [OK] (Add test results)

### Status

[OK] **Completed**

### Next Steps

- None - task complete


## Session 5: Complete exception hierarchy migration

**Date**: 2026-06-05
**Task**: Complete exception hierarchy migration
**Branch**: `agentic-rag-dev`

### Summary

Migrate remaining production/test files from old ErrorCode/BusinessException to three-layer exception system (ClientException/ServiceException/RemoteException). Fix FallbackEligibility instanceof regression. Add 4 test classes (46 tests). Update error-handling spec.

### Main Changes

(Add details)

### Git Commits

| Hash | Message |
|------|---------|
| `4c61fa1` | (see git log) |

### Testing

- [OK] (Add test results)

### Status

[OK] **Completed**

### Next Steps

- None - task complete


## Session 6: Messaging Bus Phase A + remove enabled toggle

**Date**: 2026-06-06
**Task**: Messaging Bus Phase A + remove enabled toggle
**Branch**: `agentic-rag-dev`

### Summary

Implemented messaging bus Phase A (SPI interfaces, RocketMQ 5.x core, circuit breaker, auto-config, exception hierarchy, 11 unit tests). Then removed the enabled feature flag, deleted NoOpMessageBus dead code, and flattened MessagingAutoConfiguration to a single bean creation path.

### Main Changes

(Add details)

### Git Commits

| Hash | Message |
|------|---------|
| `d2baf8c` | (see git log) |
| `5dc4ca0` | (see git log) |

### Testing

- [OK] (Add test results)

### Status

[OK] **Completed**

### Next Steps

- None - task complete


## Session 7: LLM module Mimo P1/P2 fixes (6 issues)

**Date**: 2026-06-14
**Task**: LLM module Mimo P1/P2 fixes (6 issues)
**Branch**: `agentic-rag-dev`

### Summary

Verified Mimo review's 7 findings against current code: 6 real (P1×2 + P2×4), 1 stale (P2-3 validate already wired in ModelGroup). Fixed: RetryConfig mergeWithOverride null semantics; AbstractModelCandidate getParams null-safety; CapabilityStrategyRegistry IllegalStateException convention; AbstractResilientClient LLM_TRANSIENT_ERROR; EmbeddingCapable immutability Javadoc + BailianEmbeddingClient defensive clone; AbstractProviderFactoryAwareStrategy DRY extraction. Added RetryConfigTest (7 cases). 95/95 LLM tests green.

### Main Changes

(Add details)

### Git Commits

| Hash | Message |
|------|---------|
| `49c150c` | (see git log) |

### Testing

- [OK] (Add test results)

### Status

[OK] **Completed**

### Next Steps

- None - task complete


## Session 8: LLM module self-review P1 fixes (3 issues)

**Date**: 2026-06-14
**Task**: LLM module self-review P1 fixes (3 issues)
**Branch**: `agentic-rag-dev`

### Summary

Fixed 3 self-review P1 findings: HttpClientErrorHandler.translate() refactored from sneaky-throw to pure transformer (all branches return exceptions); CircuitBreaker.executeStream consolidated doOnComplete/doOnError/doOnCancel trio into doFinally(signal) using AtomicReference<Throwable> for error capture, eliminating probe slot leak on CANCEL path and DRY violation; LlmMetrics gauge idempotency confirmed via ConcurrentHashMap.newKeySet().add() with strengthened Javadoc. Added streamCancellationAfterEmissionReleasesProbe regression test. 96/96 LLM tests green, trellis-check sub-agent 0 issues.

### Main Changes

(Add details)

### Git Commits

| Hash | Message |
|------|---------|
| `488413e` | (see git log) |

### Testing

- [OK] (Add test results)

### Status

[OK] **Completed**

### Next Steps

- None - task complete


## Session 9: LLM module self-review P2/P3 follow-ups (11 issues)

**Date**: 2026-06-14
**Task**: LLM module self-review P2/P3 follow-ups (11 issues)
**Branch**: `agentic-rag-dev`

### Summary

Completed 11 P2/P3 self-review fixes: HttpClientErrorHandler 5xx→ERROR log + ResourceAccessException unwrap; ProbeHandler extracted wrapWithInFlightProbeOrDelegate helper + corrected Javadoc + non-@Component rationale; BailianEmbeddingClient split Spring AI EmbeddingModel adapter (260→218 lines) into BailianSpringAiEmbeddingAdapter; LlmAutoConfiguration primaryEmbeddingModel() detects/wraps adapter; LlmClientRegistry externalized DESTROY_TIMEOUT/CONCURRENCY via constructor injection + @Autowired for multi-constructor Spring resolution; ChatRequest error messages now name the field; CircuitBreaker.execute() Javadoc clarifies recordSuccess no-op in non-CLOSED state. 532/532 tests green across LLM + downstream chat/agent/rag modules. trellis-check found 1 Spring autowiring issue and self-fixed.

### Main Changes

(Add details)

### Git Commits

| Hash | Message |
|------|---------|
| `c35b2fe` | (see git log) |

### Testing

- [OK] (Add test results)

### Status

[OK] **Completed**

### Next Steps

- None - task complete


## Session 10: Decouple Spring AI ChatClient.Builder injection

**Date**: 2026-06-14
**Task**: Decouple Spring AI ChatClient.Builder injection
**Branch**: `agentic-rag-dev`

### Summary

Fixed 'No qualifying bean of type ChatModel' startup failure by introducing RewriteClientResolver in infrastructure/llm/adapter. Removed ChatClient.Builder autoconfig dependency from RagConfig, RagAdvisorFactory (dead field), QueryRewriteTool. Codified LLM SPI contract in new .trellis/spec/backend/llm-spi.md with 7-section code-spec. 843/843 tests pass.

### Main Changes

(Add details)

### Git Commits

| Hash | Message |
|------|---------|
| `65c5fcf` | (see git log) |
| `93b743b` | (see git log) |

### Testing

- [OK] (Add test results)

### Status

[OK] **Completed**

### Next Steps

- None - task complete


## Session 11: Fix ChatModelAdapter.getDefaultOptions returns ToolCallingChatOptions

**Date**: 2026-06-15
**Task**: Fix ChatModelAdapter.getDefaultOptions returns ToolCallingChatOptions
**Branch**: `agentic-rag-dev`

### Summary

Fixed 'ToolCall Advisor requires ToolCallingChatOptions' IllegalArgumentException that blocked all chat paths (mode=SIMPLE/MULTI_TURN/AGENT) when CalculatorTools/DateTimeTools/CodeExecutionTool auto-registered. ChatModelAdapter now overrides getDefaultOptions() to return ToolCallingChatOptions.builder().build() (vendor-neutral, fresh per call). Centralized fix in adapter layer; all 5 self-build sites benefit automatically. Added 3 nested tests. Codified contract in llm-spi.md.

### Main Changes

(Add details)

### Git Commits

| Hash | Message |
|------|---------|
| `5b9bb96` | (see git log) |
| `e0533a6` | (see git log) |

### Testing

- [OK] (Add test results)

### Status

[OK] **Completed**

### Next Steps

- None - task complete


## Session 12: Fix intent-model ID format in application.yml

**Date**: 2026-06-15
**Task**: Fix intent-model ID format in application.yml
**Branch**: `agentic-rag-dev`

### Summary

Fixed 'Intent classification LLM call failed' repeated 3x then fallback to DEEP_RETRIEVAL confidence=0.0 in AGENT mode. Root cause: application.yml intent-model default was deepseek/deepseek-v4-flash (compound provider/model format) but IntentClassifier calls llmRegistry.get directly without compound-format parser; registry candidate ID is deepseek-v4-flash (no prefix). Single-line yml default value change. 846/846 tests pass. fallback chain / RagRetrievalProperties untouched (they go through separate compound-format parser layer).

### Main Changes

(Add details)

### Git Commits

| Hash | Message |
|------|---------|
| `817d924` | (see git log) |

### Testing

- [OK] (Add test results)

### Status

[OK] **Completed**

### Next Steps

- None - task complete


## Session 13: Unify model ID format to registry candidate ID (BREAKING)

**Date**: 2026-06-15
**Task**: Unify model ID format to registry candidate ID (BREAKING)
**Branch**: `agentic-rag-dev`

### Summary

BREAKING: all model IDs must be registry candidate ID format (deepseek-v4-flash, no provider/ prefix). Compound format (deepseek/deepseek-v4-flash) no longer accepted. Added fail-fast IllegalArgumentException in ChatServiceImpl.resolveCandidateId. Corrected misleading javadoc on RagRetrievalProperties.queryRewriteModel (was claiming 'compound format' but actually consumed via llmRegistry.get with no parser). Deleted dead config app.chat.fallback.default-chain/chains (no Java reader). Updated 5 docs + README + llm-spi.md spec. 850/850 tests pass (4 new in ChatServiceImplResolveCandidateIdTest). Front-end callers must drop provider/ prefix from model field; old format returns 400 with clear message.

### Main Changes

(Add details)

### Git Commits

| Hash | Message |
|------|---------|
| `a98fa9b` | (see git log) |
| `3e537d7` | (see git log) |

### Testing

- [OK] (Add test results)

### Status

[OK] **Completed**

### Next Steps

- None - task complete


## Session 14: 登录路径 review 修复 + token 存储竞态消除

**Date**: 2026-06-18
**Task**: 登录路径 review 修复 + token 存储竞态消除
**Branch**: `agentic-rag-dev`

### Summary

对 CaptchaService/AuthServiceImpl/TokenCacheService 做 code review 修复并推送：(1) 异步权限预热改用专用显式 ThreadPoolExecutor(AuthAsyncConfig)，弃 CompletableFuture/common pool，不用结构化并发(fork-join 与 fire-and-forget 冲突)；(2) batchStoreTokens 改原子 Lua 条件写——disabled/deleted 用户不落 token，消除先写后查竞态；(3) logout 全端下线，去掉未用 accessToken 形参+死 extractToken；(4) 清理死代码 5 成员+对应测试，新增 checkAndIncrementLoginAttempts 用例；(5) CaptchaService Executors→显式 ScheduledThreadPoolExecutor 合规。测试 AuthServiceTest19/TokenCacheServiceTest4/SysUserServiceTest9 全绿，trellis-check PASS。

### Main Changes

(Add details)

### Git Commits

| Hash | Message |
|------|---------|
| `cf51035` | (see git log) |

### Testing

- [OK] (Add test results)

### Status

[OK] **Completed**

### Next Steps

- None - task complete


## Session 15: Phase D 消息总线收尾（legacy DLQ 退役 + 迁移补全）

**Date**: 2026-06-18
**Task**: Phase D 消息总线收尾（legacy DLQ 退役 + 迁移补全）
**Branch**: `agentic-rag-dev`

### Summary

落地 docs/design/messaging-bus.md §9 Phase D 全部项。D-4 切断 legacy Redis DLQ（ChatConversationHelper 不再吞异常+enqueue，chat-save 改由 bus 重试/%DLQ% 接管；publisher 降级路径加有限重试+chat.save.fallback_failed 告警；启动 7 天 soak）。D-5 Step1 补 messaging.consumer.receive.last.success gauge（O-03 卡死检测）；R2 lag/R3 assigned.groups 核对后关闭（前者需 rocketmq-tools+admin、后者公开 API 不可行，均被 Dashboard+既有指标覆盖）。D-6 方案 b 引入 micrometer-tracing-bridge-otel+opentelemetry-sdk，OpenTelemetryTracePropagator 用 W3C traceparent 跨消息传播 traceId+MDC，无 exporter（升 a 仅加 OTLP）。D-1 经评估 deferred（与 §2.2 非目标冲突，现状已崩溃安全）。D-2/D-3 删 MessageDeadLetterQueue/DeadLetterRetryScheduler/DeadLetterEntry+清引用（用户授权提前执行，Redis chat:dead-letter 残留需手动清），顺带修好 InfrastructureBoundaryTest 既有红。全量 mvnw test BUILD SUCCESS 0 失败。ecc:java-reviewer 审 D-4 APPROVE。LitePushConsumer 核查：公开 API 但不暴露 lag/assignment。6 commit 全 push origin/agentic-rag-dev。

### Main Changes

(Add details)

### Git Commits

| Hash | Message |
|------|---------|
| `c53783a` | (see git log) |
| `b5868cb` | (see git log) |
| `58cad50` | (see git log) |
| `1edd16a` | (see git log) |
| `747bcf2` | (see git log) |
| `3734e65` | (see git log) |

### Testing

- [OK] (Add test results)

### Status

[OK] **Completed**

### Next Steps

- None - task complete


## Session 16: 完成 MCP 代码审查修复与并发竞态修复

**Date**: 2026-07-10
**Task**: 完成 MCP 代码审查修复与并发竞态修复
**Branch**: `agentic-rag-dev`

### Summary

修复 MCP DB 驱动授权、命名、Bearer Token、生命周期、迁移与缓存边界；修复 ScopeJoinEngine completion lost-wakeup；全量 1226 测试通过。

### Main Changes

- Enforced PostgreSQL-backed MCP visibility and direct-call authorization with canonical policy keys and raw remote tool names.
- Added fail-closed Bearer Token envelopes, explicit client ownership transfer, per-server discovery isolation, V18 forward migration, Admin validation, batch I/O, and bounded caches.
- Split MCP Admin/runtime responsibilities into focused services and recorded the executable cross-layer contracts in the backend spec.
- Fixed `ScopeJoinEngine` completion lost-wakeup by snapshotting completion signals before draining terminal tasks.

### Git Commits

| Hash | Message |
|------|---------|
| `3eb3734` | (see git log) |
| `9c89c0a` | (see git log) |

### Testing

- [OK] `mvn test`: 1226 passed, 0 failed/errors/skipped.
- [OK] MCP suite: 118 passed; Bean graph, ArchUnit, migration, authorization, token, lifecycle, cache, and schema contracts covered.
- [OK] Concurrent regression suite: 93 tests repeated 5 times; P1-12 group repeated 30 times.
- [OK] PostgreSQL 18.4: empty V1-V18 and V17-V18 upgrade, invalid policy normalization, duplicate repair, constraints, and repeatability.
- [OK] GitNexus staged impact reviewed for both commits; MCP CRITICAL scope matched the intended 84 Admin/runtime flows.

### Status

[OK] **Completed**

### Next Steps

- Validate live third-party MCP Server interoperability before production rollout.
- Audit the pre-existing `DefaultSubtask` terminal-state/payload publication ordering separately.


## Session 17: MCP DB source-of-truth: review fixes, cleanup, and batch archive

**Date**: 2026-07-13
**Task**: MCP DB source-of-truth: review fixes, cleanup, and batch archive
**Branch**: `agentic-rag-dev`

### Summary

Completed mcp-db-source-of-truth task: code review P0-P3 fixes (13 issues), deleted legacy provider/filter/adapter chain (11 classes), fixed unused variables and stale imports, added .omp/ and lsp.json to gitignore. Archived 6 completed tasks.

### Main Changes

- Detailed change bullets were not supplied; see the summary above.

### Git Commits

| Hash | Message |
|------|---------|
| `6a40c11` | (see git log) |
| `0d6581a` | (see git log) |
| `8ac3804` | (see git log) |
| `4ef2f95` | (see git log) |
| `26644a8` | (see git log) |

### Testing

- Validation was not recorded for this session.

### Status

[OK] **Completed**

### Next Steps

- None - task complete


## Session 18: 前端 spec 最佳实践审查修订 + ESLint/tsconfig 严格化落地

**Date**: 2026-08-16
**Task**: 前端 spec 最佳实践审查修订 + ESLint/tsconfig 严格化落地
**Branch**: `agentic-rag-dev`

### Summary

按 React/TS 当前最佳实践审查并修订 .trellis/spec/frontend 五份规范（Effect 使用边界、React 19 用法边界与 memo 策略、Query v5 细则、RHF z.input、DTO 漂移风险、satisfies）；随后落地两项技术债：接入 ESLint 10 flat config（typescript-eslint type-aware + react-hooks v7 recommended 含 React Compiler 派生规则 + import-x/no-restricted-paths 强制分层依赖方向，唯一 except chat-store→api/conversations）与 tsconfig 开启 noUncheckedIndexedAccess/erasableSyntaxOnly；修复 59 个 lint error（ReferenceCard 数据 prop ref→reference、async 回调 void 包装、RHF handleSubmit 事件期求值、effect 同步 setState 改渲染期推导/事件处理器/状态下沉）与 18 个 type error，四道门全绿（lint 仅剩 5 条 TEMP-DEBUG warn）。顺带提交工作区预存的 KB-2/KB-3 预览/下载真实端点接入与后端 AsyncRequestTimeoutException SSE 超时静默处理（GlobalExceptionHandlerTest 12/12 通过）。

### Main Changes

- Detailed change bullets were not supplied; see the summary above.

### Git Commits

| Hash | Message |
|------|---------|
| `16f36da` | (see git log) |
| `0e2001a` | (see git log) |
| `462b969` | (see git log) |

### Testing

- Validation was not recorded for this session.

### Status

[OK] **Completed**

### Next Steps

- None - task complete


## Session 19: 批量上传改造：前端走批量端点 + 后端限 10 个/200MB

**Date**: 2026-08-16
**Task**: 批量上传改造：前端走批量端点 + 后端限 10 个/200MB
**Branch**: `agentic-rag-dev`

### Summary

前端 upload-button 重构：≤5MB 小文件合并为一次 POST /documents/upload/batch（响应按索引映射逐项成败），大文件保持分片，replace 场景保持逐文件；顺带修复 uploadDirect 从未发送 replaceDocumentId 的缺陷。后端 uploadBatch 入口级校验（DocumentProperties.maxBatchFiles=10/maxBatchTotalSize=200MB，错误码 104011/104012，个人/团队共享）；容器 max-request-size 55→205MB（max-file-size 不变）；GlobalExceptionHandler 补 MaxUploadSizeExceededException 兜底。后端全量 1638 测试通过，前端四道质量门全过。

### Main Changes

- Detailed change bullets were not supplied; see the summary above.

### Git Commits

| Hash | Message |
|------|---------|
| `ff22d54` | (see git log) |

### Testing

- Validation was not recorded for this session.

### Status

[OK] **Completed**

### Next Steps

- None - task complete
