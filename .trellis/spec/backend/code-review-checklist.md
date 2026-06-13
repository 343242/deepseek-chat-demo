# Code Review Checklist

> Review dimensions, criteria, and common pitfalls for this project.

---

## Overview

Code Review 分为 10 个维度，每个维度列出具体检查项。Review 时逐项确认，不要求每条都完美，但高风险项必须标出。

本规范与 [Quality Guidelines](./quality-guidelines.md)（设计原则、禁止模式）和 [Error Handling](./error-handling.md)（异常体系）配合使用。

---

## 1. 设计原则合规

> 对照 [Quality Guidelines — SOLID](./quality-guidelines.md) 逐项检查。

- [ ] **SRP** — 类/方法只有一个变更理由。如果一个类同时负责数据获取、业务逻辑和响应构造，拆分它
- [ ] **OCP** — 新功能通过扩展（新类/新方法）添加，而非修改已有逻辑。`if-else` 链按类型分支时应考虑策略模式
- [ ] **LSP** — 子类能替换父类而不破坏行为。继承 `AbstractException` 的异常必须通过 `GlobalExceptionHandler` 正常处理
- [ ] **ISP** — 接口粒度合理，不强迫实现方依赖不需要的方法。接口方法 > 5 个时评估是否拆分
- [ ] **DIP** — 高层模块不依赖底层实现细节。Service 层依赖 Mapper 接口而非实现类
- [ ] **DRY** — 无重复逻辑。相似代码出现在 3 处以上时提取共享方法或抽象基类
- [ ] **KISS** — 不引入不必要的间接层。两个实现不需要策略接口；一次调用不需要工厂

---

## 2. 反模式检测

- [ ] **God Class** — 类超过 300 行或注入超过 8 个依赖时，评估职责是否过多
- [ ] **循环依赖** — 模块间不允许 A → B → A。用 `mvn` 或 IDE 依赖分析工具检测。解耦方式：提取共享部分到新模块，或用事件/接口反转
- [ ] **模块边界模糊** — `chat` 包的代码不应直接操作 `team` 包的 Mapper，反之亦然。跨模块调用走 Service 接口
- [ ] **Feature Envy** — 方法频繁访问另一个类的内部数据。考虑将逻辑搬到数据所属的类
- [ ] **Shotgun Surgery** — 一个需求需要改动 5+ 个文件。考虑收敛变更点到统一入口
- [ ] **`@Transactional` 注解** — 禁止使用。用 `TransactionTemplate` 替代，精确控制事务边界。见 [Quality Guidelines — Forbidden Patterns](./quality-guidelines.md)

---

## 3. 资源管理

- [ ] **流/连接关闭** — `InputStream`、`OutputStream`、`Connection` 必须用 try-with-resources
- [ ] **Redis/HTTP 客户端** — 连接池配置合理（max-active、idle-timeout），不需要手动关闭
- [ ] **临时文件** — 用 `Files.createTempFile` 创建的文件在 `finally` 或 try-with-resources 中删除
- [ ] **RocketMQ Producer/Consumer** — Spring Bean 生命周期管理，不需要手动 shutdown（除非是非 Spring 管理的实例）
- [ ] **线程池** — 必须有 shutdown 策略。`ExecutorService` 用 `@PreDestroy` 或 `DisposableBean` 关闭
- [ ] **结构化并发 `Cleaner`** — `ScopeCleanupState` 在 scope 泄漏（未 close 即被 GC）时**必须真正清理资源**：owned executor → `shutdownNow()` + cancel subtasks；SHARED executor → 只 cancel subtask + 警告。**禁止只 log warning 不清理**（违反 try-with-resources 兜底语义）

---

## 4. 边界条件

- [ ] **空值处理** — 方法参数可能为 null 时用 `Objects.requireNonNull` 或在方法入口校验。Optional 用于返回值，不用于字段或方法参数
- [ ] **集合边界** — 空集合返回 `Collections.emptyList()` 而非 `null`。`List.get(index)` 前确认 index 范围
- [ ] **数值溢出** — 大数值计算（如 token 用量累计）考虑 `long` 是否足够
- [ ] **字符串边界** — 外部输入的字符串 trim 后再使用。超长输入有 `@Size` 限制
- [ ] **时间边界** — `LocalDateTime` 不带时区信息，API 入口统一用 UTC，展示层再转换

---

## 5. 并发安全

- [ ] **共享可变状态** — 多线程访问的可变字段必须用 `volatile`、`synchronized`、`Atomic*` 或 `ConcurrentHashMap` 保护
- [ ] **SimpleDateFormat** — 线程不安全，用 `DateTimeFormatter`（线程安全）替代
- [ ] **ThreadLocal 泄漏** — 请求结束必须在 Filter/Interceptor 中 `remove()`，否则线程池复用导致数据污染
- [ ] **锁顺序** — 多把锁必须按固定顺序获取，避免死锁
- [ ] **CompletableFuture** — 异步任务不继承请求上下文（SecurityContext、RequestAttributes）。需要时用 `ScopedTasks` 或手动传播
- [ ] **结构化并发** — 遵循 [Quality Guidelines — Structured Request-Scoped Concurrency](./quality-guidelines.md) 的 Scope 模式。子任务异常不吞掉，通过 `ScopeExecutionException` 传播
- [ ] **结构化并发 timeout** — `defaultTimeout` 默认 `30s`，禁止用 `ZERO`（已拦截，构造抛异常）。需要"无限等待"必须显式传 `ScopeOptions.NO_TIMEOUT` 并文档化
- [ ] **结构化并发 LIFO** — 嵌套 scope 必须按开域相反顺序关闭；`scopeClosed(expectedScopeId)` 校验栈顶，违例抛 `ScopeViolationException`
- [ ] **结构化并发 cross-field 校验** — `SHARED_EXECUTOR + executorOwnedByScope=true` 组合禁止（构造抛异常）；外部 executor 注入时强制 `executorOwnedByScope=false`

