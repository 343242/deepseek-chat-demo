# LLM Unified SPI — 测试策略

> 本文档从 [llm-unified-spi-refactoring.md](llm-unified-spi-refactoring.md) §20 拆分而来。设计文档稳定后同步修复测试代码。

本节定义 LLM Unified SPI 框架各层的测试策略，确保弹性层（Retry / CircuitBreaker / Probe）和编排层（FallbackExecutor）的行为在单元、集成和验收三个级别均被覆盖。

### 20.1 测试目录结构

```
src/test/java/com/smart/rag/infrastructure/llm/
├── unit/
│   ├── resilience/
│   │   ├── RetryPolicyTest.java                 -- 重试策略：退避计算、异常过滤、最大次数
│   │   ├── CircuitBreakerTest.java              -- 三态转换：CLOSED→OPEN→HALF_OPEN→CLOSED
│   │   └── ProbeHandlerTest.java                -- 首包探测、超时检测、成功回调
│   ├── orchestration/
│   │   └── FallbackExecutorTest.java            -- 候选降级、异常分类、流式清理
│   ├── registry/
│   │   └── LlmClientRegistryTest.java           -- 快照读、CAS 刷新、并发安全
│   └── spi/
│       ├── GenericOpenAiProviderTest.java        -- YAML 解析、候选排序、客户端创建
│       └── CapabilityStrategyRegistryTest.java   -- Strategy 查找与匹配
├── integration/
│   ├── ResilientChatClientIT.java               -- Retry + CircuitBreaker + Probe 联合
│   ├── StreamingFallbackIT.java                 -- 流式降级端到端
│   └── RegistryRefreshIT.java                   -- 运行时刷新 + 在飞请求安全
└── acceptance/
    └── NewProviderAcceptanceTest.java            -- 新 Provider 接入验收模板
```

### 20.2 弹性层单元测试

#### 20.2.1 CircuitBreaker 三态转换

```java
class CircuitBreakerTest {

    CircuitBreaker cb;
    Clock clock;

    @BeforeEach
    void setUp() {
        clock = Mockito.mock(Clock.class);
        cb = new CircuitBreaker(new CircuitBreakerConfig(5, Duration.ofSeconds(30)), clock);
    }

    // --- CLOSED → OPEN ---
    @Test
    void shouldOpenOnConsecutiveFailures() {
        for (int i = 0; i < 5; i++) {
            cb.recordFailure("c1", new RuntimeException("err"));
        }
        assertThat(cb.stateOf("c1")).isEqualTo(CircuitBreakerState.OPEN);
    }

    // --- OPEN → HALF_OPEN (超时后) ---
    @Test
    void shouldTransitionToHalfOpenAfterTimeout() {
        // 先触发 OPEN
        for (int i = 0; i < 5; i++) cb.recordFailure("c1", new RuntimeException());
        assertThat(cb.stateOf("c1")).isEqualTo(CircuitBreakerState.OPEN);

        // 模拟时间流逝超过 openTimeout
        when(clock.millis()).thenReturn(System.currentTimeMillis() + 31_000);
        assertThat(cb.stateOf("c1")).isEqualTo(CircuitBreakerState.HALF_OPEN);
    }

    // --- HALF_OPEN → CLOSED (探测成功) ---
    @Test
    void shouldCloseOnProbeSuccess() {
        for (int i = 0; i < 5; i++) cb.recordFailure("c1", new RuntimeException());
        when(clock.millis()).thenReturn(System.currentTimeMillis() + 31_000);
        assertThat(cb.stateOf("c1")).isEqualTo(CircuitBreakerState.HALF_OPEN);

        cb.recordProbeSuccess("c1");
        assertThat(cb.stateOf("c1")).isEqualTo(CircuitBreakerState.CLOSED);
    }

    // --- HALF_OPEN → OPEN (探测失败) ---
    @Test
    void shouldReopenOnProbeFailure() {
        for (int i = 0; i < 5; i++) cb.recordFailure("c1", new RuntimeException());
        when(clock.millis()).thenReturn(System.currentTimeMillis() + 31_000);
        cb.recordFailure("c1", new RuntimeException()); // 探测失败
        assertThat(cb.stateOf("c1")).isEqualTo(CircuitBreakerState.OPEN);
    }

    // --- 并发安全：多线程同时触发状态转换 ---
    @Test
    void shouldHandleConcurrentStateTransitions() throws Exception {
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch latch = new CountDownLatch(threads);
        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    for (int j = 0; j < 100; j++) {
                        cb.recordFailure("c1", new RuntimeException());
                        cb.stateOf("c1");
                    }
                } finally {
                    latch.countDown();
                }
            });
        }
        assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
        // 无异常即通过——证明无死锁 / CAS 竞争丢失
        pool.shutdownNow();
    }
}
```

