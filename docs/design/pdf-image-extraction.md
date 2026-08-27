# PDF 解析性能优化与图片提取后台化设计（OpenDataLoader 2.5.5）

> **版本**: v1.6（第五轮评审修订）
> **日期**: 2026-08-27
> **状态**: 设计方案（五轮评审意见已处结，待复审）
> **依赖升级**: `opendataloader-pdf-core` 2.5.0 → **2.5.5**（已实施，见 §2）
>
> **v1.6 修订摘要**（第五轮评审，编号沿用评审原文；全部经源码复核属实）：
> - **严重-1（FastTrack 完全未纳入）**：属实——`EtlFastTrackProperties` 默认
>   enabled=true / maxDocCount=10 / maxTotalSize=5MB，且 `FastTrackStrategy.getOrder()=0`
>   先于 Standard 判定：单文档 ≤5MB 的 PDF 上传（最常见形态）恒路由 FastTrack；其
>   extractAll 调两参 `extractor.extract()`（`:240`，无 ParseContext → 无 manifest/占位符），
>   且 writeBm25Row → completeDocument(id,0)（`:119-120`）先于异步向量写入——v1.5 的
>   "向量写→短事务"形状在 FastTrack 不存在。已补 **§6.3.1 集成方案**（不采纳"PDF 排除出
>   FastTrack"：最常见小文档将失去 BM25-first 能见度，行为回退）；验收 3/4/5 显式双路径。
> - **严重-2（SETNX × dedupKey=documentId 击穿崩溃重投）**：属实——`subscribe` 对所有
>   handler **无条件**包 `IdempotentHandler.wrap`（`:226-227`；`idempotent.enabled` 经
>   全库复核无代码消费者、不 gate 包装，唯一豁免是消息不带 dedupKey），SETNX 先标记后执行、
>   TTL 默认 900s（application.yml 实配 **90000s**，窗口更长）；进程在"已标未完未 ACK"被杀
>   → 重投判重静默跳过正常返回 → SIMPLE 自动 ACK → 消息永久消费完毕。已修：本 topic 消息
>   **不设 dedupKey**（§6.3），消费幂等本就是行级条件更新，双投递抑制不需要 SETNX。
> - **高-1（outbox 短事务依赖的 API 均 private）**：属实——`insert()`/`tryImmediate()`
>   （`:99/:134`）private，8 字段构造语义（`:194-219`）不可绕行复刻。已修：`OutboxMessageBus`
>   新增公共方法 **`sendInTransaction`**（§6.3，签名/归属/语义落定）。附带实锤陷阱：装饰器的
>   `sendAfterCommit` 被覆盖为直接 `send()`（`:239-243`），持有事务调用仍会立即 tryImmediate
>   ——高-1（第四轮）窗口依旧，明示禁用。
> - **高-2（completeDocument 并入短事务后 SSE 事件先于提交）**：属实——
>   `executeWithoutResult` 返回后立即 `publishStatusEvent`（`:62-71`），TransactionTemplate
>   默认 REQUIRED 并入外层 → 回滚时 UI 已收 COMPLETED 假状态。已修：EtlStatusManager 全部
>   事件发布点改为**事务感知**（同步器激活时 afterCommit 发布，否则立即，§6.3）。
> - **中-1（TOC 递归无动态验证）**：属实——EXP6 仅实跑 TABLE/HEADER-CASE；`writeTOC` 经
>   `SemanticTOCI.getContents()` 的递归（2.5.5 sources jar `:310-321` 已核实存在）是 numberer
>   镜像中唯一无实验证据的递归分支。已修：TOC-CASE 升格独立造图用例，列为 **P2 开工前置
>   实跑门槛**（§8.5/§10）。
> - **中-2（新 topic 平台配置面未列）**：已补 **§6.9**——application.yml 新增
>   `app.etl.image` 消费配置块；`ordered-topics` 决策为**不纳入**（全库复核该白名单当前无
>   代码级消费者，且总线不分区、投递序不构成消费序；正确性由 image:lock + 条件更新保证）。
> - **中-3（线程模型与 runner 实态不符）**：属实——线程由 `RedisStreamConsumerRunner`
>   提供（`redis-receive-{topic}` + `redis-process-{topic}-N`），并发经
>   `ConsumerConfig.concurrency` 表达。已修：取消自建 image-io 池与 consume() 内自设
>   Semaphore（与 runner inflight 双重限流、语义冲突），`odlImageConcurrency` 1:1 映射
>   ConsumerConfig.concurrency（§6.4/§7）。
> - **中-4（前台多文档线程预算未量化）**：已补 §7 预算公式与联动约束（并发文档数 ×
>   odlThreads 上界、高核超卖说明、2C 收益归零属预期、控制总量优先降消费并发）。
> - **低（5 条）**：L1 H3 断言失败信息区分方向（">manifest 疑正文伪造 / <manifest 疑截断"，
>   §6.2）；L2 manifest 行增 `producer_version` 戳 + P3 对账 `version_skew` 维度（§6.3/§6.8）；
>   L3 新增 `odlImageMaxPerDoc` 消费侧预算 + 存储生命周期说明（§6.6/§6.8）；L4 pom 状态
>   说明改"已提交"（`5d417cf`）、设计载体改名 **ExtractWithManifest** 规避
>   StandardStrategy:186 / FastTrackStrategy:266 同名（§2.3/§6.3）；L5 验收 7 增 GC pause
>   分布采集与归因标注（§11）。
>
> **v1.5 修订摘要**（第四轮评审，编号沿用评审原文；全部经源码/实验复核）：
> - **严重-1（前台 try 域）**：属实——v1.4 伪代码 `extractContents` 写在 try 之外，提取中途异常
>   则 `cleanupMirror()` 永不执行（EXP2' 已证 extractContents 不自清理），确定性泄漏。已修：
>   try 域覆盖 extractContents。附带核实：ODL 每文档 ForkJoinPool 在 `processDocument`
>   自身 finally 中 `shutdown()`（`:435-436`），异常路径无线程泄漏。
> - **严重-2（supersede 前提反了）**：属实——`supersedeOldVersion:301-309` +
>   `RagDocumentMapper.updateSuperseded:46`：**新版本 = 新 documentId**，旧文档标记 SUPERSEDED
>   终态后删向量删原文件。v1.4"documentId 存续→交对账"整段推理作废。连锁修复：(a) 消费端
>   文档校验改为"存在且状态可处理"（SUPERSEDED 视同已删除：清行清对象+ACK，杜绝旧消息
>   下载 404 烧预算误报 dead）；(b) supersede 路径图片清理与删除路径同构（best-effort 同步
>   清行+前缀对象）；(c) 对账矩阵补 SUPERSEDED 档位。
> - **高-1（outbox 即时投递先于提交）**：属实——`send()` = insert（加入未提交事务）+
>   `tryImmediate`（`:98-102`，内存态消息异步投递），消费可先于短事务提交 → findPending 空 →
>   ACK → 消息被消费、行被删 → 新 PENDING 行失去驱动器。已修：短事务内 insert +
>   `TransactionSynchronization.afterCommit` 触发即时投递（失败由 relay 兜底——行已随
>   manifest 原子提交）。
> - **高-2（IdentityHashMap 别名破坏双射）**：设计缺陷成立（同对象多处引用时二次 put 覆盖，
>   计数断言拦不住）。已修：numberer 改**按遍历出现**记录条目，两侧各自独立全局递增 seq
>   （对同一对象图的同构遍历保证位置对齐，EXP6 原型即此形态），别名二次出现显式计数
>   `rag.image.alias_occurrence` 不去重。
> - **中**：resetUnfinishedToPending 条件化+终态不可回退（中-1）；GenerationInvalid 中止后
>   复查 findPending>0 则重投不 ACK（中-2）；**EXP7 已运行通过**——bbox 经清单 round-trip 后
>   `getPageSubImage(bbox,144)` 两次独立运行像素哈希一致，且发现 **SemanticPicture 仅由
>   hybrid 转换器构造**（`DoclingSchemaTransformer:384` 等），本地模式（H-C1）恒不产生，
>   manifest 的 PAGE_RENDER 仅来自无 XObject key 的 ImageChunk 兜底（中-3+结构修正）；
>   解码前从 XObject 字典 Width/Height 预判（中-4）；粘性失败标志经字节码证实**会被
>   `updateContainers(null)` 重置**（偏移 199-212：`imagesUtils.remove()` +
>   `isImagesUtilsFailedToCreate.set(false)`），清理镜像已覆盖，无永久循环（中-5）；
>   findPending 补 `ORDER BY page_number, seq`（中-6）；对账"chunk 反查"改为摄取时在
>   chunk/document metadata 记 `imagePlaceholderCount`，对账变元数据比对，不扫向量库文本
>   （中-7）；伪代码 Config 走统一门面构造器 + 锁获取移入 try 域（中-8）。
>
> **v1.4 修订摘要**（第三轮评审，编号沿用评审原文）：
> - **高-1（numberer 遍历域）**：实锤——`isSupportedContent` 对 `SemanticHeaderOrFooter` 返回
>   `includeHeaderFooter`（Config 默认 false，`:92`），页眉脚子树（含嵌图）被生成器整体剪掉；
>   而 v1.3 numberer 无条件递归页眉脚 → 带图页眉的文档 manifest 恒大于占位符数 → H3 断言
>   恒假 → 确定性索引失败。反向缺陷同步实锤：`writeTOC` 经 `writeContents` 递归 TOCI 内容
>   （`:311`）可产占位符，numberer 未遍历 TOC → 少计同样触发断言。修复：numberer 遍历域
>   **完整镜像 `isSupportedContent` 语义**（includeHeaderFooter=false 整枝剪掉页眉脚 + 补 TOC
>   递归），§8.5 补"页眉脚嵌图"造图用例。
> - **高-2（重建与在途消费者竞争）**：实锤——etl:lock 与 image:lock 互不互斥，消费者按
>   DELETE+INSERT 前的旧快照上传、对已删行 markUploaded 静默失效（update-by-id 0 行）。
>   修复采用条件更新协议：行状态迁移一律 `UPDATE … WHERE id=? AND status='PENDING'`，
>   0 行即代际失效 → 中止本批（新消息驱动新 manifest），杜绝锁耦合。
> - **中-1（事务前提）**：实锤——rag/etl 无 @Transactional、向量库写天然不可入 DB 事务。
>   改为**显式新建短事务**（TransactionTemplate：状态更新 + manifest 重建 + outbox INSERT
>   同 tx，outbox INSERT 加入调用方连接见 OutboxMessageBus `:49`）；失败窗口（向量已写、
>   manifest 回滚）补 P3 对账维度"占位符存在但 manifest 零行"。
> - **中-2（manifest 传递通道）**：定义 `ExtractOutput(documents, imageManifest)` 类型化载体
>   （`Extractor.extract` 新重载，默认委托旧签名），DELETE+INSERT 归属 EtlRouteStrategy 内
>   的短事务，杜绝临场发明第二套传递惯例。
> - **中-3（对账范围）**：扩为真正三方比对（document_image ↔ 前缀对象 ↔ 文档表），覆盖
>   活文档多余对象（重解析缩水 + supersede 路径 `DocumentSupersedeService.cleanupStorageFile`
>   删旧原文件但 documentId 存续）。
> - **中-4（fail-closed 降级档位）**：新增 `odlPlaceholderStrict` 开关（默认严格失败；
>   可降级为剥离占位符纯文本索引 + `rag.image.placeholder_integrity_degraded` 高优告警），
>   完整性与可用性解耦。
> - **低（5 条）**：§6.8 删除路径措辞按 best-effort 现实改写（低-1）；consume() 补文档存在
>   校验与 `getImagesUtils()` 判空（低-2）；解码异常按类型细分（decode-unsupported →
>   SKIPPED 终态，低-3）；maxBytes 增加编码前像素规模预判（低-4）；hybrid 值清单补
>   `hancom-ai`（低-5①）、downloadToTemp 失败自清理契约（低-5②）、ack/nack 与异常驱动
>   契约的映射说明（低-5③）、§11.7 测量口径定义（低-5④）。
>
> **v1.3 修订摘要**（第二轮评审，编号沿用评审原文）：
> - **高-1（占位符计数）**：`writeTable` 整行一次输出（`:348` 实证），表格一行两个含图单元格
>   即同行 ≥2 占位符——按行计数必偏小导致断言恒假、文档确定性无法索引。改为正则**出现次数**
>   统计，并明示 ImageNumberer/MarkdownGenerator 双遍历分歧的 fail-closed 语义；§8.5 增补
>   "表格嵌套多图"造图用例锁定一致性。
> - **高-2（ext 确定性）**：删除"PAGE_RENDER→jpeg 除非透明度需要"条件子句（前台无法求值，
>   与冻结 URL 契约自相矛盾）——ext 仅由 img_type 决定，PAGE_RENDER 渲染统一压平 alpha
>   白底，确定性成为构造保证（§6.3）。
> - **高-3（manifest 重建）**：定义同 documentId 重解析机制 = 同事务 `DELETE + INSERT`
>   幂等重建（裸 INSERT 必撞 `UNIQUE(document_id, seq)`）；图片数变少的孤儿对象交 §6.8
>   对账；`ImageExtractJob` 移除 `password` 字段（明文持久化埋点）（§6.3）。
> - **中-1（锁规范）**：补全 `tryLock`（失败 NACK 重投，不静默 ACK）、`isHeldByCurrentThread()`
>   守卫解锁、`semaphore.release()` 严格对称最后执行——照抄实现不再有连锁异常/许可泄漏
>   导致 image-io 车道停摆的隐患（§6.4）。
> - **中-2（超限终态）**：编码后 `bytes.length > max` 归类结构性 `SKIPPED(max-bytes-exceeded)`
>   终态，不入重放通道（避免烧完预算后 `extract_dead` 误告警）（§6.4）。
> - **中-3（SLA 条款化）**：§11.1 改条件条款——preprocessing 串行段实测 >3s 触发 SLA 修订
>   评审或立项优化，不默认违约。
> - **中-4（积压可观测）**：P2 补三个先行指标（PENDING 总量/最老行年龄 gauge、消费耗时
>   timer）+ SKIPPED 独立计数 `rag.image.extract_skipped`（§6.4）。
> - **低**：L4 字符集护栏改显式校验抛异常（assert 生产无效）；H-C1 从文档纪律升级为门面
>   fail-fast 机制（§6.2/§9）；§8.4 增补同文件双线程用例（EXP5 已运行通过：20 轮逐字节
>   一致），把 Redisson 锁失效场景纳入契约测试网。
>
> **v1.2 修订摘要**：图片读取端点整体顺延（另期立项）——本期交付边界为"图片进 MinIO +
> `document_image` 清单落库 + 占位符随索引固化"；§6.5 改为仅固化占位符 URL 格式契约
> （端点未来按契约实现，无需重建索引），H4（private 缓存）/L3（ext 校验）结论在 §6.5 存记
> 备查；§4 非目标、§6.7 降级语义（占位符为惰性文本，无 404 语义）、§10 P3 同步调整。
>
> **v1.1 修订摘要**（对应评审编号）：
> - S1：七类容器 ThreadLocal 纯度改为**运行时 classpath 二进制 javap 复核**（含版本号），
>   补充 `HybridDocumentProcessor` 裸静态量的"仅 hybrid 路径写入"事实与 `hybrid=off` 硬约束；
>   新增 **EXP4 双线程并发隔离实验**（已运行通过）并列为 P1 放行门槛（§3.2/§3.5/§8/§10）。
> - H1：清理镜像补齐为与 `closePdfResources` 逐步对齐的 **9 步**，契约测试增加"镜像步骤数 ==
>   源码清理步数"断言（§3.3/§8）。
> - H2：页渲染缓存实测为**自清理**（换页即 clear，缓存 ≤1 页），800MB 无界增长前提不成立；
>   仍采纳"换页/收尾显式 `clearRenderedPages()`"作显式保险，并记录 getSubimage 共享光栅约束（§3.4/§6.4）。
> - H3：占位符生成后增加**数量一致性断言**，不等则整体失败，杜绝半截 Markdown 被索引（§6.2）。
> - H4：图片端点 Cache-Control 改为 `private`（仅浏览器缓存，禁止共享缓存跨用户复用）（§6.5）。
> - M1：性能数字改为"P1 分段计时实测后承诺"，验收口径统一 ≤5s（§4/§11）。
> - M2：超龄 PENDING 告警随 P2 上线，扫描动作留 P3（§6.4/§10）。
> - M3：新增 §6.8 文档删除的图片孤儿清理设计。
> - M4：XY-Cut 排序确实跑在公共 ForkJoinPool（源码证实），排序器经源码审计为纯函数；
>   风险与 EXP4 覆盖方式明示（§3.4/§7）。
> - M5：契约测试 §8.3 恒真命题替换为"占位符↔manifest 双射"+"后台产出↔external 参考抽样比对"（§8）。
> - M6：SKIPPED 收窄为结构性不可恢复；瞬时异常走 FAILED 重放（§6.4）。
> - M7：经核实后台线程的 ThreadLocal ImagesUtils **并未**在 preprocessing 后自动创建（惰性创建
>   仅由 `getImagesUtils()` 触发，本地提取路径不触发）；采纳统一走 `getImagesUtils()` 懒实例，
>   由镜像第 2 步自动关闭（§3.4/§6.4）。
> - M8：pom 固化 `oss-direct` 仓库（id ≠ central，绕过镜像拦截），已实施（§2.3）。
> - L1–L5：伪代码作用域、`Created null` 日志说明、ext 校验、占位符转义护栏、pom 状态说明（§2.3/§6.2/§6.4/§6.5）。

