# LLM 模块无状态化与 BYOK 移除设计（协议无状态化；BYOK 砍除）

> 状态：**已实施**（2026-08-26，v3.2；实施提交：WS-A `d616313` / WS-B `9963f6c` / WS-C 验收测试随收尾提交）
> 实施记录（2026-08-26）：三工作流按 §6 顺序落地。两处机械偏差（不改设计约束，属签名层面）：① `ChatProtocol.chat/chatStream` 签名在文档基础上补第三入参 `ModelCandidate`（请求体构建需 model/params/思考配置，协议为全员共享单例，候选身份只能随请求传入——与"凭据/端点是方法入参"的无状态约束正交）；② `GenericChatClient` 薄壳构造器收 `(ResolvedEndpoint, ModelCandidate, ChatProtocol)` 而非文档所述 `(candidate, ResolvedEndpoint, HttpClientFactory)`——薄壳已无传输资源，HttpClientFactory 由协议持有。`application-dev.yml` 为 .gitignore 本地文件，其 byok 段清理仅生效于本地。AC4 端点 404 以路由零注册断言固化（`LlmLegacyRemovalTest`，扫描模式串动态构造避免测试自身命中）。
> 范围：`src/main/java/com/smart/rag/infrastructure/llm/`（协议抽取 + 共享传输 + registry 收窄 + BYOK 基建删除）+ `modelconfig`（模块整体删除）+ `chat` 调用点机械回退（v3.1 更正：前端不存在 llm-config 页面/调用，无前端改动项）
> 前置关系：本设计 WS-A 仍是 `docs/design/llm-resilience-optimization.md`（v1.2）WS1–WS3 的前置；resilience 方案中以 BYOK 落地为前提的条目（WS4 闸门"仅系统候选"、WS5 reset=modelCode 归一、WS7 candidateId 基数收敛）随 BYOK 移除自然成立或失去对象，其文档表述待下次修订同步，接口级结论不变
> **v3.0 定位（用户决策，2026-08-26）**：经交错链代价评估（S1 先于 B2 → 链语义从"用户成本优先"变"快速恢复优先"），结合产品定位与实现价值，**砍掉 BYOK 功能，仅使用系统配置模型**。上线前无存量用户：无迁移、无兼容承载、无保留开关——旧形态一律删除。v2.x 中为 BYOK 服务的全部工作流随功能移除撤销（用户确认）。

**修订记录**：
- v3.2（2026-08-26）：架构评审修订（评审发现逐项经代码核实；三项处置经用户确认）：
  - **免 key 豁免门卫收紧（用户决策：本轮即接入 `HostSafetyValidator`，消除子串匹配讨论）**：`ProviderConfig.isAvailable()` 的免 key 豁免判定由 `url.contains("localhost")/contains("127.0.0.1")` 子串匹配改为 `HostSafetyValidator.isLoopbackEndpoint(url)` host 字面回环判定（localhost / 127.0.0.0/8 字面 / `[::1]` 字面；纯字面解析不发 DNS；静态方法——`ProviderConfig` 为 yml 绑定 record 无法注入 bean，与 `HttpClientFactory.buildRestClient` static 同型）。子串反例（`x.localhost.evil.com`、path 含 localhost）不再豁免 → 候选跳过（fail-safe）。**`HostSafetyValidator.validate()` 全量 SSRF 校验仍不适用于 provider URL**：其威胁模型（用户输入面）与实现（localhost/回环/内网黑名单一律拒绝、端口白名单默认 80/443）会直接误杀回环 Ollama 与内网网关部署，与 keyless 场景冲突——validate() 仅留待未来输入面（§9 改述）；
  - **grep 清零模式修正**：`[Bb]yok` 不匹配全大写 "BYOK"、`llm_config` 不匹配连字符 "llm-config"——决策 11/WS-C/AC3 统一改为 `(?i)byok`（大小写不敏感）并补 `llm-config` 形态；WS-B 显式补入保留文件的 BYOK/llm-config 措辞清理（`HostSafetyValidator`/`SecurityCryptoProperties` Javadoc、`ChatServiceImpl` 两处注释、`ChatController` 的 /api/admin/llm-config 引用注释、`BailianChatClientFactoryTest` 措辞）；
  - **V31 数据风险处置（用户决策）**：实施前数据库整体清空重置（不备份、不留数据）——无存量数据风险，drop 无需预检/备份步骤（决策 8/§7 同步）；
  - **死代码清单补全**：`evictCircuitBreakerQuietly` **方法整体删除**（仅有的两处调用——`destroy` 的 BYOK 排空段与 `asyncClose`——均在删除面内，删后零调用者），连同 registry 的 `LlmCircuitBreakerAdapterRegistry` 构造依赖一并移除（resilience WS4 决策 19 将在系统级 refresh/destroy 路径重新挂接 evict，届时显式重新引入）；
  - **措辞校准**："黑盒行为零变化"精确为"协议层线序行为零变化"（传输实例收敛属资源层变更，§7 既有申报）；`ChatProtocol.id()` 保留理由注明（服务于 resilience §10 bailian 协议归并后续项）；§7 回滚"反向迁移"更正为前向迁移（Flyway 无自动反向迁移，实际为新增 V32 重建表）；resilience 文档头部补 v3.x 定位指针（本文档前置关系行的既有申报落实为双向可见）。