#### 20.2.2 ProbeHandler 首包探测与回调

```java
class ProbeHandlerTest {

    @Test
    void shouldNotifyOnFirstPacket() {
        ProbeHandler handler = new ProbeHandler(Duration.ofMillis(500));
        AtomicBoolean notified = new AtomicBoolean(false);

        Flux<String> source = Flux.just("token1", "token2", "token3");
        Flux<String> wrapped = handler.wrap("c1", source, () -> notified.set(true));

        StepVerifier.create(wrapped)
            .expectNext("token1", "token2", "token3")
            .verifyComplete();

        assertThat(notified).isTrue();
    }

    @Test
    void shouldTimeoutOnNoFirstPacket() {
        ProbeHandler handler = new ProbeHandler(Duration.ofMillis(100));

        Flux<String> source = Flux.never();
        Flux<String> wrapped = handler.wrap("c1", source, null);

        StepVerifier.create(wrapped)
            .expectError(TimeoutException.class)
            .verify(Duration.ofSeconds(2));
    }

    @Test
    void shouldNotCallCallbackTwice() {
        ProbeHandler handler = new ProbeHandler(Duration.ofMillis(500));
        AtomicInteger count = new AtomicInteger(0);

        Flux<String> source = Flux.just("a", "b");
        Flux<String> wrapped = handler.wrap("c1", source, count::incrementAndGet);

        StepVerifier.create(wrapped).expectNextCount(2).verifyComplete();
        assertThat(count.get()).isEqualTo(1);
    }
}
```

#### 20.2.3 RetryPolicy 退避与异常过滤

```java
class RetryPolicyTest {

    @Test
    void shouldNotRetryOnNonRetryableException() {
        RetryPolicy policy = new RetryPolicy(3, Duration.ofMillis(100), Set.of());
        assertThat(policy.shouldRetry(new IllegalArgumentException("bad arg"))).isFalse();
    }

    @Test
    void shouldExhaustMaxAttempts() {
        RetryPolicy policy = new RetryPolicy(3, Duration.ofMillis(10), Set.of(IOException.class));
        assertThat(policy.shouldRetry(new IOException("timeout"))).isTrue();   // attempt 1
        assertThat(policy.shouldRetry(new IOException("timeout"))).isTrue();   // attempt 2
        assertThat(policy.shouldRetry(new IOException("timeout"))).isFalse();  // attempt 3 → 放弃
    }
}
```

### 20.3 编排层单元测试

#### 20.3.1 FallbackExecutor 降级逻辑

