# PRD: Fix LLM Module Self-Review P1 Issues

> **Task**: 06-14-fix-llm-module-self-review-p1-issues
> **Source**: `docs/reviews/2026-06-14-infrastructure-llm-spec-review.md`（自身审查 P1-N1/N2/N3）
> **Scope**: `src/main/java/com/smart/rag/infrastructure/llm/`
> **Status**: planning

---

## 背景与目标

`docs/reviews/2026-06-14-infrastructure-llm-spec-review.md` 报告中识别的 3 项 P1 新发现问题，独立于 Mimo 审查的 6 项（已在 `49c150c` 修复）。本任务完成 3 项修复并验证。

## 范围

### In Scope（3 项 P1）

| ID | 文件 | 问题 |
|----|------|------|
| P1-N1 | `client/HttpClientErrorHandler.java` | `translate()` 方法签名声明返回 `RuntimeException`，但所有分支只 `throw`，伪装的 sneaky-throw 模式 |
| P1-N2 | `resilience/CircuitBreaker.java` | `executeStream()` 仅 `doOnComplete`/`doOnError` 释放 probe 槽，`cancel()` 路径泄漏，导致 HALF_OPEN 永久卡死 |
| P1-N3 | `metrics/LlmMetrics.java`（待代码确认） | gauge 注册可能未做幂等保护，重复 refresh 时累积 |

### Out of Scope

- 2026-06-14 报告中的 9 项 P2 + 5 项 P3
- 跨模块影响修复（如有）

## 详细修复方案

### P1-N1 HttpClientErrorHandler sneaky-throw

**当前** (`client/HttpClientErrorHandler.java:42`)：
```java
public static RuntimeException translate(String operation, String url, Exception e) {
    if (e instanceof RemoteException re) {
        throw re;  // 全分支只 throw
    }
    if (e instanceof IOException io) { ... throw new RemoteException(...); }
    // ...
}
```

**修复方案**：保留 `RuntimeException` 返回类型（与现有调用点 `throw translate(...)` 对齐），所有分支改为 `return new XxxException(...)`：

```java
public static RuntimeException translate(String operation, String url, Exception e) {
    if (e instanceof RemoteException re) return re;
    if (e instanceof IOException io) {
        log.warn(...);
        return new RemoteException(LLM_TRANSIENT_ERROR, ...);
    }
    if (e instanceof RestClientResponseException rcre) {
        // ... return appropriate RemoteException
    }
    return new RemoteException(LLM_STREAM_ERROR, ...);
}
```

**验证**：
- 现有调用点 `throw HttpClientErrorHandler.translate(...)` 行为不变
- 不再可能因为漏写 throw 而返回 null（编译器层面不强制，但语义清晰）

---

### P1-N2 CircuitBreaker.executeStream probe 槽泄漏

**当前** (`resilience/CircuitBreaker.java:79-92`)：
```java
return Flux.defer(streamSupplier)
    .doOnComplete(() -> {
        registry.recordSuccess(candidateId);
        registry.releaseProbe(candidateId);
    })
    .doOnError(e -> {
        if (...) registry.recordFailure(candidateId);
        registry.releaseProbe(candidateId);
    });
```

**问题**：当订阅被 `cancel()`（客户端断开、上游超时）时，`doOnComplete` 和 `doOnError` 都不触发，probe 槽泄漏。

**修复方案**：用 `doFinally` 统一释放 probe 槽：

```java
return Flux.defer(streamSupplier)
    .doOnNext(__ -> {})  // 保持现有 doOnNext 行为
    .doFinally(signal -> {
        registry.releaseProbe(candidateId);
        if (signal == SignalType.ON_COMPLETE) {
            registry.recordSuccess(candidateId);
        } else if (signal == SignalType.ON_ERROR
                   && !(lastError instanceof ProbeTimeoutException)
                   && isInfraFailure(lastError)) {
            registry.recordFailure(candidateId);
        }
    });
```

**注意**：`doFinally` 不能直接访问 error 对象，需要先用 `doOnError` 捕获到局部变量，或在 doFinally 中通过其他方式判断。Reactor 提供的 `Signal` 枚举只有 `ON_COMPLETE`/`ON_ERROR`/`CANCEL`/`ON_NEXT` 等类型，不含异常本身。

**实现建议**：保留 `doOnError` 记录 failure，`doFinally` 仅释放 probe：

```java
final AtomicReference<Throwable> lastErr = new AtomicReference<>();
return Flux.defer(streamSupplier)
    .doOnError(lastErr::set)
    .doFinally(signal -> {
        registry.releaseProbe(candidateId);
        if (signal == SignalType.ON_COMPLETE) {
            registry.recordSuccess(candidateId);
        } else if (signal == SignalType.ON_ERROR) {
            Throwable e = lastErr.get();
            if (!(e instanceof ProbeTimeoutException) && isInfraFailure(e)) {
                registry.recordFailure(candidateId);
            }
        }
        // CANCEL 路径：仅释放 probe，不修改 success/failure 计数
    });
```

**测试**：在 `CircuitBreakerTest` 增加 `executeStream cancel releases probe slot` 用例。

---

### P1-N3 LlmMetrics gauge 幂等保护（待代码确认）

**问题来源**：自身审查报告基于历史 2026-06-13 报告 P1-1 提到"重复 refresh 时累积 gauge"。需先读取 `metrics/LlmMetrics.java` 当前实现确认。

**验证步骤**：
1. 读取 `LlmMetrics.java`，查找 `registerCircuitBreakerGauge` 等方法
2. 检查是否已用 `meterRegistry.find(...).meter()` 判重
3. 若未做幂等保护，加入：
   ```java
   if (meterRegistry.find("llm.circuit.state").tag("candidate", id).meter() != null) {
       return;  // 已注册，跳过
   }
   ```

**未确认前的策略**：如果代码已经做了幂等保护，仅添加注释说明设计意图；否则实施修复。

---

## 验证

### 编译验证
```bash
mvn -pl . clean compile -q -DskipTests
```

### 单元测试
- `HttpClientErrorHandlerTest`（如不存在则新增）覆盖 sneaky-throw 修复
- `CircuitBreakerTest` 新增 cancel 路径用例
- `LlmMetricsTest`（如存在）

### 影响分析（GitNexus）
- 修改前 `gitnexus_impact({target, repo: "smart-rag", direction: "upstream"})`
- 提交前 `gitnexus_detect_changes({repo: "smart-rag"})`

## 出口标准

- [ ] 3 项修复全部完成（P1-N3 若已正确实现则仅添加注释）
- [ ] 编译通过
- [ ] 现有测试全绿 + 新增测试覆盖 P1-N1/N2
- [ ] `gitnexus_detect_changes` 影响范围与预期一致

## 提交策略

单一 commit：
```
fix(llm): address self-review P1 findings

- HttpClientErrorHandler.translate() returns exceptions instead of sneaky-throw
- CircuitBreaker.executeStream uses doFinally to release probe on cancel
- LlmMetrics gauge registration guarded against duplicates (if applicable)
- Tests for cancel-path probe release and error-handler return semantics
```
