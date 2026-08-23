# P0 实测结论（2026-08-23，真机 + 源码核验）

SDK 版本：dashscope-sdk-java 2.22.30（源码 jar 反编译核验 + workspace 域名真机调用）。
Key：dev workspace Key（环境变量注入，未落盘）。

## 1. dev chat 现状形状实测（设计 §4.1.3 #1）——**确证为坏**

GenericChatClient 的 completions 形状（顶层 `messages`）打 dev 配置的
`/api/v1/services/aigc/multimodal-generation/generation` →
**HTTP 400** `{"code":"InvalidParameter","message":"<400> InternalError.Algo.InvalidParameter: Field required: input"}`。

与 rerank 注释实证同类（原生路由要求 `input.messages` 嵌套）。结论：dev bailian chat
现状不可用（dev 默认 chat 是 deepseek 故主链路未踩到）；SDK 化即修复。

## 2. SDK facade 路由实测（设计 §4.1.3 #2）——**策略 a 否决，采策略 b（按模型族分流）**

| 模型 | Generation（text-generation 路由） | MultiModalConversation（multimodal 路由） |
|---|---|---|
| qwen3.7-plus（dev） | ❌ 400 `url error`（workspace 域名与共享域名皆拒） | ✅ 阻塞+流式均通 |
| qwen3.8-max（dev） | ❌ 400 `url error` | ✅ 阻塞通 |
| qwen3-max（stable） | ✅（workspace 域名实测通） | ❌ 400 `url error` |
| qwen-plus-latest（stable） | 未实测（同族 qwen3-max，text 路由） | 预期 ❌ |

- SDK `Generation.Models.QWEN3_7_PLUS` 常量仅命名收录，**不代表 text 路由可用**——真机拒服务。
- workspace 域名两路由均可服务（qwen3-max 经 text 路由在此域名 OK）。
- **路由策略**：candidate params 声明 `route: text|multimodal`，默认按模型名规则推断
  （见 P1 实现），text 族走 `Generation`（Message 形状），multimodal 族走
  `MultiModalConversation`（MultiModalMessage 形状，content 为 `[{text:...}]` 数组）。

## 3. embedding 冒烟（设计 §4.1.3 #5）

| 模型 | 结果 |
|---|---|
| qwen3.7-text-embedding | ✅ dim=1536 |
| text-embedding-v4 | ✅ dim=1536 |
| Qwen/Qwen3-Embedding-8B（stable） | ❌ 404（DashScope 域名不服务 ModelScope 命名；与手写客户端现状行为一致） |

stable 候选处置：SDK 化后行为不变（同样 404）；切 `text-embedding-v4` 属配置变更，
列为后续运维任务（本任务不改 stable 候选声明）。

## 4. SDK retry 探测（设计 §4.1.3 #6）——无叠加风险

源码核验：retry 仅存在于 WebSocket 客户端（`OkHttpWebSocketClient` 握手/发送重试）；
HTTP half-duplex 路径（`OkHttpHttpClient`）**无内置重试**。重试语义唯一归 Resilient 层，
无需关闭任何 SDK 开关。

## 5. 流式 delta 形状（multimodal 路由，incrementalOutput=true，真机）

- **content**：`message.content` 为 `List<Map>`，形如 `[{text=增量片段}]`；reasoning_content
  为纯文本增量（先于 content 下发）。
- **tool_calls**：OpenAI index 语义分片——首片 `index=0, id=call_xxx, function.name=…,
  arguments=""`；后续片 `id=""/name=null/arguments=<json片段>`；**尾片
  `finish_reason=tool_calls` 且 toolCalls 为空片段**→ 轮末汇总包必须由 accumulator drain
  产出，不能取尾片字段。
- **usage**：每个 chunk 均携带（output_tokens 递增的累积值）；finish chunk 有完整 usage。
  轮末汇总直接取 finish chunk usage 即可。
- **SDK 内置累积**：仅当 `incrementalOutput=false/null` 时激活（SDK 会强制 wire 层 true +
  内部合并后发累积值）。显式 `incrementalOutput(true)` → 透传增量（与现 StreamChunk 增量
  语义一致）→ 我们自己的 accumulator 收口。