- v3.1（2026-08-26）：外部评审修订（评审发现逐项经代码核实；第 1 项为用户决策，其余为事实更正与清单补全）：
  - **apiKey 语义定稿（用户决策）**：支持服务器本地无 key 供应商（同机部署 Ollama；"本地"以应用服务器为视角——应用发出的回环请求只落在服务器自身，终端用户本地地址架构上不可达，且 BYOK 移除后 provider URL 仅来自 yml、无输入面，不存在 SSRF 防护对象）：`ResolvedEndpoint.apiKey` 维持 `@Nullable`（null/blank = 无鉴权端点），免 key 豁免门卫单一保留在 `ProviderConfig.isAvailable()`（免 key 仅限 url 含 localhost/127.0.0.1），构造期不对 apiKey requireNonNull；协议层非空时每请求显式 Authorization 头、缺省时**不发送该头**（阻塞/流式两路一致，现状流式为无条件拼接须改）。申报修复：keyless 本地候选现状经 isAvailable 放行后被 `GenericChatClient` requireNonNull NPE → `createRawClient` 捕获跳过（isAvailable Javadoc 意图与实现相矛盾的潜伏 bug；当前 yml 四家 provider 均显式配 key，未暴露），WS-A 后该形态候选可用（§7 行为变更表）。未来 provider 配置若出现输入面（如管理端动态下发），再接 `HostSafetyValidator` 做 SSRF 校验（后续项，本轮不做）；
  - **ResolvedEndpoint 形状收敛**：弃 endpoints Map（v2.x BYOK 遗留——DB 行携带全端点），定稿 `ResolvedEndpoint(String baseUrl, @Nullable String apiKey, String endpoint)`——系统单源下端点已由 strategy 层按 capability 解析为单值，协议层 `get("chat")` 属二次解析；
  - **WS-A 改动文件补全**：`ChatCapabilityStrategy`/`BailianChatClientFactory`（`GenericChatClient` 仅有的两个构造点，薄壳构造器签名变更必改，原清单遗漏）；
  - **前端范围断言删除**：frontend/src 不存在任何 llm-config 页面/调用（api 目录仅 models.ts 等系统模型接口，大小写不敏感全量 grep 为空）——范围行、WS-B 前端步骤与 §7 对应行删除；AC4 后端 404 断言不变；
  - **AC2/测试措辞更正**：`ChatServiceImplTest`/`ChatServiceImplModelSelectionTest`/`ChatServiceImplResolveCandidateIdTest` 的系统用例同样 mock `getUserChain`/`getUserDefault`（生产代码仅有的两个调用点），须机械迁移为 `getChain`/`getDefault`——"零适配"仅适用 agent/intent/rewrite；
  - **清单补全/杂项**：modelconfig 测试包实有 7 文件（补列 `AdminLlmConfigControllerTest`/`UserLlmConfigControllerTest`/`LlmUserLifecycleListenerTest`/`LlmModelConfigServiceImplTest`）；AC3/决策 11 grep 范围补 `.env.example`；WS-B 显式列入 application.yml security 段两处 BYOK 措辞注释与 bailian provider 过时注释（"守卫不命中"实为命中）更正；决策 3 补共享 RestClient 保留默认 Content-Type 头。
- v3.0（2026-08-26）：用户决策——**砍掉 BYOK，仅系统模型**。文档按新定位重构（用户确认两项范围决策：WS-A 保留于本设计；抽象层全部撤销）：
  - 保留 WS-A（协议抽取 + 共享阻塞传输，v2.2/v2.3 决策原样沿用——动机独立于 BYOK：连接池收敛、resilience WS1–WS3 基底、mockwebserver 基建）；
  - 撤销 v2.x 的 WS-B/C/D/E 全部 BYOK 相关设计：描述子目录、两键身份、`ByokModelCatalog`/`ByokChatCall`、`ChatChainResolver` 混合链（含 v2.3 后评估的交错链方案——随移除 moot）、`ChatModelAssembler` 装配统一（删 String 重载）、`ChatTarget`/`FallbackTarget` 分层、`ModelService` userId 维度、配置变更失效事件接线——动机随功能移除消失，不留 speculative 抽象；
  - 新增 WS-B：BYOK 全量删除（modelconfig 模块整体 + infrastructure.llm 的 BYOK 基建 + registry 用户快照机制 + yml 开关 + Flyway 表删除 + 前端接口下线）；
  - v2.x 决策归宿：B2–B6 为 BYOK 债务，随删除连根消解（不修复，见 §0）；v2.2"保留 `recordByokFallback`/`userCacheSize`"更正失效（消费方 `DbByokConfigSource` 一并删除）；v2.1"端点缺省 `/chat/completions`"撤销（BYOK 空白 endpoints 场景不存在，维持 fail-fast）。
- v2.3（2026-08-26）：用户决策——**无上线过渡/放量观察阶段，全量直接切换；上线前无存量用户，不保留任何仅为承载旧形态存在的机制**：
  - 删除全部上线观察类表述与相关指标盯防（§7 风险缓解列、决策 13"接受并观测"→"接受，作为申报的静态权衡"）——行为变更的保障 = 回归测试 + revert，不靠线上观察；
  - **删除 WS-E 存量数据订正项及 §7 对应行**：无存量用户即无存量数据问题——不写订正脚本、不做运行时容错，旧形态 candidateId（`u:` 前缀）随代码删除而消亡；
  - 重申既有立场（与本决策一致）：无兼容开关、无并行旧路径、无 if 特殊分支（决策 8 单一装配路径、AC5 grep 清零）。
- v2.2（2026-08-26）：外部评审修订（现状断言全部经代码核实；三项方向性决策经用户确认，均取推荐项）：
  - **补 WS-A 阻塞路径传输机制**（消解决策 2"HTTP 全经共享 OkHttp"与 WS-A"阻塞 RestClient 搬迁"的自相矛盾）：协议内共享 RestClient（`HttpClientFactory.sharedRestClient` 按超时签名缓存，不绑 baseUrl/凭据，每请求绝对 URL + 显式 Authorization 头——与流式共享 OkHttp 同型）；决策 6"零变化"改述为"黑盒行为零变化，传输实例收敛共享"；resilience WS3 统一 OkHttp 计划不变（用户决策）；
  - **BYOK 429 语义定稿**：接受 429 重试（与系统候选一致，实现零分叉）；失败模型更正为"401/403 认证失败不可重试直接降级，429 限流/配额按 LLM_RATE_LIMITED 退避重试后降级"（决策 7/WS-C 测试/AC9 同步修订，用户决策）；
  - **混合链最坏延迟接受**：BYOK 链前置 + retry-only 的最坏首包延迟（候选数 × maxAttempts × 超时+退避）在决策 13/§7 申报为静态权衡，限制链长/轻量重试列为后备（用户决策）；
  - **WS-E 死代码清单更正**：`recordByokFallback`（DbByokConfigSource all_disabled 分支仍消费）与 `LlmByokProperties.userCacheSize`（ByokModelCatalog 缓存大小来源）**保留**，删除清单仅剩 `recordByokCloseError`——v2.1 误列；
  - **WS-B 增 modelCode 契约断言**：目录构建期断言 `candidate.id == candidate.model`（当前 yml 全部成立；别名型候选待后续显式设计，fail-fast 防口径静默分裂）；
  - **决策 12 补多实例限制**：失效事件为进程内语义，多实例下其他实例依赖 TTL（≤1h）——单实例假设与既有用户删除事件同型，分布式失效列为后续项；
  - **§2 事实更正**：dev profile `LLM_BYOK_ENABLED` 默认 true，B4 断链在 dev 已实际暴露（非纯潜伏）；
  - 措辞收敛：决策 1"构造绑定零映射"→"同名字段直拷"；AC5 验收目标注明聚焦前端契约/归因维度（内部 `byok:{userId}:{providerCode}` 复合键保留）。