---

## 1. 背景与动机

### 1.1 实测性能问题

12MB 华为用户手册（100+ 页、截图密集）实测：ETL consumer 收到消息（09:56:07.891）到
OpenDataLoader 输出 Markdown（09:56:23.682）耗时约 **16 秒**。官方基准（Apple M4）本地模式
约 0.02s/页，差距约 8 倍。

### 1.2 根因（源码级定位，2.5.x 实测确认）

| # | 根因 | 源码证据 | 影响 |
|---|------|---------|------|
| 1 | `Config.threads` 默认 `1`，逐页内容过滤（ODL 源码自注 "largest bottleneck"）与 XY-Cut++ 排序全程单线程 | `Config.java:922`；`DocumentProcessor.processDocument()` 内建 `ForkJoinPool(parallelism)` 但 parallelism 取自该配置；`setThreads` 自动 clamp 到 `availableProcessors()` | 页数越多影响越大，主要耗时来源 |
| 2 | `Config.imageOutput` 默认 `external`，每个图片区域被栅格化 + PNG 编码落盘 | `Config.java:83`；`DocumentProcessor.java:563` 门控 `!isImageOutputOff() && (md\|html\|json)` 时执行 `ImagesUtils.write` | 截图密集的手册开销显著；且当前流程临时目录连同图片一起删除，**产出 100% 浪费** |

### 1.3 新需求

图片不再丢弃：提取后存入 MinIO（本期仅存储，不做返回前端的读取端点——v1.2 范围见 §6.5），
Markdown 以占位符引用并随索引固化，检索链路不受影响。

### 1.4 设计原则

- **文本即最终形态**：前台（索引关键路径）只做"提取 + 无图 Markdown"，图片全部移出关键路径；
- **优雅降级**：后台图片提取失败不影响文档索引与检索，占位符保留可重试；
- **可靠性优先于极限速度**：图片任务走既有 MessageBus（outbox → relay → RedisStream → consumer，
  天然具备 FIFO/重试/DLQ），不用裸 `@Async`（进程重启即丢任务）。

---

## 2. 依赖升级 2.5.0 → 2.5.5（已实施）

### 2.1 版本间关键变更（2.5.1–2.5.5）

| 版本 | 与本项目相关的变更 |
|------|------------------|
| 2.5.1 | 新增 `image-resolution` 选项（自定义渲染 DPI，#659）；markdown 转义 `<`/`>`（#637）；列表标签改进（#648）；排序比较器 IllegalArgumentException 修复（#667）；verapdf 升级（#677） |
| 2.5.2 | uni/u 风格字形提取（#690） |
| 2.5.3 | hybrid 模式长文档拆分（#693，本地模式不涉及） |
| 2.5.5 | 重复 content id 修复（#691）；PDFBox 对齐 veraPDF 的直接依赖声明（#697） |

### 2.2 本设计依赖的内部扩展点复核（2.5.5 sources jar 逐项验证）

| 扩展点 | 2.5.5 签名 | 可见性 | 用途 |
|--------|-----------|--------|------|
| `DocumentProcessor.extractContents(String, Config)` | `public static ExtractionResult`（`:179`） | public（javadoc 自述支持"仅提取、结果可复用"） | 前台仅提取不落盘 |
| `DocumentProcessor.preprocessing(String, Config)` | `public static void`（`:623`） | public | 后台重建线程状态 |
| `MarkdownGenerator(Writer, Config)` + `writeToMarkdown(contents)` | public 构造器（`:85`）/ public 方法（`:96`） | public | Markdown 直写内存 Writer |
| `MarkdownGenerator.isImageSupported` / `writeImage(ImageChunk)` | protected 字段（`:54`）/ protected 方法（`:201`） | protected | 子类注入占位符 |
| `DocumentProcessor.closePdfResources()` | `private static void`（`:91`） | **private** | 需手动镜像（§3.3） |
| 2.5.5 新增 `ImagesUtils(Double imageResolution)` + `getPageSubImage(bbox, dpi)` | ODL 包装类（`:60`） | public | 后台渲染 DPI 可调（默认 144） |

传递依赖 `okhttp-jvm 5.4.0` 仍在 → **pom 既有 exclusion 保留**。

### 2.3 升级验证结论

- `mvn dependency:resolve` / `mvn compile` 通过；
- **构建可复现性（M8，已实施）**：阿里云镜像对新发布构件存在同步空窗，pom 已固化
  `<repository>` `oss-direct`（id 刻意 ≠ `central`，不受 `mirrorOf=central` 拦截，直通
  repo1.maven.org）——CI 环境不再依赖本机 `dependency:get` 手法；
- §3.5 可行性实验在 2.5.5 上全部复跑通过；
- `threads` 默认仍为 `1`、`imageOutput` 默认仍为 `external`（`Config.java:922/:83`）——
  §1.2 两个根因在 2.5.5 不变，优化方案依然成立；
- **pom 状态说明（L5；v1.6 修订）**：2.5.5 版本 + `oss-direct` 仓库声明已随 commit
  `5d417cf` 提交入库（pom 无工作区 diff）——v1.5"工作区态、随本设计一并提交"的表述
  已过期，此处更正；

---

## 3. ODL 内部机制事实卡（全部经 2.5.5 源码 + 运行实验验证）

> 本章是设计成立的事实基础。所有结论均已验证，实施时**不要凭记忆改写**，升级版本后按 §8 契约测试复核。

### 3.1 管线五阶段与可拆分性

```
processFile(name, config)
 = processFileWithResult(name, config)
   ├─ extractContents(name, config)          // public，可独立调用
   │   ├─ preprocessing(name, config)        // public：开 PDDocument、veraPDF 解析、表格边框检测
   │   ├─ calculateDocumentInfo / processDocument   // 逐页内容过滤（ForkJoinPool(threads)）
   │   ├─ sortContents                       // XY-Cut++ 阅读顺序排序（threads>1 时并行）
   │   └─ ContentSanitizer                   // 敏感信息清洗
   ├─ generateOutputs(name, contents, config, metadata)   // JSON/MD/HTML/图片落盘
   └─ finally closePdfResources()            // private！关闭 PDDocument + 清空全部容器
```

`extractContents` 单独调用**不会**触发 `closePdfResources`——资源释放责任在调用方（§3.3）。

### 3.2 状态模型：容器纯度（运行时二进制复核 + 并发实验双重证据）

**静态证据（S1 升级：不再止于源码阅读，而是对运行时 classpath 实际解析的 jar 用 `javap -p`
复核字段声明，版本号即 2.5.5 依赖树解析结果）**：

| # | 容器类（jar 版本） | 字段结论 |
|---|-------------------|---------|
| 1 | `org.verapdf.tools.StaticResources`（parser 1.31.48） | 全部 `ThreadLocal`（document/flavour/cMapCache/cachedFonts 等） |
| 2 | `org.verapdf.wcag…containers.StaticContainers`（wcag-algorithms 1.31.43） | 全部 `ThreadLocal`（document/fileName/password/objectKeyMapper 等） |
| 3 | `org.verapdf.gf…containers.StaticContainers`（validation-model 1.31.160） | 全部 `ThreadLocal`（flavour/separations/cachedColorSpaces/cachedFonts 等） |
| 4 | `org.opendataloader.pdf.containers.StaticLayoutContainers`（odl 2.5.5） | 全部 `ThreadLocal`（imageIndex/imagesDirectory/embeddedImageBytes 等） |
| 5 | `org.verapdf.gf…containers.StaticStorages`（wcag-validation 1.31.160） | 全部 `ThreadLocal`（chunks/isIgnoreMCIDs 等） |
| 6 | `org.verapdf.containers.StaticCoreContainers`（core 1.31.35） | `ThreadLocal`（flavour） |
| 7 | `org.verapdf.xmp.containers.StaticXmpCoreContainers`（xmp-core 1.31.35） | 全部 `ThreadLocal`（namespace 双向 map） |

七类中唯一非 ThreadLocal 的静态字段是无状态 `Logger`。**审计死角披露**：`HybridDocumentProcessor`
（odl 2.5.5）确有裸静态可变量（`static volatile lastHybridTimings / lastElementMetadata /
lastOcrWordsByPage` 等），且 `extractContents` 收尾无条件读取——但写入只发生在
`HybridDocumentProcessor.processDocument`（hybrid 路径开头 reset + 处理中赋值，`:271/:459/:748/:814`）。
**硬约束 H-C1**：本设计的并发安全性以 `hybrid=off` 为前提；一旦任何链路启用 hybrid
（`Config.java:42-54` 共六个非 off 值：`docling/docling-fast/hancom/hancom-ai/azure/google`），
上述裸静态量即构成跨线程竞写，必须先加全局锁
或上游串行化，此约束写入 §8 契约测试与 §9 升级守则。

**动态证据（S1 放行门槛）**：EXP4（§3.5）双线程并发提取不同 PDF、含 threads=2 的公共池并行排序，
20 轮输出与单线程参考**逐字节一致**，且并发轮次 `elementMetadata` 恒空（旁证 hybrid 静态量在
本地模式未被触碰）。该实验固化为 §8.4 契约测试，作为 **P1（放大并发放行）的硬门槛**。

推论（证据等级同步升级）：

- **推论 1**（静态+动态）：前台线程与后台线程各自 `preprocessing` 后状态互不干扰——跨线程拆分可行；
- **推论 2**（实验证实）：同一文件在另一线程重新 `preprocessing` 后，前台提取的 `contents` 中
  图片定位数据依然可用（`ObjectKey` = 对象号 + 代次，`public ObjectKey(int number, int generation)`
  纯数据，对同一文件跨次打开稳定）；
- **推论 3**（静态+动态，附条件）："ODL 全局锁"在 `hybrid=off` 下**不是正确性必需**，是资源保险
  （每管线渲染整页 144DPI RGB ≈ 8MB/页）——限并发实现（v1.6）= `ConsumerConfig.concurrency`
  → runner inflight Semaphore（§7）；约束 H-C1 除外。

### 3.3 资源生命周期与清理镜像

一次完整管线会**独立打开同一 PDF 最多三次**：

| 持有者 | 打开方式 | 释放责任 |
|--------|---------|---------|
| `StaticResources.getDocument()`（veraPDF `PDDocument`） | `preprocessing` 中 `new PDDocument(pdfName)` | `closePdfResources`（private） |
| `StaticContainers.getImagesUtils()`（惰性创建的 veraPDF `ImagesUtils`） | 首次 `getImagesUtils()` 时 `Loader.loadPDF(...)` | `StaticContainers.closeImagesUtils()` |

