# 百炼 SDK 接入（dashscope-sdk-java 适配自研 LLM SPI）

## 需求来源

`docs/design/bailian-sdk-integration.md`（设计稿 v5，已评审立项）。本 PRD 仅摘要需求与验收；
技术设计以该文档为唯一事实源。

## 需求摘要

将 bailian provider 的客户端从手写 HTTP 迁移到阿里云百炼官方 Java SDK
（`com.alibaba:dashscope-sdk-java` 2.22.30）：

1. **CHAT**：`ChatCapabilityStrategy` 补齐 `AbstractProviderFactoryAwareStrategy` 工厂感知；
   新增 `BailianChatClientFactory`（含 BYOK 自定义 baseUrl 域名守卫，不命中回落
   `GenericChatClient`）+ `BailianChatClient`（DashScope 原生协议，SDK facade 按模型族路由）。
2. **EMBEDDING**：`BailianEmbeddingClient` 同名全量重写为 SDK 实现（无过渡共存类），
   并发批处理重建（batchSize 来自 candidate params、text_index 对位、零向量兜底）。
3. **RERANK 不迁移**：保留手写客户端，仅消除工厂域名硬编码。
4. **依赖**：okhttp-jvm 5.4.0 → okhttp 4.12.0 经典构件（与 SDK 传递依赖同版对齐）。

## 不变式（SPI 契约保真）

- 弹性装饰栈（Resilient*）、用量计量（UsageRecording*）、registry/BYOK 配置面零改动。
- 流式「轮末汇总包」契约：每工具轮最后一个 StreamChunk 携带完整合并 toolCalls +
  finishReason + usage + 累计 reasoningContent。
- usage 映射含 cacheHitTokens（百炼 `prompt_tokens_details.cached_tokens`）。
- 错误映射到既有 `RemoteException(RemoteErrorCode…)` 分类（4xx 限流/参数、5xx 远端故障）。

## 验收标准

- [ ] P0：编译通过；dependency:tree 中 okhttp 系唯一 4.12.0、okio 唯一 3.6.0、
      kotlin-stdlib 唯一 2.1.21；现有 HTTP 链路测试（chat SSE/embedding/rerank）全绿。
- [ ] P1：BailianChatClient 单测覆盖参数映射（messages/thinking/toolCalls/responseFormat/
      resultFormat=message）、流式轮末汇总包（toolCall 增量合并、reasoning 累计、流末 usage）、
      history 工具消息转换、错误分类；BYOK 守卫单测。
- [ ] P2：BailianEmbeddingClient SDK 实现单测覆盖批处理/text_index 对位/零向量/instruct 配套；
      工厂域名来源改 `provider.url + endpoints.*`；stable profile 补 embedding/rerank endpoint 声明。
- [ ] P3：`mvn test` 全绿；detect_changes 影响面在 llm 模块内；spec 更新。

## 范围外

非 bailian provider、registry/BYOK/计量架构、Spring AI 类型层、rerank 客户端替换。