- v2.1（2026-08-25）：设计评审修订（用户确认：前四项决策按各问题推荐默认值处理，M4 = BYOK 优先贯穿列表）：
  - **链语义定稿（消解 v2.0 自相矛盾）**：链 = [显式指定] + [BYOK 选择] + [系统链]，按 modelCode 去重（决策 13 重写）；相对现状"BYOK 非空即无系统兜底"为行为变更，§7 申报；
  - **修正 §2 事实错误**：bailian chat 候选经 `BailianChatClientFactory` 守卫（`*.maas.aliyuncs.com` 域命中）实走 DashScope SDK（`BailianChatClient`）而非 GenericChatClient——yml"守卫不命中"注释已过时。bailian-sdk 协议归并降级为后续优化项（建议记入 resilience 方案 §10），SDK 路径与动态守卫本轮不动；BYOK 恒走 openai-compatible（行为变更申报，决策 2 重写）；
  - **描述子绑定修正**：`ProviderDescriptor` 字段名对齐 yml 键（`url`/`apiKey`/`endpoints`），id 由 providers map 键注入、`protocol` 缺省 `openai-compatible`（消解与"yml 键名不变、零迁移"承诺的矛盾，决策 1 重写）；
  - **用量归因**：usage 记账与 metrics 增 SYSTEM/BYOK 来源维度（决策 4 补充）——candidateId 归一后同名 modelCode 下运营方付费与用户自付仍需可区分；
  - **调用方清单补齐**：WS-D 增 `StrategyExecutionContext`/`ChatRequestSpecFactory`/`AbstractModeStrategy`（修复"漏网调用方编译期即暴露"保证的盲区——registry String 查询的其他消费者）；IntentClassifier 目标定为固定系统候选（仅装配入口迁移，零行为变更）；
  - 低危修正：端点缺省 `/chat/completions` 更正为**新增行为**（非"EndpointConfig 既有语义"）；`ByokModelSelection` 补 toString 脱敏；失效事件补 `fallbackExecution` 实现提示；AC5 grep 锚定 `"u:` 字面量；WS-E 补死代码清理（`LlmByokProperties.userCacheSize`、`LlmMetrics.recordByokCloseError`/`recordByokFallback`）；"destroy 系统级挂接保留"措辞更正（该能力系 resilience WS4 决策 19 后续引入）。
- v2.0（2026-08-25）：用户决策——放开破坏性变更约束，对齐 pi 的多 Provider + per-user BYOK 架构。相对 v1.0 的架构升级与评审收敛：
  - Provider 纯数据描述子 + 无状态协议适配器分离，**系统候选与 BYOK 共用同一协议代码**（v1.0 仅 BYOK 走共享适配器）；
  - 模型**两键身份** `(providerId, modelCode)`（pi `getModel(provider, id)` 同型）——同名模型撞名问题连根消解（收敛 v1.0 评审【中4】）；
  - 凭据**不再进入 ChatRequest**（v1.0 的请求内 `binding` 字段取消）：端点在链装配期解析为 `ResolvedEndpoint` 闭包进能力句柄，语义等价 pi `applyAuth` 且 smuggle 面更小；
  - BYOK retry 落点明确：`ByokChatCall` 内共享 `RetryPolicy`（收敛评审【高1】）；
  - **单一装配路径**：删除 String-candidateId 重载，agent/intent/rewrite 全部迁移（收敛评审【高2】agent 链路 B4 残留）；
  - `ByokModelSelection` 补全 `endpoints` 与流式/思考标志（收敛【中3】）；BYOK 不再套用系统 disabledSet（收敛【中5】，显式决策 D11）；配置变更 → 缓存失效事件接线（收敛【中6】）；`FallbackTarget`/`ChatTarget` 分层落 infrastructure.llm（收敛【中7】【低9】）；
  - v1.0 的 WS-A~D 被本版 WS-A~E 取代。
- v1.0（2026-08-25）：从 `llm-resilience-optimization.md` v1.2 的 WS0 前置工作流独立成文；发现 B4 断链。已被 v2.0 取代。

## 0. 背景与决策

v2.x 的问题清单（B1–B6）在"BYOK 砍除"决策下的归宿：

| # | 原问题（v2.x 诊断） | v3.0 归宿 |
|---|------|------|
| B1 | 凭据固化进客户端：`GenericChatClient` 构造器持 `apiKey`/`baseUrl`（requireNonNull），apiKey 烧进 RestClient 默认头 | **本设计 WS-A 修复**：协议无状态化，凭据/端点为方法入参 |
| B2 | per-user 对象图生命周期债务（Caffeine 缓存完整客户端对象图 + 异步 close 专用池 + 熔断 evict） | **随 BYOK 删除连根消解**（WS-B 删 userSnapshots/asyncClose/evict 全套） |
| B3 | `u:{userId}:{modelCode}` 命名空间泄漏（candidateId 携 userId 贯穿熔断 key/usage/前端帧） | **随 BYOK 删除连根消解**（命名空间整体不复存在） |
| B4 | BYOK 链与装配器双重解析断链（chat/agent 双路径 `LLM_CONFIG_ERROR` 可降级烧穿全链）；dev profile 默认启用下实际存在 | **随 BYOK 删除连根消解**（无 BYOK 链路即无断链；无需钉死测试） |
| B5 | BYOK 弹性语义错位（per-(user,model) 熔断在单用户低频流量下统计稀释） | **随 BYOK 删除连根消解** |
| B6 | registry 职责过载（系统快照 + 用户快照两套生命周期纠缠） | **随 BYOK 删除连根消解**（registry 收窄为纯系统级） |

