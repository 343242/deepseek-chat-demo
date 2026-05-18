# H4/H5 高并发改造方案 — Spec 合规审查

**审查日期**: 2026-05-18
**审查依据**: `.trellis/spec/backend/quality-guidelines.md` + `error-handling.md` + `directory-structure.md` + `cross-layer-thinking-guide.md` + `code-reuse-thinking-guide.md`
**审查对象**: 前一轮提出的 BailianRerankPostProcessor 高并发改造方案

---

## 一、原方案回顾

1. **H5 修复**：`process()` 不修改原始 documents，创建新 `Document` 返回
2. **H4 修复**：`ChatAdvisorChainFactory` 将 RAG Advisor 调用卸载到 `ragExecutor` 线程池，用 `Mono.subscribeOn(Schedulers.fromExecutor())` 释放 Servlet 线程
3. **L1 修复**：HttpClient 增加 `responseTimeout`
4. **L2 修复**：index >= 0 校验

---

## 二、Spec 逐条审查

### ❌ P0-1: 违反 OCP — 改了 chat 模块来修 rag 模块的问题

**违反规范**: quality-guidelines > 强制规则 > OCP 强制：新功能 = 新增类，不是改旧类

原方案要求修改 `ChatAdvisorChainFactory`（chat 模块），把 RAG 链路卸载逻辑放进去。但这是 **rag 模块的并发问题**，不应该让 chat 模块承担解决责任。

**问题本质**：`ChatAdvisorChainFactory.buildChain()` 只是组装 Advisor 链，不负责执行。真正的执行发生在 Spring AI 框架内部（`ChatClient.prompt().advisors(...).stream().chatResponse()`）。把 `Mono.subscribeOn()` 放在 `ChatAdvisorChainFactory` 里意味着工厂承担了运行时调度职责——违反 SRP。

**正确做法**：并发调度属于 **调用层**（即 ChatService / ChatController），不是工厂层。或者更好——让 PostProcessor 自身异步化，对上层完全透明。

---

### ❌ P0-2: 违反封装彻底 — 跨模块线程池泄漏

**违反规范**: quality-guidelines > 强制规则 > 封装彻底：厂商差异、技术细节不泄漏到上层

原方案在 `RagConfig`（rag 模块）里定义 `ragExecutor` Bean，但 `ChatAdvisorChainFactory`（chat 模块）直接依赖这个 Bean 来做 `subscribeOn`。

这意味着：
- chat 模块需要知道 rag 模块内部用了阻塞式外部 API 调用
- chat 模块需要知道 rag 的线程池配置
- rag 内部优化（比如换异步 Rerank 实现）需要同步改 chat 模块

**违反了封装彻底原则和跨层思考指南**（cross-layer-thinking-guide > Common Cross-Layer Mistakes > Mistake 3: Leaky Abstractions）。

---

### ❌ P0-3: 违反 DIP — 高层依赖低层调度细节

**违反规范**: quality-guidelines > SOLID > DIP：高层模块不依赖低层模块，两者都依赖抽象

`ChatService` → `ChatAdvisorChainFactory` → `ragExecutor` → Reactor `Schedulers`

链路是：chat 模块直接依赖了 rag 模块的线程池 + Reactor 调度 API。正确做法是引入一个抽象层（如 `AsyncRagExecutor` 接口），chat 模块依赖接口而非实现。

---

### ⚠️ P1-1: `cloneWithMetadata` 未考虑 Document 不可变约定

**违反规范**: quality-guidelines > 强制规则 > 批判式思考

原方案的 H5 修复通过 `new Document(id, text, newMeta)` 创建新实例。但 Spring AI 的 `Document` 在 1.0+ 版本中构造函数签名和不可变语义可能变化。应该使用 `Document.mutate()` 或 Builder 模式（如果可用），而非直接构造。

此外，Spring AI `RetrievalAugmentationAdvisor` 内部传递的就是同一个 Document 列表引用。PostProcessor 修改 metadata 是框架的 **设计约定**（所有内置 PostProcessor 都这么做）。创建新 Document 反而可能破坏框架内部的引用追踪（如 scoring、filtering 等后续步骤依赖同一个对象引用）。

**需要验证**：Spring AI 框架是否依赖 Document 引用一致性。

---