```java
class FallbackExecutorTest {

    FallbackExecutor executor;
    LlmClientRegistry registry;

    @BeforeEach
    void setUp() {
        registry = Mockito.mock(LlmClientRegistry.class);
        executor = new FallbackExecutor(registry);
    }

    @Test
    void shouldFallbackOnFirstCandidateFailure() {
        ChatCapable primary = mock(ChatCapable.class);
        ChatCapable secondary = mock(ChatCapable.class);
        when(primary.chat(any())).thenThrow(new LlmException("primary down"));
        when(secondary.chat(any())).thenReturn(LlmResponse.ok("fallback-result"));

        when(registry.getFallbackChain(eq(CHAT), eq(ChatCapable.class)))
            .thenReturn(List.of(primary, secondary));

        LlmResponse result = executor.execute(CHAT, ChatCapable::chat, req);
        assertThat(result.getContent()).isEqualTo("fallback-result");
        verify(primary).chat(any());
        verify(secondary).chat(any());
    }

    @Test
    void shouldThrowWhenAllCandidatesFail() {
        ChatCapable c1 = mock(ChatCapable.class);
        ChatCapable c2 = mock(ChatCapable.class);
        when(c1.chat(any())).thenThrow(new LlmException("c1"));
        when(c2.chat(any())).thenThrow(new LlmException("c2"));

        when(registry.getFallbackChain(eq(CHAT), eq(ChatCapable.class)))
            .thenReturn(List.of(c1, c2));

        assertThatThrownBy(() -> executor.execute(CHAT, ChatCapable::chat, req))
            .isInstanceOf(AllCandidatesExhaustedException.class)
            .hasMessageContaining("c1")
            .hasMessageContaining("c2");
    }

    @Test
    void shouldCleanupEmittedTokensOnStreamFailure() {
        // 流式首包超时后，FallbackExecutor 应取消上游 Flux 并降级到下一个候选
        ChatCapable primary = mock(ChatCapable.class);
        ChatCapable secondary = mock(ChatCapable.class);

        Flux<String> failingStream = Flux.error(new TimeoutException("first-packet timeout"));
        when(primary.chatStream(any())).thenReturn(failingStream);
        when(secondary.chatStream(any())).thenReturn(Flux.just("recovered"));

        when(registry.getFallbackChain(eq(CHAT), eq(ChatCapable.class)))
            .thenReturn(List.of(primary, secondary));

        StepVerifier.create(executor.executeStream(CHAT, ChatCapable::chatStream, req))
            .expectNext("recovered")
            .verifyComplete();
    }
}
```

### 20.4 Registry 并发刷新测试

```java
class LlmClientRegistryTest {

    @Test
    void shouldNotAffectInFlightReadsDuringRefresh() throws Exception {
        LlmClientRegistry registry = new LlmClientRegistry();
        registry.register("c1", mock(ChatCapable.class));

        // 模拟在飞请求持有旧快照
        AtomicReference<Optional<ChatCapable>> inFlight = new AtomicReference<>();
        CountDownLatch reading = new CountDownLatch(1);
        CountDownLatch refreshed = new CountDownLatch(1);

        Thread reader = new Thread(() -> {
            inFlight.set(registry.get("c1", ChatCapable.class));
            reading.countDown();
            try { refreshed.await(5, TimeUnit.SECONDS); } catch (InterruptedException e) {}
            // 再次读取，应拿到新快照
            inFlight.set(registry.get("c2", ChatCapable.class));
        });

        Thread writer = new Thread(() -> {
            try { reading.await(5, TimeUnit.SECONDS); } catch (InterruptedException e) {}
            Map<String, CapabilityClient> newSnap = new HashMap<>();
            newSnap.put("c2", mock(ChatCapable.class));
            registry.refresh(newSnap);
            refreshed.countDown();
        });

        reader.start();
        writer.start();
        reader.join(5000);
        writer.join(5000);

        assertThat(inFlight.get()).isPresent(); // c2 存在于刷新后快照
    }

    @Test
    void shouldPreserveCircuitBreakerStateAcrossRefresh() {
        LlmClientRegistry registry = new LlmClientRegistry();
        // 注册 c1，使其熔断器进入 OPEN 状态
        registry.register("c1", mock(ChatCapable.class));
        registry.circuitBreaker("c1").recordFailure("c1", new RuntimeException());
        // ... 触发 OPEN ...

        // 刷新快照但保留弹性状态
        Map<String, CapabilityClient> newSnap = Map.of("c1", mock(ChatCapable.class));
        registry.refresh(newSnap);

        // 熔断器状态应保留
        assertThat(registry.circuitBreaker("c1").stateOf("c1")).isEqualTo(CircuitBreakerState.OPEN);
    }
}
```

### 20.5 集成测试

#### 20.5.1 ResilientChatClient 联合测试

使用 WireMock 模拟远程 LLM 端点，验证 Retry → CircuitBreaker → Probe 的协同行为：