砍除理由（产品层）：per-user BYOK 的实现价值（链语义/计费归属/凭据治理）不敌其复杂度成本——v2.x 设计的全部增量复杂度（两键身份、多源链组装、遮蔽语义、凭据缓存与失效、retry-only 语义）均由该功能衍生；产品当前阶段只需系统配置模型。

## 1. 决策记录

> 1. **BYOK 功能整体移除**（用户决策，2026-08-26）——不留开关、不留分支、不留表：代码、SPI、配置、Flyway 表、REST 接口、测试、前端页面全删（v3.1 核实：前端不存在 llm-config 页面/调用，该项为空操作）。无迁移、无兼容承载。
> 2. **协议无状态化（WS-A，pi 对齐仅保留此层）**——`ChatProtocol`（`id() / chat(ChatRequest, ResolvedEndpoint) / chatStream(ChatRequest, ResolvedEndpoint)`）为 Spring 单例，无字段状态，**凭据与端点是方法入参**；`id()` 为 resilience §10 bailian 协议归并（已申报后续项）预留的协议标识，非 speculative 保留。`openai-compatible` 协议自 `GenericChatClient` 原样抽出，`GenericChatClient` 转端点绑定薄壳委托协议。系统弹性栈（retry + 熔断 + 探测）与候选对象图零变化（**协议层线序行为零变化**断言——传输实例收敛属资源层变更，§7 申报；keyless 本地候选"跳过→可用"为决策 4 申报的修复，当前 yml 无该形态）。
> 3. **共享阻塞传输（v2.2 决策原样沿用）**——`HttpClientFactory.sharedRestClient(connectTimeout, readTimeout)` 按超时签名缓存（与 `sharedOkHttpClient` 同型），实例不绑 baseUrl、不设默认 Authorization（保留默认 `Content-Type: application/json` 头，与现状 buildRestClient 一致——黑盒零变化的一部分），每请求绝对 URL + 显式 `Authorization: Bearer` 头（apiKey 缺省时省略该头，决策 4）；`@PreDestroy` 随 `closeAll` 统一关闭。**禁止**每请求 `buildRestClient`（无连接复用 + 句柄无人管理）与按 baseUrl 缓存（重新引入生命周期债务）。既有 static `buildRestClient` 保留，供本轮不动的 Bailian/Embedding/Rerank 客户端继续使用（决策 10 的范围边界，非兼容承载）。
> 4. **端点解析与凭据语义：启动期静态，无 Resolver 抽象**——`ResolvedEndpoint(String baseUrl, @Nullable String apiKey, String endpoint)`（v3.1 形状定稿：单 endpoint 字符串，紧凑构造器 requireNonNull baseUrl/endpoint，toString 脱敏 apiKey）在 `GenericChatClient` 两个构造点（ChatCapabilityStrategy/BailianChatClientFactory）由 strategy 已解析的 (baseUrl, apiKey, endpoint) 一次性构造并闭包进薄壳；v2.x 的 `EndpointResolver`/BYOK 源/Caffeine 凭据缓存随 BYOK 撤销。端点缺省 `/chat/completions` **不引入**（v2.1 的引入动机是 BYOK 空白 endpoints 行；系统 provider 均显式配置，维持 `EndpointConfig.get` 未配置返回 null → 构造期 requireNonNull 失败 → 候选跳过的既有 fail-fast 语义）。**apiKey 可缺省（用户决策，v3.1；豁免门卫 v3.2 收紧）**：null/blank = 无鉴权端点（服务器本地 Ollama 同机部署场景）；免 key 豁免门卫单一保留在 `ProviderConfig.isAvailable()`，豁免判定接入 `HostSafetyValidator.isLoopbackEndpoint(url)`——host 字面回环判定（localhost（忽略大小写）/ 127.0.0.0/8 IPv4 字面 / `[::1]` 字面；纯字面解析不发 DNS；静态方法，`ProviderConfig` 为 yml 绑定 record 无法注入 bean，与 `HttpClientFactory.buildRestClient` static 同型），取代 v3.1 的 contains 子串匹配（`x.localhost.evil.com`/path 含 localhost 反例不再豁免 → 候选跳过，fail-safe）。`HostSafetyValidator.validate()` 全量 SSRF 校验**不适用于** provider URL（其 localhost/回环/内网一律拒绝 + 端口白名单 80/443 的实现会误杀回环 Ollama 与内网网关部署，与 keyless 场景直接冲突），仅留待未来输入面（§9）。构造期不对 apiKey requireNonNull；协议层非空时每请求显式 Authorization 头、缺省时不发送（修复申报：现状该形态候选经 isAvailable 放行后被 GenericChatClient requireNonNull NPE 跳过，属潜伏矛盾，见 §7）。
> 5. **registry 收窄为纯系统级**——删除用户快照全套：`userSnapshots`/`buildUserSnapshot`/`getUserChain`/`getUserChainInternal`/`getUserDefault`/`invalidateUser`/`asyncCloseExecutor`/`asyncClose`/`stripUserPrefix`/`supportsByok`，以及 `LlmClientFactory.ResolvedCandidate` + `buildSnapshot(List)`；`evictCircuitBreakerQuietly` **方法整体删除**（仅有的两处调用——`destroy` 的 BYOK 排空段与 `asyncClose`——均在删除面内，删后零调用者，按决策 7 连坐原则连同 registry 的 `LlmCircuitBreakerAdapterRegistry` 构造依赖一并移除；resilience WS4 决策 19 将在系统级 refresh/destroy 路径重新挂接 evict，届时显式重新引入）。系统级 API（`get`/`find`/`getDefault`/`getChain`/`getDeepThinking`/`refresh`/`disable`/`enable`/`destroy`）零改动（构造器签名随依赖移除收窄，非 API 面）。
> 6. **调用方机械回退，装配入口保持现状**——`ChatServiceImpl.buildChain`：`getUserChain(CHAT, userId)` → `getChain(CHAT)`；`resolveCandidateId`：`getUserDefault(CHAT, userId)` → `getDefault(CHAT)`（显式指定置链首与能力校验逻辑不动）。`AgentModeStrategy`/`IntentClassifier`/`RewriteClientResolver`/`ChatRequestSpecFactory`/`StrategyExecutionContext` 零改动（本就仅消费系统级 registry）。`ChatModelAssembler.chatModel(userId, String candidateId, ...)` 保持现状——v2.x 决策 8"删 String 重载"随 BYOK 撤销。
> 7. **共享基建保留，BYOK 专属删除，死代码连坐清除**——保留：`HostSafetyValidator`/`SecuritySsrProperties`/`DnsResolver`（MCP 的 `McpEndpointSafetyGuard`/`McpServerAdminService` 消费）、`SecurityCryptoProperties`/`SecretCipher`/`SECURITY_CRYPTO_MASTER_KEY`（`McpBearerTokenCodec` 消费）。删除：`ApiKeyCipher`（消费方全部在删除面内）、`LlmCryptoCanaryRunner`、`LlmMetrics.recordByokCloseError`/`recordByokFallback`（消费方 registry/DbByokConfigSource 均删除）、`UserDeletedEvent` 及 `SysUserServiceImpl` 的发布点（唯一消费者为 BYOK listener，删除后为无消费者的发布即死代码；未来需要用户删除广播时从 git 历史显式重新引入）。
> 8. **Flyway 表删除以删除迁移表达**——新增 `V31__drop_llm_config.sql`（`DROP TABLE IF EXISTS llm_config;`，PG 下连带索引自动删除）。Flyway 追加式历史不可改写既有 `V16__llm_config.sql`（删除该文件会断裂所有已应用库的 checksum），删除意图以新迁移表达——这是删除本身，不是兼容承载；无数据迁移。数据风险处置（用户决策，v3.2）：**实施前数据库整体清空重置（不备份、不留数据）**，无存量数据风险，drop 无需预检/备份步骤。
> 9. **Bailian 守卫保留**——`BailianChatClientFactory.sdkEngaged`（官方域命中或 `params.sdk-client: true`）逻辑零改动：yml 配置自定义网关 URL 的回落行为仍有效；仅更新 Javadoc 中 BYOK 措辞。
> 10. **Embedding/Rerank/Bailian SDK 本轮不动**（v2.x 决策 17 沿续）——其传输统一与超时参数化由 resilience WS2/WS3 承接。
> 11. **grep 清零验收**——`"u:` 字面量、`(?i)byok`（大小写不敏感，覆盖 Byok/byok/BYOK 三形态；main/test/yml + `.env.example`，`db/migration` 天然不在范围）、`llm_config` 与 `llm-config`（main 代码与资源；`db/migration` 历史文件除外）全部为零。保留文件中的 BYOK/llm-config 措辞注释随 WS-B 清单显式清理（WS-B 第 8 点），非仅靠 grep 兜底。
> 12. **与 resilience 方案的接口级结论**——本设计 WS-A 仍先于 WS1–WS3（其改造对象由 `GenericChatClient` 更名为 `openai-compatible` 协议实现，同一份代码搬迁；mockwebserver 依赖随 WS-A 引入）；WS4/WS5/WS7 的 BYOK 前提条目随移除自然成立或失去对象，resilience 文档表述待其下次修订同步。

