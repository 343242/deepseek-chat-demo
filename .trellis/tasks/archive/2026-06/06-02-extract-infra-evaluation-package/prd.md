# 抽离基础设施层并提升评估包

## Goal

优化 Java 包结构：将业务模块中与业务无关、可跨模块复用的基础设施能力抽离到顶层 `com.smart.rag.infrastructure` 包，同时将 `src/main/java/com/smart/rag/rag/evaluation` 从 RAG 模块下提升为顶层 `com.smart.rag.evaluation` 业务包，降低模块耦合并让包边界更清晰。

## Requirements

* 将 `com.smart.rag.rag.evaluation` 迁移为 `com.smart.rag.evaluation`，测试包同步迁移为 `src/test/java/com/smart/rag/evaluation`。
* 将明确非业务领域对象的支撑能力从业务模块子包迁移到顶层 `infrastructure`：
  * `chat.advisor` -> `infrastructure.ai.advisor`
  * `chat.client` -> `infrastructure.ai.client`
  * `chat.provider` -> `infrastructure.ai.provider`
  * `chat.fallback` -> `infrastructure.ai.fallback`
  * `chat.memory` -> `infrastructure.ai.memory`
  * `chat.content` -> `infrastructure.ai.content`
  * `agent.guardrail` -> `infrastructure.agent.guardrail`
  * `agent.trace` -> `infrastructure.agent.trace`
  * `agent.workspace` -> `infrastructure.agent.workspace`
* 更新所有 Java package/import 和测试引用，保证编译通过。
* 不修改 REST endpoint、配置属性 key、数据库表结构、Bean 语义、方法签名或业务逻辑。
* 不引入新依赖。

## Acceptance Criteria

* [ ] 源码中不再存在 `package com.smart.rag.rag.evaluation`。
* [ ] 源码中不再存在上述已迁移的旧基础设施 package 声明。
* [ ] `mvn test` 或项目等价测试命令通过；若全量测试受环境限制，至少运行编译和受影响模块测试。
* [ ] GitNexus detect changes 影响面与包迁移预期一致。

## Definition of Done

* Tests added/updated where package-only tests require path/package correction.
* Lint / typecheck / tests green or documented with concrete blocker.
* Trellis/spec impact reviewed.
* Changes committed and pushed per project preference.

## Technical Approach

Use a mechanical package migration. Move directories first, then update `package` declarations and imports globally for the exact old package prefixes. Keep class names, constructors, method signatures, configuration prefixes and endpoints unchanged. This is intentionally a structural refactor, not a behavioral cleanup.

## Decision (ADR-lite)

**Context**: The existing code mixes reusable AI client/provider/advisor/fallback/memory and agent runtime support under `chat` and `agent`, while evaluation is nested under `rag` even though it is a separate evaluation domain.

**Decision**: Introduce `com.smart.rag.infrastructure` as the top-level home for cross-module technical support, and promote evaluation to `com.smart.rag.evaluation`.

**Consequences**: Imports change broadly. GitNexus reports high risk for shared infra symbols, so this task avoids behavior changes and relies on compile/tests for verification.

## Out of Scope

* Changing API URLs or controller behavior.
* Renaming classes/methods beyond package declarations.
* Changing `@ConfigurationProperties` prefixes.
* Moving domain-specific agent tools or chat business services in this pass.
* Reworking module architecture, Maven modules, or database schemas.

## Technical Notes

* GitNexus impact before edits:
  * `EvaluationRunner`: LOW, 1 direct upstream caller.
  * `ChatClientRegistry`: HIGH, 25 impacted symbols, 16 direct.
  * `AgentGuardrails`: HIGH, affects advisor/chat execution flows.
  * `ToolWorkspace`: HIGH, 18 direct impacted symbols.
* Relevant specs read:
  * `.trellis/spec/backend/directory-structure.md`
  * `.trellis/spec/backend/quality-guidelines.md`
  * `.trellis/spec/backend/error-handling.md`
  * `.trellis/spec/backend/logging-guidelines.md`
  * `.trellis/spec/guides/cross-layer-thinking-guide.md`