```java
@SpringBootTest
@AutoConfigureWireMock(port = 0)
class ResilientChatClientIT {

    @Autowired WireMockServer wireMock;

    @Test
    void shouldRetryThenSucceed(WireMockServer wm) {
        // 第 1 次返回 500，第 2 次返回 200
        wm.stubFor(post(urlEqualTo("/v1/chat/completions"))
            .inScenario("retry")
            .whenScenarioStateIs("Started")
            .willReturn(aResponse().withStatus(500))
            .willSetStateTo("Failed Once"));

        wm.stubFor(post(urlEqualTo("/v1/chat/completions"))
            .inScenario("retry")
            .whenScenarioStateIs("Failed Once")
            .willReturn(aResponse().withStatus(200)
                .withBody("{\"choices\":[{\"message\":{\"content\":\"ok\"}}]}")));

        // 调用 ResilientChatClient，验证重试后成功
        LlmResponse result = client.chat(request);
        assertThat(result.getContent()).isEqualTo("ok");
    }
}
```

### 20.6 新 Provider 验收测试模板

新 Provider 接入时，需通过以下验收清单（手动或自动化）：

```java
/**
 * 新 Provider 验收测试模板。
 * 复制此类，将 {@code YourProvider} 替换为新 Provider 实现类。
 */
class NewProviderAcceptanceTest {

    // 1. Provider 能正确注册到 Registry
    @Test
    void providerShouldRegister() { /* ... */ }

    // 2. createClient 返回的 CapabilityClient 实现了预期接口
    @Test
    void clientShouldImplementExpectedCapability() { /* ... */ }

    // 3. 同步调用返回有效结果（可用 WireMock 模拟）
    @Test
    void chatShouldReturnResponse() { /* ... */ }

    // 4. 流式调用返回有效 Token 序列
    @Test
    void chatStreamShouldReturnTokens() { /* ... */ }

    // 5. 异常场景：超时 / 401 / 429 均被正确包装为 LlmException
    @Test
    void shouldWrapProviderExceptions() { /* ... */ }

    // 6. YAML 声明式配置加载正确（仅 GenericOpenAiProvider）
    @Test
    void yamlConfigShouldLoad() { /* ... */ }

    // 7. ToolCalling 能力在声明后可被 Registry 检测
    @Test
    void toolCallingCapabilityShouldBeDetected() { /* ... */ }
}
```

### 20.7 Mock 与桩模式

| 场景 | 推荐方式 | 说明 |
|------|----------|------|
| 模拟远程 LLM 端点 | WireMock | 支持延迟注入、场景状态机、流式 SSE 模拟 |
| 模拟首包超时 | `Flux.never().delaySubscription(Duration.ofSeconds(10))` | 配合 `StepVerifier.withVirtualTime` |
| 模拟 CircuitBreaker OPEN | 直接调用 `recordFailure` N 次 | 无需 Mock，直接驱动状态转换 |
| 模拟 FallbackExecutor 候选链 | `Mockito.mock(ChatCapable.class)` 组合 | 每个 Mock 配置不同的异常/返回 |
| 模拟时钟（CB 超时） | `Mockito.mock(Clock.class)` | 控制 `millis()` 返回值驱动 HALF_OPEN 转换 |
| 模拟 Registry 刷新 | `CountDownLatch` 协调读写线程 | 验证 AtomicReference CAS 安全 |

### 20.8 测试命名与标签约定

```java
@Tag("llm-spi")
@Tag("unit")          // 或 "integration" / "acceptance"
@DisplayName("CircuitBreaker 三态转换")
class CircuitBreakerTest { ... }
```

- 所有 SPI 测试统一标记 `@Tag("llm-spi")`，支持 `mvn test -Dgroups=llm-spi` 按模块执行。
- 集成测试后缀 `IT`（不自动运行于 `mvn test`，需 `mvn verify` 或显式 `-Dgroups=llm-spi`）。

> **实施建议**：测试与实现同步开发（TDD）。弹性层优先级最高（RetryPolicy → CircuitBreaker → ProbeHandler），编排层次之，Registry 并发测试最后补齐。验收模板在首个 Provider 接入时填充具体实现。