## 2. 现状关键事实（实施前提，2026-08-26 代码核实）

- **BYOK 足迹 = 全量删除面**：
  - modelconfig 模块整体（11 文件 + mapper XML）：`AdminLlmConfigController`（/api/admin/llm-config）、`UserLlmConfigController`（/api/user/llm-config）、`LlmConfigVO`/`UpsertLlmConfigRequest`、`LlmModelConfig`（`@TableName("llm_config")`）、`LlmModelConfigMapper`（+ `resources/mapper/LlmModelConfigMapper.xml`）、`LlmModelConfigService(Impl)`、`DbByokConfigSource`、`LlmUserLifecycleListener`、`LlmCryptoCanaryRunner`；
  - infrastructure.llm：`config/ByokConfigSource`（SPI）、`config/LlmByokProperties`、`crypto/ApiKeyCipher`、`LlmMetrics.recordByokCloseError`/`recordByokFallback`、`LlmClientRegistry` 用户快照全套（决策 5 清单）、`LlmClientFactory.ResolvedCandidate` + `buildSnapshot(List)`；
  - chat：`ChatServiceImpl` 的两处调用（`buildChain` 的 `getUserChain`、`resolveCandidateId` 的 `getUserDefault`）；
  - user：`UserDeletedEvent` 定义 + `SysUserServiceImpl` 发布点（决策 7 连坐删除）；
  - yml：base 与 dev 的 `app.llm.byok` 段（dev 默认 `${LLM_BYOK_ENABLED:true}`）；env `LLM_BYOK_ENABLED` 引用清零（`SECURITY_CRYPTO_MASTER_KEY` 保留，MCP 消费）；
  - Flyway：`V16__llm_config.sql` 建表（历史文件保留不动）→ 新增 drop 迁移（决策 8）；
  - 测试：modelconfig 测试包全部 7 文件（`DbByokConfigSourceTest`/`ApiKeyCipherTest`/`LlmCryptoCanaryRunnerTest`/`AdminLlmConfigControllerTest`/`UserLlmConfigControllerTest`/`LlmUserLifecycleListenerTest`/`LlmModelConfigServiceImplTest`）删除；`LlmClientRegistryTest` BYOK 段删除，`ChatServiceImplTest`/`ChatServiceImplModelSelectionTest`/`ChatServiceImplResolveCandidateIdTest` BYOK 用例删除 + 系统用例 mock 迁移（getUserChain/getUserDefault → getChain/getDefault，生产代码仅有的两个调用点）；`BailianChatClientFactoryTest` 守卫回归保留。