## 6. SDK 接入要点（源码核验）

- **baseUrl**：per-facade 构造器 `new Generation("http", baseUrl)` /
  `new MultiModalConversation("http", baseUrl)` / `new TextEmbedding(baseUrl)`，baseUrl
  须含 `/api/v1` 前缀（SDK 自动拼 `/services/...` 路径）。禁用 `Constants.baseHttpApiUrl`
  全局静态。
- **apiKey**：param 级 `.apiKey(...)`（HalfDuplexParamBase.apiKey），无需全局常量/env。
- **超时**：构造器第三参 `ConnectionOptions`（connect/write/read，默认 120/60/300s）——
  对齐现客户端超时需显式传入。
- **GenerationParam 覆盖**：messages/maxTokens/temperature/topP/tools/enableThinking/
  thinkingBudget/responseFormat/resultFormat("message")/incrementalOutput 均一等公民；
  `parameters` map 透传 extraParams。
- **MultiModalConversationParam 覆盖**：同上（messages 为 `List<Object>` 放
  MultiModalMessage；content 为 `List<Map<String,Object>>` 纯文本用法）。
- **错误**：`ApiException`（statusCode/code/message）+ `NoApiKeyException` → 映射
  RemoteException；4xx InvalidParameter/url error 均 400。

## 7. 依赖对齐结论（设计 §4.1.3 #3/#4）

- okhttp 系全树唯一 4.12.0、okio 唯一 3.6.0、kotlin-stdlib 唯一 2.1.21、RxJava2 仅来自
  SDK、gson 仅来自 SDK（与 jackson 无坐标交叉）、jackson 全树 Boot BOM 仲裁 2.21.2。
- **设计未预见的传递链**：`opendataloader-pdf-core:2.5.0` 传递 `okhttp-jvm:5.4.0`（与
  okhttp 4.12.0 duplicate-class）→ 已加 exclusion（pom 注释说明）。
- 回退后 HTTP 链路回归：GenericChatClient 阻塞+流式 / BailianEmbedding / BailianRerank /
  Registry 共 49 测试全绿（okhttp 4.12.0）。

## 8. 冒烟程序

`/tmp/dsqsmoke/SdkSmoke*.java`（一次性，未入库）；复跑需 dev workspace Key。

## 9. 实施期补充发现（P1/P2）

1. **DashScope 流式中间块携带非终止 finish_reason**（字面 `"null"` 字符串）：轮末汇总包若以
   「finish_reason 非空」为触发条件会过早收口（且 roundEndEmitted 守卫吞掉真正终止块）。
   修复：仅识别到终止枚举（stop/length/tool_calls/content_filter）才收口，未识别终止值由流完
   成兜底。已加回归单测（`BailianChatClientTest.nonTerminalFinishIgnored`）。
2. **victools jsonschema-generator 仲裁冲突**（设计 §4.1 未预见）：SDK 传递 4.31.1 经
   Maven nearest-wins 胜出，Spring AI 工具回调链路（按 4.38.x 编译，用 `AnnotationHelper`）
   运行时 NoClassDefFoundError（22 个 agent 工具测试 Error）。修复：dependencyManagement
   锁 4.38.0。全量 mvn test 1789 全绿。
3. **BailianChatClient 真机端到端验证**（四场景）：TEXT/MULTIMODAL × 阻塞/流式全通，流式轮末
   汇总包 finish=STOP + usage 完整，thinking budget 映射生效（qwen3.8-max reasoning 流）。
4. **BailianEmbeddingClient（SDK 版）真机验证**：qwen3.7-text-embedding（batch 20）与
   text-embedding-v4（batch 10）× 单条/45 条并发分批（对位正确）/QUERY instruct 全通。
5. **TextEmbeddingParam 字段无 getter**（非 @Data）：参数断言须经 `getParameters()` map。
   Mockito 注意：`thenReturn(resultOf(...))` 参数中嵌套 mock 会触发 UnfinishedStubbing，
   夹具需先构造再打桩。