`extractContents` 独立使用时的**手动清理镜像**（H1：与 `closePdfResources` 源码**逐步对齐的
9 步**，全部 public 调用；EXP4/EXP3 实验验证可编译可执行。任何调用过 `preprocessing` 的线程
收尾时必须执行，实现收敛到唯一的 `OdlResourceCleaner.cleanupMirror()`）：

```java
// 步骤号与 2.5.5 closePdfResources 的 clearCleanupStep 序列一一对应（DocumentProcessor.java:91）
void cleanupMirror() {
    step( "PDDocument",             () -> { var d = StaticResources.getDocument(); if (d != null) d.close(); } );
    step( "ImagesUtils",            () -> StaticContainers.closeImagesUtils() );          // 释放惰性实例（独立加载的 PDDocument）
    step( "StaticResources",        () -> StaticResources.clear() );
    step( "StaticContainers",       () -> StaticContainers.updateContainers(null) );       // veraPDF wcag
    step( "GFStaticContainers",     () -> GF StaticContainers.clearAllContainers() );      // org.verapdf.gf.model.impl.containers
    step( "StaticLayoutContainers", () -> StaticLayoutContainers.clearContainers() );      // ODL
    step( "StaticStorages",         () -> StaticStorages.clearAllContainers() );
    step( "StaticCoreContainers",   () -> StaticCoreContainers.clearAllContainers() );
    step( "StaticXmpCoreContainers",() -> StaticXmpCoreContainers.clearAllContainers() );
}
```

- 镜像的步骤**名称清单**固化为 `MIRROR_STEPS` 常量；契约测试断言其与 `closePdfResources`
  字节码中 `clearCleanupStep("…")` 的字符串序列完全一致（§8.2）——升级 ODL 时若清理序列
  变化，CI 即刻失败，杜绝镜像漂移；
- 不清理的后果：每文档泄漏打开的文件句柄与堆内 COS 对象（`updateStaticContainers` 只清引用
  不 `close()`）。

### 3.4 图片定位数据与取图路径

- **ImageChunk**（嵌入位图）：`getStreamInfos().get(0).getXImageObjectKey()` →
  `ImagesUtils.getXObjectImage(page, key)` **直接解码原始嵌入图**（无渲染，快、保真）；
- **SemanticPicture**（矢量/复合区域；**EXP7 结构发现：仅由 hybrid 转换器构造，本地模式
  恒不产生**，实际渲染需求来自无 XObject key 的 ImageChunk 兜底）：`getBoundingBox()` →
  `ImagesUtils.getPageSubImage(bbox, dpi)` 按 DPI（默认 144，2.5.5 起可调）渲染页区域裁剪；
- **页渲染缓存实况（H2，1.31.43 字节码证实）**：`getRenderPage(int, Double, boolean)` 在渲染
  新页前**自调用** `clearRenderedPages()`（字节码偏移 24），缓存至多持有 **1 页**（144DPI 整页
  RGB ≈ 8MB），不存在百页累积路径；`HiddenTextProcessor` 的 finally 清理仅在其启用
  （`filterHiddenText=true`，本项目为 false）时是额外兜底。**采纳评审建议**：后台取图循环在
  **换页时与整批结束后显式调用 `clearRenderedPages()`**，将内存上界显式化而非依赖库内行为；
  另注意 `getSubimage()` 返回**共享父光栅的视图**——子图必须当页编码后即弃，不得跨页持有引用；
- **实例获取（M7，事实修正）**：veraPDF `ImagesUtils` 的 ThreadLocal 实例由
  `StaticContainers.getImagesUtils()` **惰性创建**（失败置 `isImagesUtilsFailedToCreate`）；
  本地提取路径（`filterHiddenText=false` 且 `images=off`）**不会**触发该创建——即 preprocessing
  后 ThreadLocal 实例并不存在，`new ImagesUtils()` 不会造成第二次加载。**统一约定**：后台取图
  一律通过 `StaticContainers.getImagesUtils()` 获取懒实例（与 ODL 自身 `createImageFile` 同一
  路径），单实例单加载，且由清理镜像第 2 步 `closeImagesUtils()` 自动关闭，无需手工 try-with-resources；
- **XY-Cut 排序线程池（M4，源码证实）**：`sortContents` 的 `threads>1` 分支用裸
  `IntStream.parallel()`（`DocumentProcessor.java:884`）——跑在 **JVM 公共 ForkJoinPool** 上
  （非 ODL 每文档私有池），common pool 工作线程未经 `propagateState`。经源码审计
  `XYCutPlusPlusSorter` 为**纯函数**（无任何静态可变状态引用，仅 static 方法 + 入参对象操作），
  该阶段无 ThreadLocal 依赖；残余风险：(a) 与应用内其他 parallel 流互相挤占（公共池默认
  并行度 = 核数-1）；(b) 未来版本若 sorter 触碰容器即裸状态执行——由 §8.4 并发隔离实验
  （threads>1 必现公共池排序路径）覆盖检测；
- `images=off` 时 `contents` 中 **ImageChunk/SemanticPicture 照常存在**（`imageOutput` 只被输出
  阶段消费：`MarkdownGenerator` 的 `isImageSupported` 门控（仅 ImageChunk 分发）与
  `DocumentProcessor:563` 的写盘门控；`SemanticPicture` 无条件分发但原实现因图片文件不存在而
  静默不输出）；
- `ImageChunk.getIndex()` 仅在 `ImagesUtils.write` 内分配——`images=off` 下**未赋值**，编号必须
  自理（§6.2）。

### 3.5 可行性实验证据（2.5.5，项目 classpath 实跑）

```
EXP1 processFile(images=external) 259ms → imageFile1.png 正常落盘（现状图片路径工作正常）
EXP2 extractContents(images=off, threads=4) → ImageChunk 存在且携带 ObjectKey
EXP3 [后台线程] preprocessing(3ms) → new veraPDF ImagesUtils()（实验用法；设计统一为 getImagesUtils() 懒实例）
    → getXObjectImage(前台 contents 的 key, 重开的文档) → 120x80 取图成功(3ms)
    → 手动清理镜像执行成功
EXP2' [前台线程] extractContents 后 PDDocument 仍处于打开状态（泄漏实锤，需 §3.3 镜像）
EXP4 [S1 并发隔离] 双线程并发提取不同 PDF（各 5/6 页、含图、threads=2 触发公共池并行排序），
    ×20 轮：输出与单线程参考逐字节一致，0 次错配/异常；并发轮次 elementMetadata 恒空
    （旁证 HybridDocumentProcessor 裸静态量在本地模式未被触碰）
EXP5 [S1 同文件并发] 双线程并发提取同一 PDF 文件，×20 轮：输出与参考逐字节一致
    （提取对文件只读、每线程独立 PDDocument——Redisson 锁失效时提取层仍字节级安全）
EXP6 [v1.4 遍历域一致性] 原型化前台管线（numberer 镜像 + PlaceholderGen 子类 + H3 断言），
    对抗性造图实跑：
    TABLE-CASE（网格表一行两图单元格）：manifest=2 = 占位符出现次数=2，且两占位符落在
      同一 Markdown 行（按行计数=1）——同时实锤"按行计数必偏小"与"表格嵌套递归镜像正确"；
    HEADER-CASE（3 页重复页眉 logo）：类型转储证实 logo 被分类为 SemanticHeaderOrFooter
      内嵌 ImageChunk（页顶 bbox），includeHeaderFooter=false 下 manifest=0=占位符数=0
      ——镜像剪枝生效；反证：若按 v1.3 无条件递归页眉脚，manifest=3 vs 占位符=0，
      断言恒假、该文档确定性无法索引（评审高-1 的故障场景被动态复现并证实已修复）；
    未覆盖（v1.6 中-1披露）：writeTOC 经 SemanticTOCI.getContents() 的递归分支
      （2.5.5 sources jar :310-321 已核实存在）——numberer 镜像中唯一无动态证据的递归，
      由 §8.5 TOC-CASE 在 P2 开工前以同型原型补跑闭环
EXP7 [v1.5 PAGE_RENDER 路径] ImageChunk bbox 经清单 round-trip（[l,b,r,t] 序列化重建）后，
    后台线程 preprocessing + getImagesUtils() + getPageSubImage(bbox, 144dpi)：
    → 600x420（300x210pt × 2 倍 DPI 换算正确），两次独立运行像素 SHA-256 哈希一致
    （fd3bff047c3f159d…）——渲染路径可用且跨运行像素级确定（中-3 闭环）。
    结构发现：SemanticPicture 仅由 hybrid 转换器构造（DoclingSchemaTransformer:384 等），
    本地模式（H-C1）恒不产生——矢量图 PDF 提取实测零图片对象，PAGE_RENDER 仅来自
    无 XObject key 的 ImageChunk（内联图等）兜底路径
```

---

## 4. 设计目标与非目标

**目标**

1. 前台（索引关键路径）耗时显著下降：**P1 先落地分段计时日志**（下载/写盘、preprocessing、
   逐页提取、排序、Markdown 生成五段），以实测分布校准预期后再承诺量化数字——背景事实：
   `preprocessing`（`new PDDocument` + `parseChunks` + 表格边框检测）严格串行且随页数/复杂度
   超线性，`threads=N` 只加速逐页提取与排序段，串行段会稀释总收益（M1）。**验收口径全文
   统一为 §11.1 的 ≤5s**，本文其余处的"2–4s"仅为量级预期不构成承诺；
2. 图片提取后台化：提取 → 上传 MinIO → Markdown 占位符固化（本期不返回前端，§6.5），
   全程不阻塞索引；
3. 可靠性：图片任务经 MessageBus 持久化投递，进程重启/多实例部署下不丢、可重试、幂等；
4. 占位符与上传图片的对应关系**由持久化清单保证**，不依赖跨运行的提取确定性。

**非目标**

- **不做图片返回前端**（v1.2：本期仅存储——图片进 MinIO + `document_image` 清单落库，
  不实现读取端点；占位符 URL 仅作为稳定文本契约随索引固化，端点实现另期立项，见 §6.5）；
- 不做 OCR / hybrid 模式接入（另题）；
- 不做图片内容理解（alt 文本留空，与 ODL "PDF/UA 禁止虚假替代文本" 的取值一致）；
- 不改 Parent-Child 分块策略（Markdown 结构不变，仅图片占位符替换死链）。

---

## 5. 总体架构

### 5.1 流程总览

```
┌─ 前台（etl-io-N，索引关键路径，量级预期见 §4/M1）──────────────────────┐
│ DocumentExtractor.extract(candidate)                                  │
│   └─ OpenDataLoaderPdfParser.parse(resource, mime, ParseContext)      │
│       1. MinIO 流 → 有界写入 temp PDF（现状不变）                      │
│       2. DocumentProcessor.extractContents(pdf,                      │
│            config[images=off, threads=N])                ← 无图片开销 │
│       3. PlaceholderMarkdownGenerator(sw, config)                    │
│            .writeToMarkdown(contents)  ← 占位符=确定性图片 URL        │
│       4. ImageNumberer 按 (页,序) 遍历 contents → ImageManifest       │
│       5. H3 完整性断言：占位符数 == manifest 数，不等整体失败           │
│       6. 清理镜像（§3.3 九步）+ 删除 temp PDF（生命周期回归 parse 内）   │
│       7. 返回 Document（文本即最终形态）                               │
│       8. 【显式短事务】状态更新 + document_image 幂等重建              │
│          （DELETE+INSERT）+ outbox INSERT 同 tx（§6.3，载体            │
│           ExtractWithManifest；outbox 经 sendInTransaction，H1）       │
└──────────────────────────────────────────────────────────────────────┘
                                  │ MessageBus（outbox→relay→RedisStream）
┌─ 后台（redis-process-rag_extract_images-N 池，并发=odlImageConcurrency，H-C1）─▼─┐
│ ImageExtractConsumer.consume(ImageExtractJob)                        │
│   1. Redisson 按 documentId 加锁（复用 ETL 锁模式，best-effort）       │
│   2. 从 MinIO 重新下载 PDF → temp（多实例安全，不依赖前台 temp 路径）    │
│   3. DocumentProcessor.preprocessing(pdf, config)   ← 重建线程状态     │
│   4. StaticContainers.getImagesUtils() 懒实例 → 按 manifest 逐条：      │
│        ImageChunk   → getXObjectImage(page, key)      （直解原始图）    │
│        SemanticPicture → getPageSubImage(bbox, dpi)   （区域渲染）      │
│        （换页/收尾 clearRenderedPages —— H2 显式内存上界）             │
│      → ImageIO 编码 → FileStorageService.upload（确定性 key，幂等覆盖）  │
│      → document_image 行状态 → UPLOADED；null→SKIPPED；异常→PENDING 重放│
│   5. 清理镜像（九步）+ 删除 temp + 解锁                                 │
│   失败：占位符保留（前端 404 优雅降级），RedisStream 重试/DLQ 兜底可重放  │
└──────────────────────────────────────────────────────────────────────┘
```

> **FastTrack 路径（v1.6 严重-1 补齐）**：上图描述的是 Standard 形状（向量写→短事务）。
> 单文档 ≤5MB 的上传默认路由 **FastTrackStrategy**（`EtlFastTrackProperties` 默认
> enabled=true/10docs/5MB，getOrder=0 先于 Standard），其时序相反（BM25 原文行 +
> completeDocument 先行、向量异步后补），短事务形状见 **§6.3.1**——两条路径都必须产出
> manifest/占位符/outbox 消息，只改 Standard 即小 PDF 图片链路整体静默缺失。

### 5.2 与最初草案的差异（评审要点）