- **共享基建边界（不删）**：`HostSafetyValidator`/`SecuritySsrProperties`/`DnsResolver`（MCP 消费）；`SecurityCryptoProperties`/`SecretCipher`/`SECURITY_CRYPTO_MASTER_KEY`（`McpBearerTokenCodec` 消费）。
- **系统链路现状**：bailian chat 候选经 `BailianChatClientFactory` 守卫命中 `*.maas.aliyuncs.com` 域实走 DashScope SDK（`BailianChatClient`）；deepseek/zhipu/minimax 走 `GenericChatClient`（OpenAI 兼容）。`FallbackExecutor` 全库仅 `ChatServiceImpl` 消费。`GenericChatClient` 阻塞路径 per-candidate `HttpClientFactory.buildRestClient`（每候选独立 JDK HttpClient + RestClient，Authorization 固化默认头）。
- **B4 现状**：dev profile 默认启用 BYOK 时，有 enabled 行的用户 chat/agent 双路径断链（`LLM_CONFIG_ERROR` 可降级烧穿）——随 WS-B 删除自然消解，无需钉死测试。
- **keyless 本地供应商现状（v3.1 核实）**：`ProviderConfig.isAvailable()` 对 url 含 localhost/127.0.0.1 的供应商放行空 apiKey（Javadoc 注明 Ollama 意图），但 `GenericChatClient` 构造器 `requireNonNull(apiKey)` 随即 NPE → `createRawClient` 捕获跳过——意图与实现矛盾，keyless 本地候选实际不可用；流式路径 Authorization 头为无条件拼接。当前 yml 四家 provider 均显式配 key，未暴露。豁免判定为 contains 子串匹配（`x.localhost.evil.com`、path 含 localhost 等反例可命中）——v3.2 决策 4 收紧为 `HostSafetyValidator.isLoopbackEndpoint` host 字面回环判定。

## 3. 目标架构（改造前后）

```
【现状】
  ChatServiceImpl ──getUserChain/getUserDefault──> LlmClientRegistry
       （双职责：系统快照 AtomicReference + per-user BYOK 快照 Caffeine + 异步 close 池 + 熔断 evict）
  modelconfig 模块（用户/管理端 llm-config CRUD + DbByokConfigSource SPI + ApiKeyCipher 加解密 + canary）
  GenericChatClient（apiKey/baseUrl 固化构造器；阻塞 per-candidate RestClient + 流式共享 OkHttp）

【目标】
  ChatServiceImpl ──getChain/getDefault──> LlmClientRegistry（纯系统级：系统客户端 + 链 + 禁用集）
  modelconfig 模块消失；llm_config 表删除；UserDeletedEvent 死发布清除
  协议层：ChatProtocol: openai-compatible（无状态单例，凭据=入参；阻塞=共享 RestClient、流式=共享 OkHttp）
  执行层：GenericChatClient 薄壳（启动期静态 ResolvedEndpoint）+ Resilient（retry+熔断+探测）不变
```

要点：**LLM 客户端与用户维度彻底解耦**（用户维度不复存在）；**凭据是端点数据不是对象身份**；Bailian SDK / Embedding / Rerank 客户端原样（决策 10）。

## 4. 工作流详设

### WS-A 协议抽取与端点数据（决策 2/3/4）

**改动文件**：`llm/ResolvedEndpoint.java`（新 record）、`llm/client/protocol/ChatProtocol.java`（新接口）、`llm/client/protocol/OpenAiCompatibleChatProtocol.java`（新，自 `GenericChatClient` 抽出）、`GenericChatClient`（转薄壳）、`ChatCapabilityStrategy`/`BailianChatClientFactory`（`GenericChatClient` 仅有的两个构造点，改构造 `ResolvedEndpoint`——v3.1 补列）、`HttpClientFactory`（新增 `sharedRestClient`）、`ProviderConfig`（isAvailable 免 key 豁免收紧）+ `infrastructure/security/HostSafetyValidator`（新增静态 `isLoopbackEndpoint`，v3.2）、pom.xml（test 依赖 `com.squareup.okhttp3:mockwebserver:4.12.0`）

1. 新增 `record ResolvedEndpoint(String baseUrl, @Nullable String apiKey, String endpoint)`（v3.1 收敛：单 endpoint 字符串，弃 v2.x BYOK 遗留的 endpoints Map——端点已由 strategy 层按 capability 解析为单值，协议层无需二次 get("chat")）——紧凑构造器 requireNonNull baseUrl/endpoint（端点未配置 → 构造失败 → 候选跳过，现状 fail-fast 语义不变），**显式覆写 toString 脱敏 apiKey**（AC6）；不可变纯数据；在 `GenericChatClient` 两个构造点由 strategy 已解析三元组一次性构造（启动期一次，决策 4）。
2. 新增 `ChatProtocol` 接口（决策 2）；`OpenAiCompatibleChatProtocol` = `GenericChatClient` 现有 `buildRequestBody`/SSE 读取/响应解析逻辑**原样搬迁**（阻塞 RestClient 与流式 OkHttp 两路均搬；resilience WS3 再统一 OkHttp）——协议实现不持有任何凭据/端点字段，全部来自方法入参。凭据头语义（决策 4）：apiKey 非空 → 每请求显式 `Authorization: Bearer {apiKey}`；null/blank → **不发送该头**（两路一致；现状流式为无条件拼接，搬迁时修正）。
3. 共享阻塞传输（v2.2 决策原样，见决策 3）：`sharedRestClient` 按超时签名 `computeIfAbsent` 缓存、不绑 baseUrl/凭据（保留默认 `Content-Type: application/json` 头）、每请求绝对 URL（`baseUrl + endpoint` 拼接沿用现 `buildUrl` 逻辑）+ 显式 Authorization 头（apiKey 缺省时省略）；既有 static `buildRestClient` 保留（Bailian/Embedding/Rerank 消费）。
4. `GenericChatClient` 转薄壳：构造器收（candidate, 静态 `ResolvedEndpoint`, HttpClientFactory），`chat/chatStream` 委托共享协议；不再持有 per-candidate `HttpHandles`（共享传输由 HttpClientFactory 统一管理）；Resilient 装饰链与系统对象图**零变化**（协议层线序行为层面；keyless 本地候选"跳过→可用"为 §7 申报修复，当前 yml 无该形态）。
5. 免 key 豁免门卫收紧（决策 4，v3.2）：`HostSafetyValidator` 新增静态 `isLoopbackEndpoint(String url)`——URI host 字面回环判定：localhost（忽略大小写）/ 127.0.0.0/8 IPv4 字面 / `[::1]` 字面（注意 `URI.getHost()` 对 IPv6 返回带方括号形式）；解析失败 → false（fail-safe）；**不发 DNS**（yml 单源下运维直接写回环字面量即可，自定义 hosts 别名不在豁免面，避免启动期网络依赖与解析/连接 TOCTOU 无意义化）。`ProviderConfig.isAvailable()` 免 key 分支改调该判定，取代 contains 子串匹配。行为变更：子串反例 URL（如 `https://x.localhost.evil.com`、path 含 "localhost"）由"可被豁免放行"变为"不豁免 → 候选跳过"；当前 yml 四家 provider 均配 key 且无回环 URL，无实际触发面。`HostSafetyValidator` 既有实例方法/Javadoc 不动（其 validate() 语义与 MCP 消费方不变）。
6. Bailian/Embedding/Rerank 客户端不涉及（决策 10）。