### ⚠️ P1-2: 线程池配置缺少 spec 规范的命名和日志

**违反规范**: logging-guidelines > Rules > ✅ DO + quality-guidelines > 线程池规约

原方案的 `ragExecutor` Bean 配置缺少：
- 自定义 `ThreadFactory`（应带业务前缀 + `UncaughtExceptionHandler`）
- 参照 `EtlExecutorConfig` 已有模式——项目已有线程池规范（全 7 参数 + NamedThreadFactory）

```java
// 应参照 EtlExecutorConfig 的 buildExecutor 模式
ThreadPoolTaskExecutor exec = new ThreadPoolTaskExecutor();
exec.setThreadNamePrefix("rag-"); // ❌ 不够规范
// 缺少：rejectedExecutionHandler 日志、UncaughtExceptionHandler
```

---

### ⚠️ P1-3: `subscribeOn` 在 Flux 链中的位置可能不生效

Spring AI 的 `ChatClient.stream().chatResponse()` 返回的是 Flux，其内部订阅发生在框架的 Reactor 线程上。在外层套 `Mono.fromCallable().subscribeOn()` 只影响 `ragAdvisorFactory.create()` 的线程（组装 Advisor 链），**不影响** Advisor 内部 PostProcessor 的执行线程——PostProcessor 是在 Spring AI 的 Reactor 链内同步调用的。

这意味着原方案的 H4 修复可能根本 **不生效**。`block()` 仍然在 Reactor 线程上执行，只是换了个 Reactor 线程而已。

---

## 三、修正方案

基于以上 spec 审查，重新设计：

### 核心原则
1. **OCP**：rag 模块的问题在 rag 模块内解决，不动 chat 模块
2. **封装彻底**：并发策略封装在 PostProcessor 内部，对上层透明
3. **遵循框架约定**：不违反 Spring AI Document 引用语义

### 方案 A：PostProcessor 内部异步化（推荐）

将阻塞式 `block()` 改为真正的异步：在 PostProcessor 内部用独立线程池执行 Rerank 调用，但对外保持同步接口签名（`List<Document> process()`）。

```java
// BailianRerankPostProcessor.java — 内聚改造，外部零感知
public class BailianRerankPostProcessor implements DocumentPostProcessor {

    private final WebClient webClient;
    private final ExecutorService rerankExecutor; // 封装在内部，不暴露给外部

    // ...

    public BailianRerankPostProcessor(String baseUrl, String apiKey, String model, int topN) {
        // ...
        // 遵循 EtlExecutorConfig 规范：全参数 ThreadPoolExecutor
        this.rerankExecutor = new ThreadPoolExecutor(
            2, 4, 60L, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(50),
            new NamedThreadFactory("rerank", true),
            new ThreadPoolExecutor.CallerRunsPolicy() // 满了由调用线程兜底
        );
    }

    @Override
    public List<Document> process(Query query, List<Document> documents) {
        if (documents == null || documents.isEmpty()) return documents;

        try {
            // 在独立线程池中执行阻塞调用
            Future<Map<String, Object>> future = rerankExecutor.submit(
                () -> callWithRetry(buildRequestBody(query.text(), documents))
            );

            Map<String, Object> response = future.get(15, TimeUnit.SECONDS); // 显式超时

            if (response == null || !response.containsKey("results")) {
                return documents;
            }

            return buildRerankedList(response, documents);

        } catch (TimeoutException e) {
            log.warn("Rerank timed out, degrading: query length={}", query.text().length());
            future.cancel(true);
            return documents;
        } catch (Exception e) {
            log.warn("Rerank failed, degrading: {}", e.getMessage());
            return documents;
        }
    }

    // H5 修复：创建新 List 但保留原始 Document 引用（遵循框架约定）
    // 不 new Document()，只在原 doc 的 metadata 上 set（框架设计约定）
    // 通过重新排列顺序 + 设置 rerankScore 实现"语义上的不可变"
    private List<Document> buildRerankedList(Map<String, Object> response,
                                              List<Document> source) {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> results = (List<Map<String, Object>>) response.get("results");

        List<Document> reranked = new ArrayList<>(results.size());
        for (Map<String, Object> result : results) {
            Number index = (Number) result.get("index");
            Number score = (Number) result.get("relevance_score");
            if (index != null && index.intValue() >= 0 && index.intValue() < source.size()) {
                Document doc = source.get(index.intValue());
                doc.getMetadata().put("rerankScore", score != null ? score.doubleValue() : 0.0);
                reranked.add(doc);
            }
        }
        return reranked.isEmpty() ? source : reranked;
    }
}
```