| # | 原草案 | 本设计 | 理由 |
|---|--------|--------|------|
| 1 | 前台 `extractContents` ~150ms 级 | 量级预期 2–4s（**非承诺**，M1：以 P1 分段计时实测分布为准，验收统一 §11 的 ≤5s） | `extractContents` 包含 preprocessing（1–2s 串行）+ 逐页提取，150ms 只对小 PDF 成立 |
| 2 | 自写 `PlaceholderMdGen` 生成 Markdown | **子类化** ODL `MarkdownGenerator`（置 `isImageSupported=true` + 覆写 `writeImage`） | 复用标题/表格/阅读顺序全部逻辑，零重写漂移风险 |
| 3 | temp PDF + `ExtractionResult`（内存 contents）交给后台任务上下文 | **`ImageManifest` 持久化到 `document_image` 表**，后台重新下载 PDF、只重跑 `preprocessing` | `ExtractionResult` 无法随消息跨进程序列化；contents 持有期间阻碍前台线程复用；manifest 使编号一致性**由构造保证**（见 #4） |
| 4 | 占位符 (页,序) 与后台遍历各自编号，依赖跨运行提取确定性 | 编号**只在前台发生一次**并随 manifest 持久化，后台按清单逐条执行 | `threads>1` 官方标注 experimental"输出可能略有差异"，跨运行遍历序不应作为正确性依赖 |
| 5 | `@Async` + ODL 锁 | MessageBus 新 topic + ConsumerConfig.concurrency（runner inflight 限并发，§7）+ Redisson documentId 锁 | ThreadLocal 模型下锁非正确性必需（§3.2 推论 3）；`@Async` 重启丢任务与消息可靠性冲突 |
| 6 | 镜像 `closePdfResources` 清理"后台" | 前台**与**后台各自做清理镜像 | `extractContents` 不关资源，前台不清理则每文档泄漏句柄（EXP2' 实锤） |

---

## 6. 详细设计

### 6.1 ParseContext：documentId 传入解析层

现状 `DocumentParser.parse(Resource, String mimeType)` 无文档身份，而确定性图片 key 需要
`documentId`。改动面控制到最小：

```java
// DocumentParser.java 新增（默认方法，其余 5 个 Parser 零改动）
public interface DocumentParser {
    List<Document> parse(Resource resource, String mimeType);   // 保留
    default List<Document> parse(Resource resource, String mimeType, ParseContext ctx) {
        return parse(resource, mimeType);                        // 默认委托
    }
}

public record ParseContext(Long documentId, String bucket, String objectKey, String fileName) {}
```

- `Extractor.extract(bucket, objectKey, mimeType)` 增加重载携带 `documentId`（调用方
  `EtlRouteStrategy` 持有 `EtlCandidate`，直接传 `candidate.documentId()`）；
- 仅 `OpenDataLoaderPdfParser` 覆写三参版本。

### 6.2 前台管线（重构 `OpenDataLoaderPdfParser`）

```java
Config config = new Config();
config.setGenerateMarkdown(false);        // 前台不走 generateOutputs，直写内存 Writer
config.setGenerateJSON(false);
config.setGenerateHtml(false);
config.setGeneratePDF(false);
config.setImageOutput(Config.IMAGE_OUTPUT_OFF);   // 零图片工作（§1.2 根因 2）
config.setThreads(documentProperties.getOdlThreads());  // 默认 max(1, availableProcessors()/2)
// H-C1 机制化（v1.3）：hybrid=off 是全部并发安全的前提（§3.2），不能只靠评审纪律。
// 门面（前台 parser 与后台 consumer 的唯一 Config 构造点）在每次调用前 fail-fast 校验，
// 任何链路启用 hybrid 即抛 IllegalStateException 拒绝执行；启动期无 hybrid 配置项天然满足
if (!Config.HYBRID_OFF.equals(config.getHybrid())) {
    throw new IllegalStateException("H-C1 violated: hybrid mode is incompatible with concurrent extraction (see design §3.2)");
}

// 严重-1 修复：extractContents 必须在 try 保护域内 —— 它不触发 closePdfResources（§3.1/EXP2'），
// 提取中途异常（损坏页/OOM）时若 cleanupMirror 在其外，则该 etl-io 线程确定性泄漏
// PDDocument + ThreadLocal COS 状态，用户重试即重复泄漏直至句柄耗尽。
// （ForkJoinPool 无此问题：processDocument 自身 finally shutdown，`:435-436` 已核实）
ExtractionResult result = null;
try {
    result = DocumentProcessor.extractContents(tempPdf.toString(), config);
    ImageManifest manifest = ImageNumberer.number(result.getContents());   // ① 先编号（按出现，见下）
    String markdown;
    try (StringWriter sw = new StringWriter();
         PlaceholderMarkdownGenerator gen = new PlaceholderMarkdownGenerator(sw, config, manifest, ctx)) {
        gen.writeToMarkdown(result.getContents());                          // ② 占位符注入
        markdown = sw.toString();
    }
    assertPlaceholderIntegrity(markdown, manifest);   // ③ H3 完整性断言（见下，不等则整体失败）
    // ④ Document 组装（现状元数据逻辑不变）+ chunk/document metadata 记 imagePlaceholderCount
    //    （中-7：对账维度的数据基础，见 §6.8）→ 返回
} finally {
    cleanupMirror();                       // §3.3 九步清理镜像 —— 必须在提取线程上执行
    safeDelete(tempPdf);                   // temp 生命周期回归 parse() 内
}
```

**PlaceholderMarkdownGenerator**（唯一自定义 Markdown 逻辑，~30 行）：

> 2.5.5 分发事实（源码已核实）：`write(IObject)` 按 `instanceof` 分发——`ImageChunk` 走
> `writeImage()` 且受 `isImageSupported` 门控（`:132/:150`）；`SemanticPicture` **无条件**走
> 独立的 `writePicture()`（`:148`，原实现因图片文件不存在而静默不输出）。两条路径都要覆写。
> `markdownWriter` 是 protected final 字段（`:51`），子类可直接写。

```java
class PlaceholderMarkdownGenerator extends MarkdownGenerator {
    private final ImageManifest manifest;
    private final ParseContext ctx;

    PlaceholderMarkdownGenerator(Writer w, Config c, ImageManifest m, ParseContext ctx) {
        super(w, c);
        this.isImageSupported = true;      // protected 字段：打开 ImageChunk 分发门控
        this.manifest = m; this.ctx = ctx;
    }

    @Override
    protected void writeImage(ImageChunk image) {          // ImageChunk 路径（嵌入位图/内联图）
        writePlaceholder();                                // v1.5：独立计数器按出现递增（高-2）
    }

    @Override
    protected void writePicture(SemanticPicture picture) { // 本地模式不可达（EXP7 结构发现），hybrid 未来安全位
        writePlaceholder();
    }

    private int seq = 0;   // 生成器侧独立全局计数器（高-2：按出现编号，非对象身份）

    private void writePlaceholder() {
        // 位置对齐查表：第 ++seq 次出现 ↔ manifest 有序清单第 seq 条 —— URL（含 ext）完全由
        // 清单条目派生，无身份映射、无 ext 歧义；两侧同构遍历保证对齐，错位即计数断言失败
        ImageEntry entry = manifest.entries().get(seq++);  // 先记数后查表，0-based
        String url = "/api/documents/%d/images/%s".formatted(ctx.documentId(), entry.urlName());
        // L4：URL 字符集护栏 —— 显式校验并抛异常（JVM 默认不开 -ea，assert 在生产是空操作）。
        //     当前形态 [A-Za-z0-9/.\-] 对 Markdown 安全，绕过 ODL 的 getCorrectMarkdownString
        //     转义成立；一旦 URL 形态变化（query/fragment/中文），此处失败强制走转义路径。
        if (!url.matches("[A-Za-z0-9./\\-]+")) {
            throw new DocumentParseException(ctx.fileName(), "opendataloader",
                "占位符 URL 字符集不变量被破坏: " + url);
        }
        try { markdownWriter.write("![image](" + url + ")"); }
        catch (IOException e) { throw new UncheckedIOException(e); }
    }
}
```

**H3 生成后完整性断言（硬门禁，在返回 Document 之前执行；v1.3 修正计数口径）**：

```java
// writeToMarkdown 吞异常 + 半截输出（源码证实）→ 不能信任"没抛错就是完整的"。
// v1.3 修正：必须按"出现次数"统计而非"含占位符的行数"——writeTable 整行一次输出
// （单元格间仅列分隔符、行尾才换行，MarkdownGenerator:348 实证），表格一行两个含图
// 单元格即同行 ≥2 个占位符；截图密集手册的高频形态。按行计数必偏小 → 断言恒假 →
// 文档确定性无法索引。
private static final Pattern PLACEHOLDER = Pattern.compile("\\]\\(/api/documents/\\d+/images/p\\d+-\\d+\\.[a-z]+\\)");

long placeholderCount = PLACEHOLDER.matcher(markdown).results().count();
boolean hasMissingFallback = markdown.contains("/images/missing");
if (placeholderCount != manifest.size() || hasMissingFallback) {
    // L1（v1.6）：失败信息区分方向——"> manifest" 多为正文伪造了占位符形态的文本
    // （如本系统自身 API 文档转 PDF 自引用 ](/api/documents/…) ）；"< manifest" 多为
    // writeToMarkdown 截断/吞异常。降级开关（中-4）兜底可用性，方向标记让两类根因
    // 在日志一眼可分、不必复现排查。
    String direction = placeholderCount > manifest.size()
        ? "regex-gt-manifest(疑正文伪造)" : "regex-lt-manifest(疑截断)";
    throw new DocumentParseException(fileName, "opendataloader",
        "占位符数量(" + placeholderCount + "/" + manifest.size() + ")不一致[" + direction + "]"
        + "或存在 missing 兜底，拒绝索引残缺文档");
}
```

> **两条遍历的分歧风险（v1.4 修正表述）**：`ImageNumberer` 与 `MarkdownGenerator` 是两条
> 独立遍历——v1.3 声称"两者对图片对象的访问域一致"只在**类型级**成立，容器级门控存在两个
> 已实锤的反例（页眉脚门控、TOC 递归，见 ImageNumberer 一节的 v1.4 修正），numberer 已改为
> `isSupportedContent` 语义的完整镜像。残余风险：ODL 升级引入新的容器级门控/递归差异时，
> 分歧第一现场是断言失败——fail-closed 保护数据正确，但表现为该类文档全部无法索引。
> **中-4 降级档位**：配置 `odlPlaceholderStrict`（默认 `true` 严格失败）；置 `false` 时断言
> 失败降级为"剥离全部占位符、纯文本索引 + 计数 `rag.image.placeholder_integrity_degraded`
> （高优告警）+ 该文档 manifest 行不落库不投递"——完整性与可用性解耦，由运维在 ODL 升级
> 窗口期按文档类型临时启用，修复后回归严格模式。两档位下都不索引"占位符与清单错位"的
> 文本，这是不可协商的底线。

> 断言语义：占位符总数必须等于 manifest 全部条目数且**零** missing 兜底——任何 miss/截断/
> 吞异常都会被这一道整体拦截，宁可文档 ETL 失败重试，不可静默索引残缺文本。

**L2 已知外观问题（记录不修复）**：Writer 构造器路径下 `writeToMarkdown` 收尾日志打印
`"Created null"`（`markdownFileName` 为 null，源码 `:114`）。属 ODL JUL 噪音，不影响正确性；
上线时可通过 JUL level 配置压掉 `MarkdownGenerator` 的 INFO，不为此 fork 补丁。

**ImageNumberer（编号器，前后台共同事实源；v1.4 重定义遍历域）**

> **v1.4 关键修正**：编号器的遍历域必须镜像的是 **`MarkdownGenerator` 的输出域**
> （`isSupportedContent` 门控 ∩ `write`/`writeContents` 递归），而**不是** ODL
> `ImagesUtils.writeFromContents` 的提取域——H3 断言比较的对象是"manifest 条目数 ↔ 占位符
> 出现次数"，两端域不一致即恒假。v1.3 只对齐了类型级、漏了容器级门控，两个已实锤的缺陷：
> ① `isSupportedContent`（`:126`）对 `SemanticHeaderOrFooter` 返回 `includeHeaderFooter`
> （Config 默认 **false**，`:92`，本设计亦显式 false）→ 页眉脚子树（含嵌图，截图密集手册的
> logo 页眉正是目标场景）被生成器整体剪掉、不产占位符，而旧 numberer 无条件递归 → 多计 →
> 带图页眉文档确定性索引失败；② `writeTOC`（`:311`）经 `writeContents` 递归 TOCI 的
> `getContents()` 可产占位符，旧 numberer 不遍历 TOC → 少计。**结论：numberer 是
> `isSupportedContent` 语义的完整镜像，门控与递归缺一不可。**

```
walk(contents, inSupportedContext):
    对每个 content：
      1. 门控（镜像 isSupportedContent）：
         SemanticHeaderOrFooter → includeHeaderFooter=false 时整枝剪掉（含其全部嵌套图）；
                                  =true 时递归 getContents()（本设计固定 false，前台显式 setIncludeHeaderFooter(false)）
         其他类型 → SemanticTextNode / SemanticFormula / SemanticPicture / TableBorder /
                    PDFList / SemanticTOC / ImageChunk(且 isImageSupported=true，占位符
                    生成器子类已置 true) → 受支持
      2. 受支持类型的递归规则（镜像 write 分发）：
         ImageChunk       → 记录 {type=XOBJECT,  page, seq++, bbox, objectKey, xObjectName}
         SemanticPicture  → 记录 {type=PAGE_RENDER, page, seq++, bbox}
         PDFList          → 递归 listItem.getContents()（writeContents 同构）
         TableBorder      → 递归 row.cell.getContents()（跳过跨格重复项：colNumber/rowNumber 对位）
         SemanticTOC      → 递归 toc.getTOCItems()：嵌套 SemanticTOC 继续递归，
                            SemanticTOCI 递归其 getContents()（writeTOC :311 同构）
         SemanticHeaderOrFooter → 见门控
```

- `seq` 为**文档内连续递增**（与 ODL `imageIndex` 语义一致），URL 名用 `(page+1)-{seq}` 双保险；
- **按"遍历出现"记录（v1.5 高-2 修复，取代 v1.4 的 IdentityHashMap 方案）**：同一图片对象被
  内容树多处引用（别名）时，**每次出现各记一条**、各分配 seq——v1.4 的 byIdentity 单值映射
  在别名场景下二次 put 覆盖第一次（manifest 两行、Markdown 两次写 B 的 URL，计数断言
  2==2 通过、A 行成孤儿，**恰好拦不住**）。两侧（numberer 与占位符生成器）各自维护独立
  全局递增计数器，对同一 contents 对象图的同构遍历保证第 k 次出现两侧对齐（EXP6 原型即
  此形态）；别名二次出现计数 `rag.image.alias_occurrence`（WARN，ODL 当前是否产生别名引用
  未验证，计数器先行观测）；