**测试**：MockWebServer 断言协议层目标 URL + 端点路径解析 + Authorization 头（带 key 用例断言 Bearer 值；keyless 用例断言请求**不含**该头，阻塞/流式两路）；阻塞/流式两路均经共享传输（同超时签名同实例断言，无 per-candidate HttpClient 构造）；`isLoopbackEndpoint` 边界用例（localhost/127.0.0.1/127.x 字面/`[::1]` 放行；`x.localhost.evil.com`、path 含 localhost、解析失败拒绝；`ProviderConfig.isAvailable` 免 key 分支联动断言）；系统路径经薄壳后协议层线序行为零变化断言（keyless 本地候选由"跳过"变"可用"、豁免门卫收紧均为申报变更，见 §7）；构造契约回归。

### WS-B BYOK 全量删除（决策 1/5/6/7/8/9）

**改动文件**：§2 足迹清单全部 + `ChatServiceImpl` + `SysUserServiceImpl` + application.yml / application-dev.yml（含 security 段 BYOK 措辞注释与 bailian provider 过时注释更正）+ `.env.example` + Flyway 新迁移 + 保留文件措辞清理（`HostSafetyValidator`/`SecurityCryptoProperties` Javadoc BYOK 措辞、`ChatServiceImpl` 两处 BYOK 注释、`ChatController` 的 /api/admin/llm-config 引用注释、`BailianChatClientFactoryTest` 措辞，v3.2）（v3.1 更正：前端无 llm-config 代码，无前端改动项）

1. modelconfig 模块整体删除（含 `resources/mapper/LlmModelConfigMapper.xml`，模块包移除）。
2. infrastructure.llm BYOK 基建删除（决策 5/7 清单）；`LlmClientRegistry` 收窄为纯系统级，destroy 排空只剩系统客户端 close；`LlmClientFactory` 删 BYOK 重载。
3. `ChatServiceImpl` 机械回退（决策 6）：`getUserChain` → `getChain`、`getUserDefault` → `getDefault`；显式指定置链首、能力校验（防直传 embedding/rerank candidateId）逻辑不动。
4. `UserDeletedEvent` + `SysUserServiceImpl` 发布点删除（决策 7）。
5. yml `app.llm.byok` 段删除（base + dev）；security 段两处 BYOK 措辞注释清理（"通用安全原语（LLM BYOK + MCP admin 共用）"、"缺失时 BYOK enabled=true 会 fail-fast…"，AC3 grep 兜底但显式列入）；bailian provider 段过时注释更正（守卫实际命中 `*.maas.aliyuncs.com` 走 SDK，现注释"守卫不命中，全走 GenericChatClient"为 v2.1 已确认的过时表述）；`.env.example` 的 `LLM_BYOK_ENABLED` 行与 BYOK 相关注释删除；`SECURITY_CRYPTO_MASTER_KEY` 及 `security.crypto` 配置保留（MCP 消费）。
6. Flyway 新增 `V31__drop_llm_config.sql`（决策 8）。
7. `BailianChatClientFactory` Javadoc BYOK 措辞更新，`sdkEngaged` 守卫逻辑零改动（决策 9）。（v3.1：原第 7 点"前端页面与调用代码下线"删除——前端不存在 llm-config 代码。）
8. 保留文件 BYOK/llm-config 措辞清理（v3.2，AC3 `(?i)byok` + `llm-config` 清零的对象）：`HostSafetyValidator`/`SecurityCryptoProperties` Javadoc 的 BYOK 措辞改述（原语现由 MCP 消费，免 key 豁免经 `isLoopbackEndpoint`）；`ChatServiceImpl` buildChain/resolveCandidateId 的 BYOK 注释随机械回退同步更新；`ChatController` /api/admin/llm-config 引用注释更新；`BailianChatClientFactoryTest` BYOK 措辞更新（守卫回归语义不变）。

**测试**：删除 modelconfig 测试包全部 7 文件（清单见 §2）；`LlmClientRegistryTest` 收窄改造（BYOK 段删除，系统级全量回归）；`ChatServiceImplTest`/`ChatServiceImplModelSelectionTest`/`ChatServiceImplResolveCandidateIdTest` BYOK 用例删除，系统用例的 `getUserChain`/`getUserDefault` mock 机械迁移为 `getChain`/`getDefault` 后回归全绿；`BailianChatClientFactoryTest` 守卫回归全绿。删除符号的残留引用由编译期暴露（无反射/无字符串装载）。

### WS-C 验收收尾（决策 11）

1. grep 清零断言（test 级文件扫描——yml/`.env.example` 非 ArchUnit 管辖）：`"u:` 字面量、`(?i)byok`（main/test/yml + `.env.example`，覆盖 Byok/byok/BYOK 三形态）、`llm_config` 与 `llm-config`（main 代码与资源，`db/migration` 历史文件除外）。
2. modelconfig 目录不存在断言；`/api/user/llm-config`、`/api/admin/llm-config` 返回 404。

## 5. 测试策略

