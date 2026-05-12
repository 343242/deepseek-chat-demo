# P1: 线程池基础设施

## 目标
创建 IO 密集型和 CPU 密集型双线程池，参数外部化到 YAML，支持动态配置。

## 实现清单

### 1. EtlExecutorProperties
- 路径: `com.demo.chat.rag.config.EtlExecutorProperties`
- `@ConfigurationProperties(prefix = "app.etl.executor")`
- 内嵌 `PoolConfig io` 和 `PoolConfig cpu` 两个配置组
- PoolConfig: corePoolSize, maxPoolSize, queueCapacity, threadNamePrefix, keepAliveSeconds

### 2. EtlExecutorConfig
- 路径: `com.demo.chat.rag.etl.EtlExecutorConfig`
- `@Configuration`
- Bean `etlIoExecutor` → ThreadPoolTaskExecutor（IO 密集型：大核心池、大队列）
- Bean `etlCpuExecutor` → ThreadPoolTaskExecutor（CPU 密集型：核心数=CPU核数，小队列）
- 两个线程池均配置：rejectedPolicy=CALLER_RUNS、waitForTasksToCompleteOnShutdown=true
- Bean `EtlTaskExecutorBridge`：统一门面类，持有两个线程池引用，提供 `submitIo()` / `submitCpu()` / `submitIoAll()`

### 3. 线程池安全
- 所有提交使用 `CompletableFuture` 返回值
- 异常通过 `CompletableFuture.exceptionally()` 捕获
- 不使用 ThreadLocal 传递 SecurityContext（线程池场景不安全），显式传参

## 配置默认值
```yaml
app:
  etl:
    executor:
      io:
        core-pool-size: 4
        max-pool-size: 8
        queue-capacity: 50
        thread-name-prefix: "etl-io-"
        keep-alive-seconds: 60
      cpu:
        core-pool-size: 2
        max-pool-size: 4
        queue-capacity: 20
        thread-name-prefix: "etl-cpu-"
        keep-alive-seconds: 60
```

## 验收
- [x] 两个线程池 Bean 注册成功
- [x] 参数从 YAML 读取
- [x] 无硬编码线程池参数