- **中-3 结构发现（EXP7 副产物）**：`SemanticPicture` 仅由 hybrid 转换器构造
  （`DoclingSchemaTransformer:384`、`HancomSchemaTransformer:331` 等），本地模式（H-C1
  hybrid=off）**恒不产生**——manifest 的 `PAGE_RENDER` 条目只来自 **ImageChunk 无 XObject
  key** 的兜底（如内联图，ODL 自身 `createImageFile` 同款回退逻辑）。numberer 保留
  `SemanticPicture` 分支作为 hybrid 未来的死代码安全位，本地模式实际不可达；
- bbox 序列化为 `[leftX, bottomY, rightX, topY]`（double×4）；
- **域一致性由 §8.5 两个造图用例持续锁定**（表格嵌套多图 + 页眉脚嵌图，均动态验证通过），
  ODL 升级引入的任何新分歧表现为断言失败（fail-closed）或降级告警（中-4 开关），不静默错位。

### 6.3 数据模型：`document_image`

```sql
CREATE TABLE document_image (
    id           BIGSERIAL PRIMARY KEY,
    document_id  BIGINT       NOT NULL,
    page_number  INT          NOT NULL,           -- 0-based
    seq          INT          NOT NULL,           -- 文档内连续序号
    img_type     VARCHAR(16)  NOT NULL,           -- XOBJECT | PAGE_RENDER
    bbox         JSONB        NOT NULL,           -- [l,b,r,t]
    object_num   INT,                             -- XOBJECT: ObjectKey.number
    object_gen   INT,                             -- XOBJECT: ObjectKey.generation
    x_object_name VARCHAR(255),                   -- XOBJECT: 资源名（诊断用）
    storage_key  VARCHAR(512) NOT NULL,           -- images/{documentId}/p{page+1}-{seq}.{ext}
    producer_version VARCHAR(64) NOT NULL,        -- L2（v1.6）：前台生成 manifest 时的 ODL 版本戳（如 "odl-2.5.5"），P3 对账检测滚动发布跨版本消费
    status       VARCHAR(16)  NOT NULL DEFAULT 'PENDING',  -- PENDING|UPLOADED|FAILED|SKIPPED
    fail_reason  VARCHAR(512),
    file_size    BIGINT,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    UNIQUE (document_id, seq)
);
CREATE INDEX idx_document_image_doc ON document_image(document_id, status);
```

**事务边界（v1.4 修正前提）与 manifest 传递载体（中-1/中-2）**：

> v1.3 前提被推翻：rag/etl 全包**无任何 `@Transactional`**，"写结果"实为
> `VectorStoreLoader.load`（外部向量库，天然不可入 DB 事务）+ `statusManager.completeDocument`
> （单行更新），"同事务挂靠现成事务"的落点不存在。且 `OutboxMessageBus.send` 的 outbox
> INSERT 会**加入调用方事务（同连接）**（其 `:49` javadoc 明示"调用方不应在持有业务事务时
> 调用"）——因此必须**显式新建短事务**，而非搭便车。

**manifest 载体（中-2，杜绝临场发明第二套惯例）**：`Extractor` 增加类型化重载——

```java
// v1.6（L4）：命名避开 StandardStrategy:186 / FastTrackStrategy:266 既有私有 record
// ExtractOutput（私有 record 无 JVM 冲突，但同名必致混淆——且 FastTrack 集成（§6.3.1）
// 将复用本载体，两类 ExtractOutput 同文件可读性不可接受）
public record ExtractWithManifest(List<Document> documents, List<ImageEntry> imageManifest) {}

// Extractor.java：新重载，默认委托旧签名（imageManifest 为空，兼容非 PDF 链路）
default ExtractWithManifest extractWithManifest(String bucket, String objectKey, String mimeType, Long documentId) {
    return new ExtractWithManifest(extract(bucket, objectKey, mimeType), List.of());
}
```

归属链：`OpenDataLoaderPdfParser` 产出 manifest → `DocumentExtractor.extractWithManifest`
透传（**不进 `Document.metadata`**，避免向量库元数据被清单污染的剥离负担）→
`EtlRouteStrategy` 在下方短事务内执行 DELETE+INSERT。向量写（外部系统）发生在短事务
**之前**、按现状顺序不变。

**短事务（TransactionTemplate，显式新建，对齐 OutboxMessageBus `:49` 的连接语义）**：

```
[向量库写入（外部，现状顺序，不入本事务；FastTrack 无此段，形状见 §6.3.1）]
→ TransactionTemplate.execute:
    ① statusManager.completeDocument(...)          -- 状态单行更新（SSE 事件 afterCommit，见 H2）
    ② DELETE FROM document_image WHERE document_id = :id   -- 幂等重建（v1.3 定义，保留）
    ③ INSERT INTO document_image (...) VALUES (...);        -- 新 manifest 全量（含 producer_version）
    ④ outboxMessageBus.sendInTransaction(envelope) -- 事务内 INSERT（同连接）+ 注册
                                                     afterCommit → tryImmediate（H1 契约，见下）
```

- ①–④ 原子：要么索引状态+清单+outbox 行全可见，要么全回滚；
- **高-1 修复（即时投递先于提交的丢消息窗口）**：实测 `send()` = `insert`（加入调用方
  **未提交**事务）+ `tryImmediate`（`:98-102`，有界线程池投递**内存态**消息）——若直接在
  事务内调 `send()`，消费者可在提交前收到消息 → `findPending` 读已提交快照为空 → 正常
  返回 ACK → 消息被消费、outbox 行按"已投递"删除 → 提交后的新 PENDING 行失去唯一驱动器，
  滞留至超龄告警。**本设计不调用 `send()`**，改为事务内 INSERT + afterCommit 触发即时
  投递——投递时 manifest 必已提交可见；afterCommit 投递失败则行留 relay 回收（行已随事务
  原子提交，relay 兜底不丢）。这也回归了 `:48-50` javadoc"不应在持有业务事务时调用"的
  契约本意；
- **H1（v1.6：④依赖的能力均为 private，扩面契约落定）**：上述"事务内 INSERT + 提交后
  即时投递"依赖的 `insert()`（`:194-219`，8 字段构造语义：payload 编码 / payloadType /
  tag / dedupKey / hashKey / headers JSON / status+attempts / nextRetryAt 与 createdAt、
  updatedAt **同源时刻**）与 `tryImmediate()`（`:134`）现均为 private。**禁止绕过直用
  OutboxMapper 复刻构造逻辑**——relay 的 envelope 重建按 payload_type/dedupKey/hashKey
  反序列化（`OutboxRelay:265-279`），畸形行 = 投递死信。契约：在 `OutboxMessageBus` 上
  新增**唯一公共入口**，两个 private 方法原样复用、零构造逻辑漂移：

  ```java
  /**
   * 事务内投递：在当前事务中 INSERT outbox 行（复用 insert() 全部构造语义，同连接），
   * 并注册 TransactionSynchronization.afterCommit → tryImmediate。
   * 前置：调用方必须持有活动事务，否则 IllegalStateException fail-fast
   * （无事务时 insert 走自动提交 + afterCommit 永不触发 = 行滞留至 relay 的静默错误）。
   * 返回 outbox 行 ID（与 send() 一致）。afterCommit 投递失败 → 行留 relay 兜底。
   * 归属：OutboxMessageBus 具体类（图片 publisher 直接注入具体类型——@Primary bean 即
   * 其实例），不污染 MessageBus SPI 接口。
   */
  public String sendInTransaction(MessageEnvelope<?> message)
  ```

  **实锤陷阱（禁用现成方法）**：`sendAfterCommit` 在 OutboxMessageBus 装饰器上被覆盖为
  直接 `send()`（`:239-243`）——持有事务调用它 = insert + **立即** tryImmediate，高-1
  窗口原样存在；delegate（RedisStreamMessageBus `:292-310`）的正确 afterCommit→send 语义
  被装饰器遮蔽，本场景不可用（该覆盖对既有无事务调用方无影响，不在本期修正范围）；
- **H2（v1.6：①的事件时序）**：`EtlStatusManager.completeDocument` 在
  `executeWithoutResult` 返回后**立即** `publishStatusEvent`（`:62-71`）；其
  TransactionTemplate 默认 REQUIRED → 并入本短事务后，`DocumentStatusChangedEvent` 将在
  外层提交前发布——事务回滚时 UI 已收到 COMPLETED **假状态**。修复：EtlStatusManager 的
  **全部**事件发布点（completeDocument / failDocument / markVectorFailed 的
  DocumentStatusChangedEvent，及 failDocument 的 EtlFailedEvent）收敛到统一**事务感知
  helper**：`TransactionSynchronizationManager.isSynchronizationActive()` 为真时注册
  afterCommit 发布、否则立即发布。无事务的既有调用路径（两策略现状）行为不变；
- **失败窗口（中-1 明示；仅 Standard 路径——FastTrack 向量写在事务之后，无此窗口，见
  §6.3.1）**：向量已写（不可回滚）但本事务回滚 → 索引文本含占位符、清单
  零行、无消息。对策（v1.5 中-7 修正可行性）：**摄取时在 chunk/document metadata 记
  `imagePlaceholderCount`**（§6.2 步骤 ④），P3 对账维度改为 **"metadata 的
  imagePlaceholderCount > 0 但 `document_image` 零行"的元数据比对**——不依赖对向量库
  chunk 文本做正则扫描（该能力/成本未论证，纸面设计），命中即计数
  `rag.image.manifest_missing` 告警并重新触发该文档 ETL；

**重建与在途消费者的并发竞争（高-2，条件更新协议）**：

> 实锤的竞争窗口：前台重试持 `smart-rag:etl:lock:{id}`，消费者持 `smart-rag:image:lock:{id}`，
> 两锁互不互斥——消费者 `findPending` 读到旧快照 → 前台事务 DELETE+INSERT 新清单并投新消息
> → 消费者继续按旧快照上传、对已删行 `markUploaded`（update-by-id 0 行，静默）。
> 批量导入积压 + 慢消费期间用户重试即可命中；后果限于 MinIO 留下跨代孤儿对象 + 无效上传。

**修复（方案②条件更新为主，不引入锁耦合）**——消费侧所有行状态迁移一律条件化：

```sql
UPDATE document_image SET status='UPLOADED', file_size=:n, updated_at=now()
 WHERE id=:id AND status='PENDING';      -- 0 行 = 代际失效（行已被重建删除/改态）
```

- 任何一次迁移影响 0 行 → **判定代际失效，立即中止本批**（已上传的跨代对象成为孤儿，
  由 §6.8 三方对账清理；新清单由前台投递的新消息驱动，不丢工作）；
- 方案①（前台 DELETE+INSERT 前短暂获取 `image:lock:{id}`）作为可选加固不采纳为正确性
  依赖——锁只是减少无效工作，条件更新才是正确性边界（与 ETL 锁的 best-effort 定位一致）。
- MinIO 侧同 key 覆盖（重解析后同 (page,seq) 位置的新图覆盖旧对象）；图片数变少时多出的
  旧对象、以及消费者代际失效中止后已上传的跨代对象，统一由 §6.8 **三方对账**清理兜底——
  **不用在删除路径做前缀清理**（那是文档删除的职责，重试场景交给对账，避免删除路径与
  消费路径的并发竞争）；
- **消息不设 dedupKey（v1.6 严重-2 修复，推翻 v1.5 的 dedupKey=documentId）**：
  `RedisStreamMessageBus.subscribe` 对所有 handler **无条件**包 `IdempotentHandler.wrap`
  （`:226-227`；`idempotent.enabled` 配置经全库复核**无代码消费者**、不 gate 此包装——
  唯一豁免是消息不带 dedupKey，`IdempotentHandler:46-49`）。SETNX **先标记后执行**，
  key = `messaging:idempotent:{topic}:{dedupKey}`，TTL 默认 900s（application.yml 实配
  **90000s**）。若 dedupKey=documentId：进程在"SETNX 已标、handler 未完成、消息未 ACK"
  窗口被杀（**验收 3 正是此场景**）→ 重投被判重复**静默跳过且正常返回** → SIMPLE 模式
  自动 ACK → 消息永久消费完毕、PENDING 行滞留（P2 阶段无补偿扫描，仅超龄告警兜底）——
  崩溃重投恢复承诺被击穿，且这是 IdempotentHandler javadoc 自述的 at-least-once 权衡，
  不是意外行为。本 topic 的消费幂等本来就是**行状态条件更新**（`WHERE status='PENDING'`），
  双投递由 image:lock + 条件更新 + findPending 空转退出收敛，不需要 SETNX 去重。
  **修复：构造 MessageEnvelope 时 dedupKey=null**（wrap 对 null/空直通，零平台改动）；
  hashKey=documentId 保留（随 outbox 行持久化、诊断用；总线不分区，无路由语义）。
  披露：`rag_index_document` 现沿 dedupKey=documentId 先例
  （`EtlDispatchServiceImpl:135-137`），同型崩溃窗口对其同样存在（文档停留 PROCESSING
  由用户重试兜底）——既有行为不在本期范围，但新 topic **不得复制该先例**。重试期间
  旧消息与新消息最终收敛到同一份 manifest（消费者按行状态幂等，旧消息触发时新清单要么
  已在处理、要么已终态，直接 ACK）。

#### 6.3.1 FastTrack 路径集成（v1.6 严重-1——不集成 = 小 PDF 图片链路整体静默缺失）