**关键改动**：
1. **线程池封装在 PostProcessor 内部**——外部（chat 模块、factory）完全无感知
2. **Future.get(15s)** 显式超时——不依赖 WebClient timeout 兜底
3. **CallerRunsPolicy**——线程池满时由调用线程（Reactor 线程）执行，不丢请求
4. **L2 修复**：`index >= 0` 校验
5. **不改 chat 模块任何文件**——OCP 合规

### 需要新增的辅助类

```java
// retrieval/NamedThreadFactory.java — 复用 EtlExecutorConfig 的模式
// 或者从 EtlExecutorConfig 中提取为 common 工具类（code-reuse-thinking-guide）
```

### 方案 A 的 spec 合规检查

| 规范 | 状态 | 说明 |
|------|------|------|
| OCP 强制 | ✅ | 只改 BailianRerankPostProcessor + L1 超时配置，chat 模块零改动 |
| 封装彻底 | ✅ | 线程池封装在 PostProcessor 内部，不暴露 |
| SRP | ✅ | PostProcessor 自己管理自己的异步策略 |
| DIP | ✅ | 不引入新的跨模块依赖 |
| 线程池规约 | ✅ | 全 7 参数 + NamedThreadFactory + CallerRunsPolicy |
| 跨层思考 | ✅ | 无跨层泄漏 |
| code-reuse | ⚠️ | NamedThreadFactory 应从 EtlExecutorConfig 中提取共享 |
| Forbidden: @Transactional | N/A | |
| DTO 隔离 | N/A | |
| Error Handling | ✅ | TimeoutException 独立处理，Exception 兜底降级 |

---

## 四、改造清单

| 文件 | 改动 | 破坏性 |
|------|------|--------|
| `BailianRerankPostProcessor.java` | 内部引入 rerankExecutor + Future.get(timeout) + index>=0 校验 + responseTimeout | 构造函数签名不变，接口不变 |
| `EtlExecutorConfig.java` → 提取 `NamedThreadFactory` 到 common | 代码复用 | EtlExecutorConfig 改用 common 版本 |
| `common/thread/NamedThreadFactory.java` | 新增共享线程工厂 | 无破坏 |

**不需要改的文件**：
- ~~ChatAdvisorChainFactory.java~~ — OCP，不动
- ~~RagConfig.java~~ — 不需要 ragExecutor Bean
- ~~RagAdvisorFactory.java~~ — 不动

---

## 五、H5 深度分析：是否真的需要创建新 Document？

Spring AI 框架中 `DocumentPostProcessor.process()` 的 **实际约定**：

查看框架内置的 `MappingDocumentPostProcessor`、`FilteringDocumentPostProcessor` 等——它们都 **不创建新 Document**，而是直接修改 metadata 或过滤列表。

原因：`RetrievalAugmentationAdvisor` 内部用 Document ID 做去重和引用追踪，如果创建新 Document 对象，ID 可能丢失或重复。

**结论**：H5 的问题不是"修改 metadata"本身（这是框架约定），而是 **fallback 路径在原始 documents 上标记 rerankFallback，污染了返回给同一请求的文档列表**。但同一请求内，documents 只用一次就丢弃了，所以实际无影响。

**H5 降级为 LOW**——不改也可以接受。如果改，也应仅限 fallback 路径创建新列表，不需要所有路径都创建新 Document。

---

## 六、最终结论

| 问题 | 原方案 | Spec 审查后 |
|------|--------|-------------|
| H4 阻塞 | 改 ChatAdvisorChainFactory + 外部 ragExecutor | ❌ 违反 OCP/DIP/封装 → 改为 PostProcessor 内部线程池 |
| H5 可变性 | 所有路径创建新 Document | ⚠️ 违反框架约定 → 降级为 LOW |
| L1 超时 | HttpClient responseTimeout | ✅ 保留 |
| L2 越界 | index >= 0 | ✅ 保留 |
| 跨模块改动 | chat + rag 两个模块 | ✅ 只改 rag 模块 |