---

## 6. 性能

- [ ] **数据结构选择** — 能说明为什么选择当前结构：

  | 场景 | 推荐 | 避免 |
  |------|------|------|
  | 按 key 查找 O(1) | `HashMap` | `TreeMap`（除非需要排序） |
  | 有序遍历 | `ArrayList` | `LinkedList`（内存局部性差） |
  | 去重 + 查找 | `HashSet` | `contains` 遍历 `List` |
  | 读多写少缓存 | `ConcurrentHashMap` | `Collections.synchronizedMap` |
  | 频繁头部插入/删除 | `ArrayDeque` | `LinkedList` |

- [ ] **N+1 查询** — 循环内不发 SQL。用批量查询（MyBatis-Plus `selectBatchIds`、`in` 查询）替代。见 [Database Guidelines](./database-guidelines.md)
- [ ] **内存占用** — 大集合处理用流式（`Stream`）或分页，不一次性加载全量数据到内存。文档解析等场景注意 `byte[]` 生命周期
- [ ] **I/O 批量操作** — 数据库批量插入用 `saveBatch`，单条循环 insert 不可接受
- [ ] **缓存命中率** — 热点数据（SystemPrompt、ModelParams）走 Caffeine 本地缓存，避免每次请求查库。见 [Database Guidelines](./database-guidelines.md)
- [ ] **日志性能** — 用 `{}` 占位符而非字符串拼接：`log.debug("id={}", id)` 而非 `log.debug("id=" + id)`。见 [Logging Guidelines](./logging-guidelines.md)

---

## 7. 异常处理

> 对照 [Error Handling](./error-handling.md) 的三级体系和 Rules。

- [ ] **异常分类正确** — 客户端错误用 `ClientException`，服务端错误用 `ServiceException`，第三方错误用 `RemoteException`，消息总线用 `MessagingException`
- [ ] **不使用 `BusinessException`** — 新代码禁止使用（已 `@Deprecated`）
- [ ] **不使用 `IllegalArgumentException`** — 统一用 `ClientException` 替代
- [ ] **Controller 无 try-catch** — 异常全部交给 `GlobalExceptionHandler`
- [ ] **错误消息清晰** — 中文描述，面向用户友好，不含堆栈/SQL/内部类名
- [ ] **日志与异常配合** — 抛异常前记录 WARN/ERROR 日志（含上下文），不要只抛不记
- [ ] **异常链不断** — `new XxxException("msg", cause)` 保留原始 cause，不要吞掉

---

## 8. 内存泄漏防护

- [ ] **静态集合** — `static final Map/List` 作为缓存时必须有淘汰策略（Caffeine TTL、手动清理），否则只增不减
- [ ] **监听器/回调** — 注册后未注销的 Listener 会阻止所属对象被 GC。用 WeakHashMap 或确保 `@PreDestroy` 注销
- [ ] **ThreadLocal** — 见第 5 节。必须配对 `set()` / `remove()`
- [ ] **大对象缓存** — 缓存 value 超过 10KB 时评估是否应该用软引用/弱引用，或设容量上限
- [ ] **Stream 未关闭** — `InputStream`/`OutputStream`/`Stream` 不关闭会导致文件描述符泄漏

---

## 9. 可扩展性

- [ ] **无硬编码魔法数字** — 数字常量提取为 `private static final` 或枚举。错误码用 `IErrorCode` 枚举，不内联数字
- [ ] **无硬编码字符串** — 配置值用 `application.yml` + `@Value` 或 `@ConfigurationProperties`。Topic/Tag 名称走配置而非代码常量
- [ ] **依赖松耦合** — 面向接口编程。Spring 注入用构造器注入（不用 `@Autowired` 字段注入），便于测试和替换
- [ ] **扩展点预留** — 策略模式、工厂方法的选择有明确注释说明为什么需要灵活性。不需要扩展的地方不要过度设计
- [ ] **配置外置** — 环境相关配置（数据库 URL、密钥、超时时间）全部走 Spring 配置，不编译进代码

---

## 10. 代码质量

- [ ] **命名表达意图** — 类名是名词（`UsageService`），方法名是动词或动词短语（`resolveUserPermissions`）。缩写只用项目内通用的（DTO、VO、DAO）。不确定时读出来能听懂即可
- [ ] **方法长度** — 单方法不超过 40 行。超过时提取子方法，每个子方法做一件事
- [ ] **类长度** — 单类不超过 300 行（不含 import）。超过时考虑拆分职责
- [ ] **参数数量** — 方法参数超过 3 个时用 record 封装。构造器参数超过 5 个评估是否需要 Builder
- [ ] **无重复代码** — 复制粘贴超过 3 行的代码块必须提取。如果两段代码"几乎一样"，抽象共同的模板方法
- [ ] **注释有价值** — 注释解释 WHY（为什么这么做），不解释 WHAT（代码本身已经说明）。没有注释好过误导性注释
- [ ] **测试覆盖** — 公开方法有对应单元测试。异常路径有测试用例。集成测试覆盖关键链路（消息收发、数据库读写）

---

## Review 流程

1. **提交前自检** — 作者按本 Checklist 逐项检查，高风险项在 PR 描述中说明
2. **Review 重点** — 优先关注第 2（反模式）、5（并发）、7（异常处理）三个维度，这些是线上故障高发区
3. **性能争议** — 第 6（性能）维度的数据结构选择需要给出理由，不要求最优但要求有意识的选择
4. **设计原则** — 第 1 维度的 SOLID 检查作为长期目标，新代码不应引入明显违反，老代码渐进改善