> 实锤前提（源码复核）：`EtlFastTrackProperties` 默认 `enabled=true / maxDocCount=10 /
> maxTotalSize=5MB`，`FastTrackStrategy.getOrder()=0` 先于 Standard 判定——**单文档
> ≤5MB 的 PDF 上传（最常见用户行为）恒路由 FastTrack**；其 `extractAll` 调两参
> `extractor.extract()`（`:240`，无 documentId → 无 ParseContext → 无 manifest/占位符）；
> 时序与本节短事务形状相反：`writeBm25Row` → `completeDocument(id, 0)`（`:119-120`）
> 先于异步向量写入（`asyncVectorize :127`）；BM25 原文行 `insertFastTrackRow` 直写
> content，metadata 无 imagePlaceholderCount 挂靠点。只改 Standard = 大文档测试通过、
> 线上小文档功能全缺。**决策：集成，而非把 PDF 排除出 FastTrack**——排除会使最常见
> 的小文档失去 BM25-first 能见度（COMPLETED 语义回退为等待全量向量化），用户可见的
> 行为回退不可接受。

集成改动（三处，全部复用本节既有机制，不发明第二种事务形状）：

1. `extractAll` 改调 `extractWithManifest(bucket, objectKey, mimeType, documentId)`
   （§6.3 载体），manifest 随 `ExtractWithManifest` 返回进入下方短事务；
2. **FastTrack 短事务**（与 Standard 同构、按 BM25-first 时序适配——向量写在事务之后
   异步发生，故不存在 Standard 的"向量已写、清单回滚"失败窗口，`manifest_missing`
   对账维度对 FastTrack 天然不适用）：

   ```
   → TransactionTemplate.execute（循环内每文档一个）:
       ① writeBm25Row(content, metadata += imagePlaceholderCount)
          -- 原文行含占位符文本；从自动提交并入短事务（单行 INSERT，隔离时长可忽略）。
             metadata 组装点 = VectorStoreMapper.insertFastTrackRow default 方法
             （:116-125，现有 map 增一个键）；content 携带占位符，若事务回滚则行不可见，
             不产生"可检索文本存在而 manifest 零行"的中间态
       ② statusManager.completeDocument(id, 0)   -- REQUIRED 并入本事务；事件 afterCommit（H2）
       ③ DELETE + INSERT document_image          -- 幂等重建，同 Standard（含 producer_version）
       ④ outboxMessageBus.sendInTransaction(...) -- 同 H1 新 API，消息不设 dedupKey（严重-2）
   → 事务提交后：asyncVectorize（现状不变：transform → load → updateChunkCount →
      deleteFastTrackRows；启动点移到 execute() 返回之后，避免与未提交的 BM25 行竞争）；
      异步分块的 ChunkMetadataEnricher 同步记 imagePlaceholderCount（计数自
      manifest.size()，随异步闭包传递——中-7 对账数据基础）
   ```

3. **幂等/重试**：ETL 消息重投 → `extractWithManifest` 重跑 → 短事务 DELETE+INSERT
   幂等重建 + 新 outbox 消息；消费侧条件更新协议（高-2）与代际失效中止对新旧路径无差别。
   失败路径：短事务回滚 → 现状 catch → `failDocument`（其事件经 H2 修复后同样 afterCommit
   安全）→ 文档 FAILED，无残缺索引。

**验收映射**：验收 3/4/5 以 **FastTrack（≤5MB 用例）与 Standard（>5MB 用例）双路径**
分别执行（§11），杜绝"只测 Standard"的盲区。

消息体（v1.3：移除 `password` 字段——当前恒 null，留在消息体里是未来明文泄漏埋点；
启用加密 PDF 支持时再加回并评估传输/持久化加密）：

```java
public record ImageExtractJob(Long documentId, String bucket, String objectKey,
                              String fileName) implements Serializable {}
```

> manifest **不进消息体**——消费者从 `document_image` 表读 PENDING 行，消息仅作触发器。
> 这样 DLQ 重放天然幂等（按行状态过滤）。

**存储 key（确定性，v1.3 收紧为构造保证）**：`images/{documentId}/p{page+1}-{seq}.{ext}`，
`ext` **仅由 img_type 决定，无条件分支**：

| img_type | ext | 依据 |
|----------|-----|------|
| `XOBJECT` | `png` | PNG 原生支持 alpha，任何嵌入图都无损承载 |
| `PAGE_RENDER` | `jpeg` | 渲染时**统一将 alpha 压平到白底**（页面渲染本无透明语义），无需按透明度分支 |

> v1.2 版曾写"PAGE_RENDER→jpeg 除非透明度需要"——该条件只有后台渲染出 BufferedImage 后
> 才能求值，而 storage_key 与占位符 URL 在**前台写 manifest 时已固化**，两端推断可能分叉；
> 按 L3 规则（ext 不一致按 404）未来端点上线后这部分图永久 404，修复需重建索引，违反
> "端点按契约实现、无需重建索引"的核心承诺。删除条件子句后，ext 成为前台可独立求值的
> 纯函数，确定性由构造保证。同 key 覆盖上传 = 幂等。

### 6.4 后台消费者 `ImageExtractConsumer`

```java
// SmartLifecycle 消费者，模式对齐 EtlDocumentConsumer（TOPIC = "rag_extract_images"）
// 线程模型（v1.6 中-3 修正，取代 v1.5 的"自建 image-io-N 池"写法）：
//   不新增自建线程池——线程由 RedisStreamConsumerRunner 提供（SIMPLE 模式）：
//   · redis-receive-{topic} 单线程 XREADGROUP 拉取；饱和背压 = 阻塞于
//     inflight semaphore.acquire()（消息停留在 stream 未投递、不进 PEL，与 ETL 消费
//     同一背压语义）
//   · redis-process-{topic}-N 处理池（core=max=concurrency，队列 readBatch*2，
//     满载 routeToRetry 延迟重投——P0-1 满载不留 PEL 卡死）
//   odlImageConcurrency 1:1 映射 ConsumerConfig.concurrency（§6.9 配置面）：
//   处理池线程数 = in-flight 许可数 = ODL 渲染内存并发上界（§3.2 推论 3 的落点）。
//   与 ETL 消费隔离靠 per-topic 独立 runner/池实例（redis-process-rag_extract_images-*
//   与 rag_index_document 的池互不共享线程）。consume() 内不再自设 Semaphore——
//   v1.5 写法与 runner inflight 双重限流、且阻塞的是处理线程，语义冲突已删。
//
// ack/nack 契约映射（v1.4 低-5③）：本伪代码的 ack(job) = MessageHandler 正常返回
// （RedisStream SIMPLE 模式自动 ACK，对齐 EtlDocumentConsumer 的异常驱动契约——
// 该消费侧无显式 ack API）；nack(job) = 向 handler 外抛异常 → invisibleDuration 后重投，
// 重试预算由 RetryPolicy 管，耗尽进 DLQ。实现不得自行发明第二套 ACK 机制。
public void consume(ImageExtractJob job) {
    // （v1.6：v1.5 的 semaphore.tryAcquire(30s) 已删——并发上限由 ConsumerConfig.concurrency
    //  表达，见上；本方法整体跑在 redis-process-{topic}-N 处理线程上）
    RLock lock = null;                                             // 中-8b：获取锁的调用也置于 try 内，
    boolean locked = false;                                        // tryAcquire 与 try 之间不留可抛间隙
    List<DocumentImageRow> rows = List.of();                       // L1：声明在 try 之前
    Path tempPdf = null;
    try {
        lock = redisson.getLock("smart-rag:image:lock:" + job.documentId());
        locked = lock.tryLock(30, -1, SECONDS);                    // 获取失败 → 异常驱动重投（映射 nack）
        if (!locked) { throw new RetryableException("document-lock-contended"); }

        // 严重-2a：校验"存在且可处理"——SUPERSEDED 视同已删除（新版本=新 documentId，旧 id 的
        // 原文件已被 supersede 清理，existsById 会误放行 → 下载 404 烧预算误报 dead）
        if (!documentRepository.isProcessable(job.documentId())) {
            repository.deleteByDocumentId(job.documentId());       // 终态文档：清行退出
            cleanupImagePrefix(job.documentId());                  // 前缀对象 best-effort 清理（归 §6.8 同构）
            return;                                                // → 正常返回 = ack
        }
        rows = repository.findPending(job.documentId());           // 中-6：ORDER BY page_number, seq
        if (rows.isEmpty()) { return; }                            // （保证换页清理按批生效而非逐行）

        // 低-5②：downloadToTemp 契约 = 自身失败时清理已建的部分文件再抛出（finally 的
        // tempPdf!=null 门只负责成功路径，半途失败窗口由 helper 内部 try/finally 封闭）
        tempPdf = downloadToTemp(job);

        Config cfg = OdlConfigs.background();                      // 中-8a：统一门面构造器（含 H-C1 fail-fast），
        DocumentProcessor.preprocessing(tempPdf.toString(), cfg);  // 禁止直构 new Config() 绕过校验
        var utils = StaticContainers.getImagesUtils();             // M7：懒实例；由清理镜像第 2 步关闭
        if (utils == null) {                                       // 低-2：懒创建失败显式归类瞬时（资源压力）；
            throw new RetryableException("images-utils-init-failed");  // 粘性标志经字节码证实会被清理镜像的
        }                                                          // updateContainers(null) 重置（偏移 199-212），
                                                                   // 不存在该线程永久 Retryable 循环
        int lastPage = -1;
        for (DocumentImageRow row : rows) {
            if (row.pageNumber() != lastPage) {
                utils.clearRenderedPages();                        // H2：换页显式清缓存（内存上界 = 1 页）
                lastPage = row.pageNumber();
            }
            if (row.seq() > imageProperties.getMaxPerDoc()) {       // L3（v1.6）：单文档对象预算
                row.markSkipped("doc-image-budget"); continue;     // 行保留终态、不产生存储对象；
            }                                                      // manifest/占位符双射不受影响（H3 比
                                                                   // 较的是 manifest 全量），预算调大后
                                                                   // 重置该批 PENDING 重投即可补传
            if (row.imgType() == XOBJECT) {
                // 中-4：解码前从 XObject 字典 Width/Height 预判（经 streamInfo.xObjectName 定位页资源，
                // 读字典字段不解码像素），病态大图（如 30000×30000）在整图进堆前拦截
                if (xoDimensionsExceed(row, maxBytes)) { row.markSkipped("max-bytes-exceeded"); continue; }
            }
            BufferedImage img;
            try {
                img = switch (row.imgType()) {
                    case XOBJECT     -> utils.getXObjectImage(row.pageNumber(),
                                             new ObjectKey(row.objectNum(), row.objectGen()));
                    case PAGE_RENDER -> utils.getPageSubImage(row.bbox(), dpi);  // EXP7 已验证；alpha 压平白底
                };
            } catch (DecodeUnsupportedException e) {               // 低-3：JPX(JPEG2000)/CMYK/JBIG2 等
                row.markSkipped("decode-unsupported", e);          // 确定性解码失败 → 结构性终态，
                continue;                                          // 不入重放烧预算（分类器见下表）
            }
            if (img == null) { row.markSkipped("unresolvable-xobject"); continue; }
            if ((long) img.getWidth() * img.getHeight() * 3 > maxBytes) {   // 低-4：解码后像素复核
                row.markSkipped("max-bytes-exceeded"); continue;   // （兜底：字典预判不可得时仍拦得住）
            }
            byte[] bytes = encode(img, row.ext());                 // 当页编码后即弃（共享父光栅）
            if (bytes.length > maxBytes) {                         // 编码后精确复核（压缩率高时兜底）
                row.markSkipped("max-bytes-exceeded", bytes.length); continue;
            }
            fileStorage.upload(bucket, row.storageKey(), new ByteArrayResource(bytes), row.mime());
            // 高-2：条件更新 —— 0 行 = 代际失效（行已被前台重建删除），立即中止本批
            if (!row.markUploadedConditionally(bytes.length)) {    // UPDATE ... WHERE id=? AND status='PENDING'
                throw new GenerationInvalidException("manifest-rebuilt-during-consume");
            }
        }
    } catch (GenerationInvalidException e) {
        // 高-2：中止本批。旧行已被前台重建事务删除，无需 reset；已上传跨代对象归 §6.8 对账。
        // 中-2：ACK 前复查——若新清单 PENDING 行 >0 且其驱动消息已丢（高-1 窗口），静默 ACK 会
        // 让其滞留到超龄告警；此时抛 Retryable 让本消息重投接管，而非依赖"必有新消息"
        if (repository.countPending(job.documentId()) > 0) {
            throw new RetryableException("generation-invalid-but-pending-remains");
        }
        return;                                                    // 新清单为空或已有新消息在途 → ack
    } catch (Exception e) {
        // M6 + 中-1：瞬时 → 回置仅限非终态行（条件化：WHERE status='PENDING'，终态
        // UPLOADED/SKIPPED/FAILED 不可回退——无条件按 id 回置会把已终态行拉回重传，破坏状态机）
        repository.resetUnfinishedToPending(rows, sanitize(e));
        throw new RetryableException(sanitize(e));                 // 映射 nack：重试预算 → DLQ → FAILED 终态
    } finally {
        if (tempPdf != null) { cleanupMirror(); safeDelete(tempPdf); }
        if (locked && lock.isHeldByCurrentThread()) lock.unlock();
        // （v1.6：semaphore.release() 已随自设 Semaphore 一并删除——并发由 runner inflight 管理）
    }
}
```

**M6 终态判定语义（v1.4 收敛版）**：

| 情形 | 行状态 | 依据 |
|------|--------|------|
| `getXObjectImage` 返回 null | `SKIPPED`（`unresolvable-xobject`） | 结构性：重放结果不变 |
| 解码抛 `DecodeUnsupportedException`（JPX/CMYK/JBIG2 等格式不支持，分类器按异常类型/消息映射，未知默认瞬时） | `SKIPPED`（`decode-unsupported`） | 结构性：确定性异常，重放必同败 |
| 像素预判或编码后 `> odlImageMaxBytes` | `SKIPPED`（`max-bytes-exceeded`） | 结构性：重放结果不变 |
| `seq > odlImageMaxPerDoc`（v1.6 L3 单文档对象预算） | `SKIPPED`（`doc-image-budget`） | 结构性：行保留、仅不产生存储对象；预算调大后重置 PENDING 重投可补传 |
| 行条件更新影响 0 行 | 本批中止（`GenerationInvalid`），不判行死 | 高-2：代际失效，新消息接管新清单 |
| 其他异常（下载/预处理/上传/资源压力） | 行回置 `PENDING` + 异常驱动重投 | 瞬时：重放可能成功 |
| 重试预算耗尽（DLQ 后仍失败） | `FAILED`（终态）+ `rag.image.extract_dead` | 防无限重放 |