- **WS-A**：MockWebServer（协议层端点/凭据/路径解析 + 共享传输同签名同实例）+ `isLoopbackEndpoint`/`isAvailable` 免 key 豁免边界；系统协议层线序行为零变化；既有 llm 客户端测试全绿。
- **WS-B**：编译完整性（删除符号零残留引用）；registry 收窄回归；chat 链路回归（BYOK 用例删除 + 系统用例 mock 迁移 getUserChain/getUserDefault → getChain/getDefault 后全绿）；Bailian 守卫回归。
- **WS-C**：grep 断言 + 目录/端点不存在断言。
- **回归基线**：`ChatServiceImplTest`、`ChatServiceImplModelSelectionTest`、`ChatServiceImplResolveCandidateIdTest`、`ChatModelAssemblerTest`、`FallbackExecutorTest`、`LlmClientRegistryTest`、`BailianChatClientFactoryTest` 全绿。

## 6. 执行顺序与提交切分

```
WS-A（协议抽取 + 共享阻塞传输 + 免 key 豁免收紧，独立提交，系统协议层线序行为零变化）
 → WS-B（BYOK 全量删除 + 调用点机械回退，独立提交）
   → WS-C（grep 清零与收尾断言，独立提交）
```

WS-A 与 WS-B 无相互依赖（可互换序），按 A→B 保持与 resilience WS1–WS3 的前置衔接。遵循 AGENTS.md 协议：被改符号编辑前 `impact(target, upstream)`，提交前 `detect_changes()`。

## 7. 风险与回滚

| 风险 | 缓解 | 回滚 |
|---|---|---|
| 删除面大（modelconfig 模块 + registry 机制 + 事件连坐） | 全部为 BYOK 专属：base yml 默认 false；B4 断链下功能本就不可用；调用方仅 ChatServiceImpl 两处 + 事件发布一处，编译期全部暴露 | revert WS-B + 前向迁移重建表（Flyway 无自动反向迁移，实际为新增 V32 从 V16 复制 DDL；实施前整库已重置，见决策 8） |
| 传输收敛（v2.2 沿用）：阻塞路径 per-candidate RestClient → 共享实例 | 协议层线序行为零变化断言 + 同签名同实例断言；per-candidate `HttpHandles` 生命周期消失由 HttpClientFactory 统一管理 | revert WS-A |
| keyless 本地供应商由"构造期跳过"变为"可用"（v3.1 申报修复，非回归；v3.2 豁免门卫同步收紧） | `isAvailable()` Javadoc 意图与 `GenericChatClient` requireNonNull 的潜伏矛盾修复；免 key 豁免经 `HostSafetyValidator.isLoopbackEndpoint` 字面回环判定（子串反例不再放行，fail-safe）；协议层 apiKey 缺省不发送 Authorization 头，MockWebServer 两路断言（AC1）；当前 yml 四家 provider 均配 key，无实际触发面 | revert WS-A |
| `UserDeletedEvent` 删除后未来需要用户删除广播 | 无消费者发布即死代码；git 历史可恢复，届时显式重新引入 | revert 或从历史恢复 |
| V31 drop 迁移误伤 | `DROP TABLE IF EXISTS llm_config` 单表精确；实施前数据库整体清空重置（用户决策，v3.2），无存量数据 | 前向迁移（V32）重建表 |
| resilience 方案中 BYOK 前提条目表述漂移 | 接口级结论不变（WS4/WS5/WS7 前提随移除自然成立）；其文档下次修订同步 | — |

## 8. 验收标准

- [ ] AC1：协议层生效——`openai-compatible` 协议经 `ResolvedEndpoint` 发出（MockWebServer 断言目标 URL、端点路径；带 key 用例断言 Authorization Bearer 值，keyless 用例断言请求**不含**该头——阻塞/流式两路）；阻塞/流式均经共享传输（同超时签名同实例，无 per-candidate HttpClient 构造）；免 key 豁免经 `isLoopbackEndpoint` 字面回环判定（localhost/127.x 字面/`[::1]` 放行，子串反例拒绝）；系统路径经薄壳协议层线序行为零变化（keyless 本地候选变可用、豁免门卫收紧均为 §7 申报变更）。
- [ ] AC2：系统链路全量回归绿——agent/intent/rewrite 既有测试零适配；chat 三测试类（`ChatServiceImplTest`/`ChatServiceImplModelSelectionTest`/`ChatServiceImplResolveCandidateIdTest`）删除 BYOK 用例并将系统用例 mock 迁移（getUserChain/getUserDefault → getChain/getDefault）后全绿。
- [ ] AC3：grep 清零——`"u:`、`(?i)byok`（大小写不敏感，main/test/yml + `.env.example`）、`llm_config` 与 `llm-config`（main，`db/migration` 除外）为零。
- [ ] AC4：modelconfig 模块目录不存在；`/api/user/llm-config` 与 `/api/admin/llm-config` 404。
- [ ] AC5：registry 纯系统级——决策 5 清单符号全部不存在；`LlmClientRegistryTest` 收窄后绿。
- [ ] AC6：`ResolvedEndpoint` 的 apiKey 不出现在任何日志输出（toString 脱敏测试）。
- [ ] AC7：`V31__drop_llm_config.sql` 应用后 `llm_config` 表不存在。

## 9. 架构参照：pi `packages/ai` 取舍记录（v3.0）

- **采纳**：无状态 API 实现——协议/适配器不持有凭据与连接，凭据随请求以参数传入；HTTP 传输共享（连接池全局复用）。
- **不再采纳**（随 BYOK 移除失去对象）：Provider 描述子目录、模型两键身份、调用时凭据解析（CredentialStore/per-user 域）、per-request 调用方 key 覆盖——这些解决的是多源/per-user 凭据问题；本项目现为单一系统源（yml），现有 `ProviderConfig` 绑定 + registry 系统快照已足够。若未来重启 BYOK，v2.x 修订历史（本文件上方）保留完整决策脉络可回溯。
- **后续项（v3.1 记录；v3.2 部分前置完成）**：免 key 豁免的回环判定已在本轮接入 `HostSafetyValidator`（`isLoopbackEndpoint`，决策 4/WS-A 第 5 点）；若未来 provider 配置出现输入面（如管理端动态下发、重启 BYOK），再接入 `HostSafetyValidator.validate()` 全量 SSRF 校验——届时须评估其端口白名单/内网黑名单与内网网关、回环同机部署的冲突（豁免与全量校验的共存口径待那时定）；当前 yml 单源（运维控制）无输入面，不做。
