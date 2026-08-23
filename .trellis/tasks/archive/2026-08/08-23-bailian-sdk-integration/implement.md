# 实施计划

技术设计唯一事实源：`docs/design/bailian-sdk-integration.md`（v5）。
本文件仅记录执行顺序、验证命令与回滚点。

## P0 依赖与现状（先行验证）

1. pom.xml：`okhttp-jvm 5.4.0` → `okhttp 4.12.0`（`okhttp.version` 属性改 4.12.0，
   注释同步改写）；新增 `com.alibaba:dashscope-sdk-java:2.22.30`（零 exclusion）；
   `kotlin.version=2.1.21` 保留。
2. `mvn dependency:tree` 核对清单（设计 §4.1.3 #4）。
3. `mvn -q compile` + LLM 相关测试回归（GenericChatClient SSE / BailianEmbedding /
   BailianRerank）。
4. 无真实 Key 的离线实测项（设计 §4.1.3 #1/#2/#5）→ 下载 SDK 源码/反编译核验 facade
   路由与 retry 行为，结论写回设计文档「P0 结论」；真机冒烟标记为联调待办。

## P1 CHAT SDK 化

1. `BailianChatClient`（`client/bailian/`，implements ChatCapable + ToolCallingCapable）：
   - facade 路由（§4.2.4，策略 a 优先，源码核验后定）；
   - 参数映射表（§4.2.1）：messages/thinking(enableThinking+thinkingBudget)/
     responseFormat/resultFormat=message/incrementalOutput；
   - Flowable→Flux 薄桥（Flux.create，不引 reactor-adapter）；
   - 全新 DashScopeToolCallAccumulator（按 SDK delta 形状，勿复用 OpenAI index 语义旧类）；
   - reasoning 透传+累计；usage 含 cached_tokens；ApiException 错误映射；
   - history 工具消息双向转换（§4.2.2）。
2. `ChatCapabilityStrategy` extends `AbstractProviderFactoryAwareStrategy`；
   基类 Javadoc 删除「Chat 不使用此基类」句。
3. `BailianChatClientFactory`：providerId=bailian, capability=CHAT，
   域名守卫（dashscope.aliyuncs.com / *.maas.aliyuncs.com 或 params.sdk-client=true）。
4. 单测（Mockito 桩 SDK 入口）。

## P2 EMBEDDING SDK 化 + 域名配置化

1. `BailianEmbeddingClient` 同名全量重写为 SDK 实现（TextEmbedding.call），
   保留：batchSize 解析、ScopedTasks 并发分批（MAX_CONCURRENCY=4）、text_index 对位、
   零向量兜底、instruct+query 配套。
2. `BailianEmbeddingClientFactory` / `BailianRerankClientFactory`：域名来源改
   `baseUrl + endpoint` 参数（即 provider.url + endpoints.*）；stable profile 补
   `endpoints.embedding/rerank` 声明。
3. `BailianSpringAiEmbeddingAdapter` 构造参数泛化 `BailianEmbeddingClient` → `EmbeddingCapable`
   （LlmAutoConfiguration 同步）。
4. 单测重写（BailianEmbeddingClientTest 适配 SDK 桩）。

## P3 清理

1. 删 `ToolCallAccumulator` 中百炼专属分支（若有）。
2. `.trellis/spec/backend/llm-spi.md` 更新（SDK 客户端接入模式、工厂守卫）。
3. `mvn test` 全绿 + detect_changes + gitnexus impact 复核 + 分批提交。

## 验证命令

```bash
mvn -q compile
mvn -q test -Dtest='GenericChatClient*,Bailian*,LlmClientRegistry*'
mvn -q test          # P2/P3 验收
mvn dependency:tree -Dincludes='com.squareup.okhttp3,com.squareup.okio,org.jetbrains.kotlin,io.reactivex.rxjava2'
```

## 回滚点

- P0 独立 commit（pom 变更）可单独 revert。
- P1：删 `BailianChatClientFactory` 的 @Component 即回 GenericChatClient。
- P2：revert P2 commit（同名全量替换，无过渡类）。