> `DecodeUnsupportedException` 为本项目分类器类型：包装 PDFBox `ImageIO` 层对
> JPEG2000/CMYK 变体等的不支持异常（按异常类型与消息特征映射，白名单式；未匹配的一律
> 走瞬时通道——宁可多耗一次重试，不把瞬时故障误判死刑）。

要点：

- **重新下载而非传递 temp 路径**：多实例部署下消费消息的实例不一定是产生消息的实例；
- `getXObjectImage` 优先直解原始嵌入图（无损、快）；`SemanticPicture` 才走渲染；
- **超龄告警随 P2 上线（M2）**：PENDING 行 `created_at` 超过阈值（默认 15 分钟）计数
  `rag.image.extract_stale` 并 ERROR 日志——outbox 投递失败到 P3 扫描上线之间的窗口内，
  滞留状态可观测。补偿**扫描/重投**动作留 P3（启动时 + 定时，将超龄 PENDING 且文档仍存在
  的行重新投递，终态化超限行）。

**后台积压可观测性（v1.3 随 P2 补齐，先行指标而非滞后告警）**：单车道消费
（`odlImageConcurrency=1`）面对批量导入必然积压，仅 15 分钟超龄计数是滞后信号。随 P2 增加：

| 指标 | 类型 | 语义 |
|------|------|------|
| `rag.image.pending_total` | Gauge（按 status 维度） | PENDING 积压总量——批量导入后持续上升即为积压 |
| `rag.image.pending_oldest_seconds` | Gauge | 最老 PENDING 行年龄——比 15 分钟阈值更早暴露停滞 |
| `rag.image.consume_seconds` | Timer | 单批消费耗时分布——吞吐基线，容量评估依据 |
| `rag.image.extract_skipped` | Counter（`fail_reason` tag） | 结构性终态独立计数（v1.3）——大面积 SKIPPED
  （如某类 PDF 对象解析缺陷）不再混在 dead/stale/orphan 之外不可见 |

### 6.5 图片读取端点（v1.2：本期不做，仅固化占位符 URL 格式契约）

**范围决策**：本期只做存储（图片进 MinIO + `document_image` 清单落库），**不实现任何读取
端点**，图片不返回前端。原 v1.1 的流式代理端点设计（鉴权 → 行查询 → `FileStorageService`
流式代理）整体顺延，实现时对齐 `document-original-file-preview-download.md` 的安全约束
（bucket/objectKey 不出服务层、权限先行、不引入 presigned 外链）。

**本期保留的唯一契约——占位符 URL 格式**（随 Markdown 进索引后即固化为长期文本形态，
未来端点按此契约实现即可渲染，无需重建索引）：

```
![image](/api/documents/{documentId}/images/p{page+1}-{seq}.{ext})   # 相对路径，不泄露 bucket
```

端点实现期（另期立项）必须落地的两条评审结论，在此存记备查：

- **H4（缓存策略）**：端点经鉴权，响应体禁止被共享缓存（网关/CDN/企业代理）跨用户复用，
  `Cache-Control: private, max-age=31536000, immutable`（仅浏览器缓存）；CDN 加速需求改走
  签名 URL 方案另议；
- **L3（ext 校验）**：请求 URL 的 ext 与行内 storage_key 实际扩展名不一致按 404 处理，
  Content-Type 一律取自行内 mime 字段，不信任 URL 后缀。

端点未实现期间，占位符在检索结果中为惰性文本（无可达 URL），对 LLM 上下文而言是低信息量
token（与非目标一致，不额外处理）。

### 6.6 配置项（`DocumentProperties` 扩展）

| 属性 | 默认 | 说明 |
|------|------|------|
| `odlThreads` | `max(1, availableProcessors()/2)` | 前台**每文档**逐页并行度；多文档并发下的总量预算与联动约束见 §7（v1.6 中-4）；容器内有 cgroup 时被自动 clamp |
| `odlImageConcurrency` | `1` | 后台图片提取并发——**1:1 映射 ConsumerConfig.concurrency**（v1.6 中-3：runner 处理池线程数 = in-flight 许可数，不再自建池/自设 Semaphore，§6.4/§6.9） |
| `odlImageRenderDpi` | `144` | PAGE_RENDER 渲染 DPI（2.5.5 `getPageSubImage(bbox, dpi)`） |
| `odlImageJpegQuality` | `0.85` | 渲染图 JPEG 质量（控制 MinIO 存储体积） |
| `odlImageMaxBytes` | `20MB` | 单图上传上限（编码前按像素规模 w*h*3 预判 + 编码后精确复核双闸） |
| `odlImageMaxPerDoc` | `500` | 单文档图片对象预算（v1.6 L3）：消费侧 `seq` 超限行 SKIPPED(doc-image-budget) 不上传；manifest/占位符双射不受影响（§6.4/§6.8） |
| `odlPlaceholderStrict` | `true` | H3 断言失败档位：true=严格失败（fail-closed）；false=降级剥离占位符纯文本索引 + `rag.image.placeholder_integrity_degraded` 高优告警（中-4，ODL 升级窗口期运维开关） |

### 6.7 失败模式与降级矩阵

| 故障点 | 行为 | 影响 | 恢复 |
|--------|------|------|------|
| 前台 extractContents 异常 | 文档 ETL 失败（现状语义） | 无索引 | 用户重试（现状） |
| 占位符数量 ≠ manifest（H3） | 前台整体抛 `DocumentParseException`，文档 ETL 失败 | 无索引（拒绝残缺文本） | 用户重试；持续失败进运维排查 |
| outbox 投递失败 | 计数 `rag.etl.publish_failed`（对齐现状） | 图片滞留 PENDING | P2 起超龄告警可观测；P3 扫描重投 / 手动重投 |
| 后台下载/预处理/解码/上传异常 | 未完成行回置 PENDING，RedisStream 重试 → DLQ | 图片不在 MinIO（占位符为惰性文本，端点未实现，无 404 语义） | 重放消息，行级幂等（M6：不判死） |
| `getXObjectImage` 返回 null（结构性） | 行 SKIPPED（终态） | 该图无存储对象 | 不可恢复 |
| 重放超限（DLQ 后仍失败） | 行 FAILED（终态）+ `rag.image.extract_dead` | 相关图无存储对象 | 运维介入 |
| 消费者进程崩溃 | 行停留 PENDING | 图片暂缺 | RedisStream 未 ACK 重投（**前提：消息不设 dedupKey，§6.3 严重-2**——SETNX 幂等层对带 key 的重投会判重静默 ACK） |
| 文档被删除 **或被 supersede（严重-2：新版本=新 id，旧文档 SUPERSEDED 终态）** | 消费前校验 isProcessable：清行 + 清前缀对象 best-effort，正常 ACK | 旧文档图片资产回收 | 同删除路径；best-effort 失败归 §6.8 对账 |
| 行条件更新 0 行（前台重建清单，高-2） | `GenerationInvalid` 中止本批；复查 PENDING>0 则重投不 ACK（中-2） | 跨代已传对象成孤儿 | 新消息驱动新清单；§6.8 对账清理 |

### 6.8 图片孤儿清理（M3，对位 `OrphanChunkCleaner` / `DirectUploadOrphanCleaner`；v1.4 扩为三方对账）

**同步路径（v1.4 措辞修正）**：`DocumentLifecycleService.delete` 及 supersede 路径均**无
`@Transactional`**——各步骤（实体/向量/MinIO/DB 行）本就是独立的 best-effort 顺序步。图片
清理作为**又一步 best-effort** 追加，不引入新的事务语义：

1. `DELETE FROM document_image WHERE document_id = ?`（文档删除路径；supersede 路径见下）；
2. `FileStorageService` 按前缀列出 `images/{documentId}/` 对象批量删除
   （ListObjects+DeleteObjects）；
3. 任一步失败不阻断删除主流程，计数 `rag.image.orphan_clean_failed` 告警，等对账兜底。

**supersede 路径（v1.5 严重-2 修正——v1.4 前提与代码相反）**：实读
`DocumentSupersedeService.supersedeOldVersion:301-309` + `RagDocumentMapper.updateSuperseded:46`：
**新版本 = 新 documentId**；旧文档标记 `SUPERSEDED` 终态（行不删）→ 删实体索引 → 删向量 →
删旧原文件。因此旧 documentId 的图片资产处置与删除路径**同构**，不应对账留置：

- `supersedeOldVersion` 在 `cleanupStorageFile(oldDocId)` 之后追加 best-effort 步骤：
  ① `DELETE FROM document_image WHERE document_id = oldDocId`；② 清理
  `images/{oldDocId}/` 前缀对象——与删除路径完全同一实现；
- 消费端校验用 `isProcessable`（存在且状态非 SUPERSEDED/DELETED，见 §6.4），替换时在途/
  积压的旧消息命中 SUPERSEDED 即清行清对象退出（杜绝旧文件已删 → 下载 404 → 烧预算 →
  `extract_dead` 误告警的每次替换必现场景）。

**对账兜底（P3，v1.5 补 SUPERSEDED 档位）**：定时任务比对三方状态——

| 比对维度 | 检测 | 动作 |
|---------|------|------|
| 文档表 ↔ `document_image` | 文档不存在**或状态为 SUPERSEDED** 但行残留 | 删行（对象归下一行处理） |
| `document_image` ↔ MinIO 前缀对象 | **活文档**的前缀下存在未被任何行引用的对象（重解析图片缩水、高-2 代际失效中止后已上传的跨代对象；SUPERSEDED 文档的前缀整体未引用） | 删除多余对象（**删除前以当前行快照二次核对引用**，防与在途消费者竞争） |
| 文档表 ↔ MinIO 前缀对象 | 文档不存在/SUPERSEDED 但对象残留 | 删对象（`DirectUploadOrphanCleaner` 同模式：先扫描后删除） |
| document metadata ↔ `document_image` | **`imagePlaceholderCount > 0` 但该 documentId 零行**（§6.3 失败窗口：向量已写、manifest 事务回滚；v1.5 中-7：摄取时写入 metadata 的计数，元数据比对，不扫 chunk 文本） | 计数 `rag.image.manifest_missing` 告警 + 重新触发该文档 ETL（重建清单） |
| `document_image.producer_version` ↔ 当前 ODL 版本（v1.6 L2） | **UPLOADED 行的 producer_version ≠ 当前版本**（滚动发布窗口：旧实例前台生成的 manifest 被新实例后台消费，bbox/DPI 渲染语义跨版本漂移无检测；影响限于个别图片渲染偏差，契约测试只锁同版本像素一致） | 计数 `rag.image.version_skew`（告警级）；重渲染由运维决策——重新触发文档 ETL 即全量刷新版本戳（DELETE+INSERT 重写） |

- 与在途消费者的竞争防护：对账删除对象前重新读行快照（对象引用判定以提交后的行为准）；
  消费者侧条件更新（高-2）保证对账误删不会造成行状态错位（行已终态则上传无效但不脏）；
- documentId 为自增主键不复用，无 TOCTOU 误删他代的风险，对账仅防各 best-effort 路径的
  部分失败。
- **容量与生命周期（v1.6 L3）**：对象生命周期 = 随文档删除/supersede 全量回收（本节
  同步路径 + 对账兜底），**无对象级 TTL**；总量预算由 `odlImageMaxPerDoc`（单文档对象
  数上限，消费侧超限行 SKIPPED 不上传）+ `odlImageMaxBytes`（单图上限）双闸约束，极端
  上界 = 活文档数 × odlImageMaxPerDoc × odlImageMaxBytes（默认 500×20MB = 10GB/文档，
  实际由真实图片密度决定）；P3 对账附带输出对象总数/总字节 gauge，作为 MinIO 容量规划
  输入。

### 6.9 平台配置面（v1.6 中-2：新 topic 的配置清单与决策）

**application.yml 新增**（模式对齐 `app.etl.consumer.*` / `EtlConsumerProperties`，新增
`ImageConsumerProperties`，prefix `app.etl.image`）：

```yaml
app:
  etl:
    image:
      consumer:
        topic: ${ETL_IMAGE_CONSUMER_TOPIC:rag_extract_images}
        group: ${ETL_IMAGE_CONSUMER_GROUP:image-group}
        batch-size: 1          # 逐条消费：消息仅是触发器（manifest 在 DB），批语义无收益
        invisible-duration: ${ETL_IMAGE_INVISIBLE_DURATION:30m}
      # concurrency 不单独配置——odlImageConcurrency（§6.6）1:1 映射 ConsumerConfig.concurrency
```

**`messaging.ordered-topics` 决策：不纳入 `rag_extract_images`**。依据：

- 该白名单经全库复核**当前无代码级消费者**（仅 `MessagingProperties.orderedTopics` 声明 +
  `EtlDocumentConsumer:35-36` javadoc 引用"OutboxRelay 有序投递依赖"）——是声明式纪律，
  不是运行时机制；
- 即使纳入，总线不分区（`RedisStreamMessageBus` R6 注释：同 topic 消息投给 group 内任一
  consumer），**投递序不构成消费序**；同 documentId 的实际串行由 `image:lock:{documentId}`
  保证；
- 本 topic 的正确性不依赖跨消息有序：重投/乱序/双投递均由行级条件更新协议（高-2）收敛；
  不纳入换取 relay 侧并行投递（批量导入积压时的吞吐）。

在 yml 的 `ordered-topics` 处加注释记录该排除决策与理由指针（§6.9），防止后人"顺手补齐
白名单"造成无依据的配置漂移。

---

## 7. 并发与线程模型

| 线程 | 用途 | 并发上限 | 隔离 |
|------|------|---------|------|
| `etl-io-N`（现状） | 前台提取 + 索引 | 池 8 核/16 max（application.yml） | 与图片完全隔离 |
| `redis-process-rag_extract_images-N`（新增，runner 提供） | 后台取图/上传 | `ConsumerConfig.concurrency` = `odlImageConcurrency` | per-topic 独立池，打满不影响 ETL 消费 |
| `redis-receive-rag_extract_images`（新增，runner 提供） | 该 topic 拉取 | 单线程（背压载体） | 同上 |

- **v1.6 中-3 修正**：不新增 `image-io-N` 自建池——消费线程全部由
  `RedisStreamConsumerRunner` 提供（SIMPLE 模式：receive 单线程 + process 池 + inflight
  Semaphore，`RedisStreamConsumerRunner:135-167`）；并发度经 `ConsumerConfig.concurrency`
  表达，`odlImageConcurrency` 与之 1:1。消费饱和的表现是 receive 线程阻塞于
  `semaphore.acquire()`（消息停留在 stream 未投递、不进 PEL）——与 ETL 消费同一背压语义；
  处理池队列满（readBatch×2）时 routeToRetry 延迟重投，不留 PEL 卡死；
- 前台 `threads=N` 的逐页过滤 ForkJoinPool 是**每文档临时池**（ODL 内部
  `new ForkJoinPool(parallelism)` + `propagateState`，工作线程按需惰性创建），取半核数
  避免多文档并发 ETL 时互相挤压；
- **中-4（v1.6 量化）前台线程预算**：并发提取文档数 ≤ ETL 消费并发
  （`EtlDocumentConsumer` 未显式设置 → ConsumerConfig 默认 20）∩ etl-io 池上限（16）；
  逐页线程名义上界 = 并发文档数 × odlThreads（FJ 惰性创建 + CPU 调度自然钳制实际峰值）。
  **联动约束**：高核机器（如 32C）默认 odlThreads=16，×16 文档 = 名义 256 逐页线程 +
  公共池排序挤占——建议按部署规格显式设定（多文档并发为主的部署推荐
  odlThreads ≈ max(1, cores/8)；专机小并发可放宽至 cores/2）。**2C 容器**：cores/2=1，
  threads 收益归零**属预期**（P1 分段计时可见单文档串行段主导），不构成方案回退理由；
  需控制线程总量时**优先下调消费并发/etl-io 池**，不动每文档 threads（保单文档延迟）；
- **M4（明示）**：XY-Cut 排序的 `threads>1` 分支跑在 **JVM 公共 ForkJoinPool**（裸
  `IntStream.parallel()`），与本应用内其他 parallel 流共享挤占；排序器已审计为纯函数
  （§3.4），公共池工作线程无需 ThreadLocal 传播。残余风险由 §8.4 并发隔离实验覆盖
  （threads>1 必经公共池排序路径）；
- ThreadLocal 纯度依赖（§3.2，含硬约束 H-C1 `hybrid=off`）通过并发上限
  （`ConsumerConfig.concurrency`）内每消息的完整"preprocessing → 取图 → 清理"闭环保护，
  不做跨线程共享 `ImagesUtils`；
- `ImageExtractJob` 消费与 ETL 消费共享 RedisStream 基建但独立 topic/consumer group/
  处理池，互不争用。

---

## 8. 契约测试（固化 §3 事实卡；P1 放行门槛）

新增 `OpenDataLoaderContractTest`（测试范围，不起 Spring 容器，直接依赖 jar）：

1. **扩展点存在性**：反射断言 `DocumentProcessor.extractContents/preprocessing`、
   `MarkdownGenerator(Writer,Config)/writeToMarkdown/isImageSupported/writeImage/writePicture`、
   veraPDF `ImagesUtils.getImagesUtils/clearRenderedPages/getXObjectImage/getPageSubImage` 签名与
   可见性（升级 ODL 版本时 CI 即刻失败，强制重走 §3 复核）；
2. **清理镜像对齐（H1）**：读取 `DocumentProcessor.closePdfResources` 字节码，抽取
   `clearCleanupStep("…")` 的字符串参数序列，与 `OdlResourceCleaner.MIRROR_STEPS` 声明的
   步骤序列**完全相等**（步骤数 == 9 且名称逐一对应）——源码清理序列任何变化即刻失败；
3. **行为契约**：合成带图 PDF（复用本设计验证实验的 PDFBox 造图代码）断言：
   - `images=off` 时 contents 含 ImageChunk 且携带可解析 ObjectKey；
   - 后台线程 `preprocessing` + `getImagesUtils()` 懒实例 + `getXObjectImage(page, key)`
     能取回与前台同源的位图（EXP3 固化）；
   - 清理镜像执行后 `StaticResources.getDocument() == null` 且 `getImagesUtils()` 为 null
     （无残留）；
4. **并发隔离（S1，P1 硬放行门槛）**：EXP4 固化——双线程并发提取两个不同 PDF（多页、含图、
   `threads=2` 触发逐页并行 + 公共池排序），≥20 轮输出与单线程参考**逐字节一致**，零异常，
   且 `ExtractionResult.elementMetadata` 恒空（hybrid 静态量未被触碰的旁证）。
   **v1.3 增补同文件用例（EXP5，已运行通过：20 轮逐字节一致）**：双线程并发提取**同一**
   PDF 文件——覆盖"Redisson 按 documentId 锁失效"场景下的提取层安全网（提取对文件只读、
   每线程独立 PDDocument；行写入竞争由 DB 约束与锁负责，提取层字节级一致证明锁失效不会
   产生脏 Markdown）。**P1 上线（放大并发放行）前，两个用例必须在本机与 CI 环境双双通过**；
5. **占位符↔manifest 双射（M5，替换原"编号两次自等"恒真命题）**：每次前台解析后断言
   Markdown 占位符 URL 集合与 manifest 行集合构成双射（数量相等 + 无 missing 兜底 + URL 与
   行一一对应）——已由 §6.2 的 H3 断言在线执行，契约测试离线复核同一不变式。
   **造图用例锁定双遍历域一致性**（v1.3/v1.4 递增；**两个用例已用管线原型动态验证通过，
   见 §3.5 EXP6**，落地时将原型直接转为 CI 用例）：
   - **表格嵌套多图**（v1.3）：一行两个含图单元格的 PDF（表格行在 Markdown 中整行输出，
     同行 ≥2 占位符）——锁定出现次数计数（非行计数）与表格嵌套递归一致性；
   - **页眉脚嵌图**（v1.4，对应高-1）：带 logo 页眉（`SemanticHeaderOrFooter` 内嵌
     `ImageChunk`）的 PDF——锁定 `includeHeaderFooter=false` 时 numberer **整枝剪掉**页眉脚
     （manifest 不含该图、Markdown 无占位符、断言通过）；
   - **TOC 条目嵌图**（v1.6 中-1 升格独立用例，原 v1.4 仅在页眉脚用例中带过一句）：
     TOC 条目 `contents` 含图的 PDF——锁定 numberer 经 `SemanticTOC →
     SemanticTOCI.getContents()` 的递归与 `writeTOC`（2.5.5 sources jar `:310-321`，
     TOCI 分支 `writeContents` 递归）**同构**。EXP6 未覆盖此分支（仅实跑 TABLE/HEADER-CASE），
     是 numberer 镜像中唯一无动态证据的递归——**P2 开工前以 EXP6 同型原型补跑闭环
     （TOC-CASE），实跑通过后才转 CI 用例**（§10 落地顺序同步）。
   两遍历任何门控/递归分歧在这组用例上表现为断言失败（fail-closed）或降级告警（中-4
   开关），不静默错位；
6. **后台产出一致性抽样（M5；v1.5：PAGE_RENDER 机制已由 EXP7 动态验证——bbox round-trip、
   DPI 换算、跨运行像素哈希一致，§3.5）**：同一测试 PDF，后台按 manifest 提取的图片与
   `processFile(images=external)` 参考落盘图做像素哈希比对（XOBJECT 类应逐字节一致；
   PAGE_RENDER 类同 DPI 下一致），抽样 ≥5 张；**真实样本要求**：因本地模式不产生
   SemanticPicture（EXP7 结构发现）且无 key 的内联图难以稳定合成，PAGE_RENDER 抽样必须
   含真实文档样本（如带内联图的导出 PDF），合成用例仅覆盖 XOBJECT。

---

## 9. 版本耦合风险与升级守则

- 依赖的 `org.opendataloader.pdf.processor.DocumentProcessor` / `markdown.MarkdownGenerator`
  位于官方 javadoc 自述"非稳定公共 API"的包内（`generateOutputs` 注释明示）——**锁版本升级**：
  pom 变更必须伴随 §8 契约测试通过 + §3 事实卡按新 sources jar 复核修订；
- **硬约束 H-C1（v1.3 机制化）**：并发安全以 `hybrid=off` 为前提（§3.2，
  `HybridDocumentProcessor` 裸静态量）。已从"文档纪律"升级为机制：ODL 门面在每次调用前
  fail-fast 校验 `Config.getHybrid() == HYBRID_OFF`，否则抛 `IllegalStateException`（§6.2）。
  任何链路启用 hybrid 前，必须先引入 ODL 全局锁或上游串行化，移除该校验并修订
  §3.2/§7/§8.4；
- `Config` 默认值（threads=1 / imageOutput=external）不构成契约——前台显式设置全部依赖项，
  不依赖默认值；
- veraPDF 升级（如 2.5.1 #677）可能改变 ThreadLocal 结构——清理镜像清单以 `closePdfResources`
  源码为唯一事实源同步维护（§8.2 字节码断言强制）。

---

## 10. 实施拆步

| Phase | 内容 | 交付 | 放行门槛 | 可独立上线 |
|-------|------|------|---------|-----------|
| **P1 性能优化** | `images=off` + `threads=N`（§6.2 Config 部分）+ 分段计时日志；仍走 `processFile` 落盘 md | 前台耗时下降（数字以分段计时实测分布为准，M1）+ 全链路耗时可观测 | **§8.4 并发隔离契约测试（EXP4）在本机与 CI 双双通过——S1 门槛，未过不得放大并发** | ✅（依赖升级已完成） |
| **P2 图片链路** | ParseContext / ExtractWithManifest 载体 / PlaceholderMarkdownGenerator / ImageNumberer（isSupportedContent 镜像域）/ 显式短事务（状态+manifest 重建+outbox `sendInTransaction`）/ **FastTrack 路径集成（S1，§6.3.1）** / **消息不设 dedupKey（S2）** / EtlStatusManager 事务感知事件（H2）/ 条件更新协议 / `document_image`（含 producer_version）/ 后台消费者（ConsumerConfig.concurrency 映射 + §6.9 配置面）/ 上传 / **超龄 PENDING 告警 + 积压三指标（M2/中-4）** / H3 完整性断言（方向标记，L1）+ `odlPlaceholderStrict` 降级开关 + `odlImageMaxPerDoc` 预算（L3） | 图片进 MinIO + 占位符（**FastTrack 与 Standard 双路径**） | §8.1/8.2/8.3/8.4/8.5 契约测试通过（含页眉脚嵌图 + TOC-CASE 用例；**TOC-CASE 原型实跑先行**） | ✅（P1 之后任意时点） |
| **P3 补偿与闭环** | 孤儿对账（§6.8）+ 补偿扫描 + 终态告警（**不含读取端点**——v1.2 起端点另期立项，见 §6.5） | 存储全生命周期闭环 | §8.6 抽样一致性通过 | ✅ |

P2 落地顺序：契约测试先行（**TOC-CASE 原型补跑为其前置**，§8.5）→ ImageNumberer（纯函数）
→ 前台管线改造 → 表 + 消费者 → FastTrack 集成（§6.3.1）→ 双路径联调（小 PDF 走 FastTrack、
超限走 Standard，分别验证验收 3/4/5）。

---

## 11. 验收标准

1. **性能（口径统一，M1；v1.3 改条件条款）**：同一 12MB 手册，前台 ETL 收到消息 →
   `Document` 产出目标 **≤ 5s**——**以 P1 分段计时实测为准**：若实测显示 preprocessing
   串行段单独 >3s（占预算 60%，该段不受 `threads=N` 控制且随页数/复杂度超线性），则触发
   SLA 修订评审或立项 preprocessing 优化（如按页范围预处理流水线化），**不默认按违约处理**；
   各段耗时进日志，实测分布作为目标数字的最终裁决与后续优化的基线；
2. **并发隔离（S1）**：§8.4 双线程并发提取契约测试在本机与 CI 环境通过（≥20 轮逐字节一致），
   作为 P1 放行门槛先行验证；
3. 图片任务最终一致：全部 `document_image` 行到达 `UPLOADED/SKIPPED/FAILED` 终态；杀掉消费者
   进程后重启，PENDING 行被重新消费完成（RedisStream 重投验证）；**v1.6 前置**：消息不设
   dedupKey（§6.3 严重-2）——否则重投被 SETNX 幂等层判重静默 ACK，本验收必挂；验收 3/4/5
   以 **FastTrack（≤5MB 用例）与 Standard（>5MB 用例）双路径**分别执行（§6.3.1）；
4. 幂等：同消息重放 N 次，MinIO 对象数与行数不变，无重复上传副作用；
5. **数据完整性（H3）**：Markdown 中占位符与 manifest/MinIO 对象一一对应（双射断言在线拦截 +
   抽样 20 图人工核对页码/序号/内容）；构造 miss 场景时文档 ETL 整体失败而非索引残缺文本；
6. 契约测试进入 CI：人为调坏一个扩展点签名、或增删 `closePdfResources` 一个清理步骤时，
   构建失败；
7. 后台全程不影响前台：图片任务满载运行时，同步上传→检索可见耗时不明显回归——**测量
   口径（v1.4 定义）**：同一文档集（≥10 份、含大手册）分别在图片任务空载与满载（PENDING
   积压 >0 且消费处理池全忙——redis-process-rag_extract_images-N 全部占用）两种压力下执行
   上传→检索可见全流程，两组 **p95 延迟差 <10%**，
   样本与两组时窗记录进测试报告（复用 P1 分段计时日志的 e2e 段，不引入新埋点）；
   **GC 干扰记录（v1.6 L5）**：两组时窗同步采集 JVM GC pause 分布（`jvm.gc.pause` 指标或
   GC 日志）——图片解码（144DPI 整页 ≈8MB/页）与前台共堆，若 p95 达标但满载组 GC pause
   总时长/停顿分布显著恶化，测试报告须显式标注 GC 归因，避免前台回归定位困难。
