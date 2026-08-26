# PDF 解析性能优化与图片提取后台化设计（OpenDataLoader 2.5.5）

> **版本**: v1.3（第二轮评审修订）
> **日期**: 2026-08-26
> **状态**: 设计方案（两轮评审意见已处结，待复审）
> **依赖升级**: `opendataloader-pdf-core` 2.5.0 → **2.5.5**（已实施，见 §2）
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
- **pom 状态说明（L5）**：评审看到的 2.5.0 为已提交态；工作区 pom 已是 2.5.5（1 行版本 diff +
  M8 仓库声明，随本设计一并提交）。

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
（`docling/docling-fast/hancom/azure/google`），上述裸静态量即构成跨线程竞写，必须先加全局锁
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
  （每管线渲染整页 144DPI RGB ≈ 8MB/页）——用 `Semaphore` 限并发；约束 H-C1 除外。

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
- **SemanticPicture**（矢量/复合区域）：`getBoundingBox()` →
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
│       8. 【事务内】document_image 幂等重建（DELETE+INSERT，§6.3）      │
│          + outbox 投递 rag_extract_images 消息                        │
└──────────────────────────────────────────────────────────────────────┘
                                  │ MessageBus（outbox→relay→RedisStream）
┌─ 后台（image-io 消费者线程池，Semaphore 限并发，hybrid=off 约束 H-C1）──▼─┐
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

### 5.2 与最初草案的差异（评审要点）

| # | 原草案 | 本设计 | 理由 |
|---|--------|--------|------|
| 1 | 前台 `extractContents` ~150ms 级 | 量级预期 2–4s（**非承诺**，M1：以 P1 分段计时实测分布为准，验收统一 §11 的 ≤5s） | `extractContents` 包含 preprocessing（1–2s 串行）+ 逐页提取，150ms 只对小 PDF 成立 |
| 2 | 自写 `PlaceholderMdGen` 生成 Markdown | **子类化** ODL `MarkdownGenerator`（置 `isImageSupported=true` + 覆写 `writeImage`） | 复用标题/表格/阅读顺序全部逻辑，零重写漂移风险 |
| 3 | temp PDF + `ExtractionResult`（内存 contents）交给后台任务上下文 | **`ImageManifest` 持久化到 `document_image` 表**，后台重新下载 PDF、只重跑 `preprocessing` | `ExtractionResult` 无法随消息跨进程序列化；contents 持有期间阻碍前台线程复用；manifest 使编号一致性**由构造保证**（见 #4） |
| 4 | 占位符 (页,序) 与后台遍历各自编号，依赖跨运行提取确定性 | 编号**只在前台发生一次**并随 manifest 持久化，后台按清单逐条执行 | `threads>1` 官方标注 experimental"输出可能略有差异"，跨运行遍历序不应作为正确性依赖 |
| 5 | `@Async` + ODL 锁 | MessageBus 新 topic + `Semaphore` + Redisson documentId 锁 | ThreadLocal 模型下锁非正确性必需（§3.2 推论 3）；`@Async` 重启丢任务与消息可靠性冲突 |
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

ExtractionResult result = DocumentProcessor.extractContents(tempPdf.toString(), config);
try {
    ImageManifest manifest = ImageNumberer.number(result.getContents());   // ① 先编号
    String markdown;
    try (StringWriter sw = new StringWriter();
         PlaceholderMarkdownGenerator gen = new PlaceholderMarkdownGenerator(sw, config, manifest, ctx)) {
        gen.writeToMarkdown(result.getContents());                          // ② 占位符注入
        markdown = sw.toString();
    }
    assertPlaceholderIntegrity(markdown, manifest);   // ③ H3 完整性断言（见下，不等则整体失败）
    // ④ Document 组装（现状元数据逻辑不变）→ 返回
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
    protected void writeImage(ImageChunk image) {          // ImageChunk 路径（嵌入位图）
        writePlaceholder(manifest.byIdentity(image));
    }

    @Override
    protected void writePicture(SemanticPicture picture) { // SemanticPicture 路径（矢量/复合区域）
        writePlaceholder(manifest.byIdentity(picture));
    }

    private void writePlaceholder(Optional<ImageEntry> entry) {
        // H3：miss 不允许抛异常 —— 父类 writeToMarkdown 对整体循环 try/catch(Exception)，
        //      仅记 WARNING 后返回半截 Markdown，ETL 会把残缺文本当完整文档索引（数据完整性事故）。
        //      策略：miss 写显式兜底标记，生成完成后由数量断言整体拦截（见下）。
        String url = entry.map(e -> "/api/documents/%d/images/%s".formatted(ctx.documentId(), e.urlName()))
                         .orElse("/api/documents/%d/images/missing".formatted(ctx.documentId()));
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
    throw new DocumentParseException(fileName, "opendataloader",
        "占位符数量(" + placeholderCount + "/" + manifest.size() + ")不一致或存在 missing 兜底，"
        + "拒绝索引残缺文档");
}
```

> **两条遍历的分歧风险（v1.3 明示）**：`ImageNumberer` 与 `MarkdownGenerator` 是两条独立遍历
> ——编号器镜像 `writeFromContents` 的嵌套序，生成器另有自己的格式化决策。当前 2.5.5 两者
> 对图片对象的访问域一致（表格/列表/页眉脚嵌套均被 `writeFromContents` 与 `writeContents`
> 同构覆盖，`isSupportedContent` 对 `ImageChunk`/`SemanticPicture` 的取舍一致）；任何一方的
> 分歧都会表现为上述断言失败（fail-closed，不会静默错位），并由 §8.5 的"表格嵌套多图"
> 用例在 CI 持续锁定。

> 断言语义：占位符总数必须等于 manifest 全部条目数且**零** missing 兜底——任何 miss/截断/
> 吞异常都会被这一道整体拦截，宁可文档 ETL 失败重试，不可静默索引残缺文本。

**L2 已知外观问题（记录不修复）**：Writer 构造器路径下 `writeToMarkdown` 收尾日志打印
`"Created null"`（`markdownFileName` 为 null，源码 `:114`）。属 ODL JUL 噪音，不影响正确性；
上线时可通过 JUL level 配置压掉 `MarkdownGenerator` 的 INFO，不为此 fork 补丁。

**ImageNumberer（编号器，前后台共同事实源）**

镜像 ODL `ImagesUtils.writeFromContents` 的遍历序（**含嵌套规则**，遍历序即阅读顺序）：

```
for page in 0..n-1:
    walk(contents[page]):
        ImageChunk       → 记录 {type=XOBJECT,  page, seq++, bbox, objectKey(number,generation), xObjectName}
        SemanticPicture  → 记录 {type=PAGE_RENDER, page, seq++, bbox}
        PDFList          → 递归 listItem.getContents()
        TableBorder      → 递归 row.cell.getContents()（跳过跨格重复项：cell.colNumber==col && rowNumber==row）
        SemanticHeaderOrFooter → 递归 getContents()
```

- `seq` 为**文档内连续递增**（与 ODL `imageIndex` 语义一致），URL 名用 `(page+1)-{seq}` 双保险；
- `manifest.byIdentity(ImageChunk)` 用 `IdentityHashMap` 建立——同一 `contents` 对象图内引用相等
  即命中，**不存在编号歧义**；
- bbox 序列化为 `[leftX, bottomY, rightX, topY]`（double×4）。

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
    status       VARCHAR(16)  NOT NULL DEFAULT 'PENDING',  -- PENDING|UPLOADED|FAILED|SKIPPED
    fail_reason  VARCHAR(512),
    file_size    BIGINT,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    UNIQUE (document_id, seq)
);
CREATE INDEX idx_document_image_doc ON document_image(document_id, status);
```

**事务边界与重试重建（v1.3 补全）**：`document_image` 写入与 ETL 结果落库同事务
（`EtlRouteStrategy` 写结果的位置），随后 outbox 投递 `rag_extract_images` 消息（复用
`EtlDispatchServiceImpl.dispatchAsync` 的 outbox 模式与 `rag.etl.publish_failed` 告警计数）。

**同 documentId 重解析（ETL 重试）的 manifest 重建机制**——`UNIQUE(document_id, seq)` 约束下
裸批量 INSERT 必然唯一键冲突；且重解析的图片数/seq 语义可能与旧 manifest 不同，残留旧行
（PENDING 被消费重传旧图 / UPLOADED 与新 Markdown 的 seq 错位）都是脏状态。因此：

```sql
-- 同一事务内，先删后插（幂等重建）
DELETE FROM document_image WHERE document_id = :documentId;
INSERT INTO document_image (...) VALUES (...);   -- 新 manifest 全量
```

- 提交后才投递消息；消费侧以行状态为准，不存在跨代残留（旧行已随事务删除）；
- MinIO 侧同 key 覆盖（重解析后同 (page,seq) 位置的新图覆盖旧对象）；图片数变少时，
  多出的旧对象成为孤儿，由 §6.8 对账清理兜底——**不用在删除路径做前缀清理**（那是文档
  删除的职责，重试场景交给对账，避免删除路径与消费路径的并发竞争）；
- outbox 消息 dedupKey=documentId，重试期间旧消息与新消息最终收敛到同一份 manifest。

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
// 线程池：image-io-N，core=max(1, odlImageConcurrency)，与 etl-io 隔离
public void consume(ImageExtractJob job) {
    if (!semaphore.tryAcquire(30, SECONDS)) { nack(job); return; } // 许可不可得 → NACK 重投，不静默 ACK
    RLock lock = redisson.getLock("smart-rag:image:lock:" + job.documentId());
    boolean locked = false;                                        // v1.3：严格对称，见 finally
    List<DocumentImageRow> rows = List.of();                       // L1：声明在 try 之前，catch 可安全引用
    Path tempPdf = null;
    try {
        // v1.3 补全锁规范：获取失败（争用中）→ NACK/重投而非静默 ACK；
        // leaseTime=-1 走 Redisson watchdog 续期（对齐 EtlDispatchServiceImpl 的锁模式）
        locked = lock.tryLock(30, -1, SECONDS);
        if (!locked) { nack(job); return; }

        rows = repository.findPending(job.documentId());           // 幂等：UPLOADED/SKIPPED 行被过滤
        if (rows.isEmpty()) { ack(job); return; }
        tempPdf = downloadToTemp(job);                             // FileStorageService.open(...).content(Full)

        Config cfg = new Config(); cfg.setImageOutput(Config.IMAGE_OUTPUT_OFF);
        DocumentProcessor.preprocessing(tempPdf.toString(), cfg);   // 重建本线程 ThreadLocal 状态
        var utils = StaticContainers.getImagesUtils();             // M7：懒实例，单次加载；由清理镜像第 2 步关闭
        int lastPage = -1;
        for (DocumentImageRow row : rows) {                        // manifest 已按 (page, seq) 排序
            if (row.pageNumber() != lastPage) {
                utils.clearRenderedPages();                        // H2：换页显式清缓存（内存上界 = 1 页）
                lastPage = row.pageNumber();
            }
            BufferedImage img = switch (row.imgType()) {
                case XOBJECT     -> utils.getXObjectImage(row.pageNumber(),
                                         new ObjectKey(row.objectNum(), row.objectGen()));
                case PAGE_RENDER -> utils.getPageSubImage(row.bbox(), dpi);  // 可配，默认 144；alpha 压平白底
            };
            if (img == null) { row.markSkipped("unresolvable-xobject"); continue; }  // 结构性：见下
            byte[] bytes = encode(img, row.ext());                 // 当页编码后即弃（getSubimage 共享父光栅）
            if (bytes.length > maxBytes) {                         // v1.3：确定性超限 = 结构性终态，不入重放
                row.markSkipped("max-bytes-exceeded", bytes.length); continue;
            }
            fileStorage.upload(bucket, row.storageKey(), new ByteArrayResource(bytes), row.mime());
            row.markUploaded(bytes.length);                        // UPLOADED
        }
        ack(job);
    } catch (Exception e) {
        // M6：瞬时异常（下载/预处理/解码 IOException）→ 未完成的行回置 PENDING 并整批投 FAILED
        // 语义（本批事务回滚式），交由 RedisStream 重试/DLQ 重放；不判死。
        // 注意：确定性超限已在循环内走 SKIPPED 终态，不会进此通道耗尽重试预算
        repository.resetUnfinishedToPending(rows, sanitize(e));
        nack(job, e);
    } finally {
        if (tempPdf != null) { cleanupMirror(); safeDelete(tempPdf); }   // §3.3 九步镜像（本线程）
        if (locked && lock.isHeldByCurrentThread()) lock.unlock(); // v1.3：守卫解锁，异常不连锁
        semaphore.release();                                       // 严格对称，最后一步
    }
}
```

> **v1.3 锁与许可的故障注入不变量**（照抄实现必须满足）：任何路径下 `semaphore.release()`
> 恰好执行一次（tryAcquire 成功后必达）；`unlock` 有 `isHeldByCurrentThread()` 守卫
> （否则 `IllegalMonitorStateException` 会掩盖原始异常并跳过 release → 许可泄漏 →
> image-io 车道永久停摆）；获取失败走 NACK 重投，绝不静默 ACK 丢消息。

**M6 终态判定语义（收窄 SKIPPED；v1.3 增补确定性超限）**：

| 情形 | 行状态 | 依据 |
|------|--------|------|
| `getXObjectImage` 返回 null（对象号在页资源中确证不存在） | `SKIPPED`（终态，`fail_reason=unresolvable-xobject`） | 结构性：同文件重放 N 次结果不变 |
| 编码后 `bytes.length > odlImageMaxBytes` | `SKIPPED`（终态，`fail_reason=max-bytes-exceeded`） | 结构性：重放结果不变；走重放只会烧完预算后误报 `extract_dead` |
| 任何异常（IO/渲染/上传失败、内存压力下的解码失败） | 回置 `PENDING` + 批次计入重试 | 瞬时：重放可能成功 |
| 重放次数超上限（RedisStream 重试 + DLQ 后仍失败） | `FAILED`（终态）+ 计数告警 `rag.image.extract_dead` | 防无限重放 |

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
| `odlThreads` | `max(1, availableProcessors()/2)` | 前台逐页并行度（容器内有 cgroup 时被自动 clamp，注意 CPU limit） |
| `odlImageConcurrency` | `1` | 后台图片提取并发（Semaphore 许可数 + 消费线程池 core） |
| `odlImageRenderDpi` | `144` | PAGE_RENDER 渲染 DPI（2.5.5 `getPageSubImage(bbox, dpi)`） |
| `odlImageJpegQuality` | `0.85` | 渲染图 JPEG 质量（控制 MinIO 存储体积） |
| `odlImageMaxBytes` | `20MB` | 单图上传上限（防御异常大图） |

### 6.7 失败模式与降级矩阵

| 故障点 | 行为 | 影响 | 恢复 |
|--------|------|------|------|
| 前台 extractContents 异常 | 文档 ETL 失败（现状语义） | 无索引 | 用户重试（现状） |
| 占位符数量 ≠ manifest（H3） | 前台整体抛 `DocumentParseException`，文档 ETL 失败 | 无索引（拒绝残缺文本） | 用户重试；持续失败进运维排查 |
| outbox 投递失败 | 计数 `rag.etl.publish_failed`（对齐现状） | 图片滞留 PENDING | P2 起超龄告警可观测；P3 扫描重投 / 手动重投 |
| 后台下载/预处理/解码/上传异常 | 未完成行回置 PENDING，RedisStream 重试 → DLQ | 图片不在 MinIO（占位符为惰性文本，端点未实现，无 404 语义） | 重放消息，行级幂等（M6：不判死） |
| `getXObjectImage` 返回 null（结构性） | 行 SKIPPED（终态） | 该图无存储对象 | 不可恢复 |
| 重放超限（DLQ 后仍失败） | 行 FAILED（终态）+ `rag.image.extract_dead` | 相关图无存储对象 | 运维介入 |
| 消费者进程崩溃 | 行停留 PENDING | 图片暂缺 | RedisStream 未 ACK 重投 |
| 文档被删除 | 消费前校验文档存在，跳过；孤儿清理见 §6.8 | — | — |

### 6.8 图片孤儿清理（M3，对位 `OrphanChunkCleaner` / `DirectUploadOrphanCleaner`）

文档删除时，`images/{documentId}/` 前缀对象与 `document_image` 行必须随原始对象一并清理：

- **同步路径**：文档删除服务在删除原始对象与向量（现状逻辑）之后、事务提交之前，追加
  ① `DELETE FROM document_image WHERE document_id = ?`；② `FileStorageService` 按前缀列出
  `images/{documentId}/` 对象并批量删除（best-effort，ListObjects+DeleteObjects）；
  失败不阻断删除主流程，计数 `rag.image.orphan_clean_failed` 告警；
- **对账兜底（P3）**：定时任务对账 `document_image` ↔ MinIO 前缀对象 ↔ 文档表三方状态，
  清理"文档已不存在但图片对象仍存在"的孤儿（与 `DirectUploadOrphanCleaner` 同模式：先扫描
  后删除、删除前二次确认文档仍不存在，防 TOCTOU 误删重用 documentId 的场景——documentId
  为自增主键不复用，风险天然消除，对账仅防删除路径的部分失败）；
- **重试链路对齐**：ETL 重试（同 documentId 重新解析）会重建 manifest 行——消费者以行为准、
  同 key 覆盖上传，旧对象被同 key 新内容覆盖，无泄漏。

---

## 7. 并发与线程模型

| 线程池 | 用途 | 并发上限 | 隔离 |
|--------|------|---------|------|
| `etl-io-N`（现状） | 前台提取 + 索引 | 现状 | 与图片完全隔离 |
| `image-io-N`（新增） | 后台取图/上传 | `odlImageConcurrency` | 独立池，打满不影响 ETL |

- 前台 `threads=N` 的逐页过滤 ForkJoinPool 是**每文档临时池**（ODL 内部
  `new ForkJoinPool(parallelism)` + `propagateState`），取半核数避免多文档并发 ETL 时互相挤压；
- **M4（明示）**：XY-Cut 排序的 `threads>1` 分支跑在 **JVM 公共 ForkJoinPool**（裸
  `IntStream.parallel()`），与本应用内其他 parallel 流共享挤占；排序器已审计为纯函数
  （§3.4），公共池工作线程无需 ThreadLocal 传播。残余风险由 §8.4 并发隔离实验覆盖
  （threads>1 必经公共池排序路径）；
- ThreadLocal 纯度依赖（§3.2，含硬约束 H-C1 `hybrid=off`）通过 `Semaphore` + 每 Semaphore
  许可内的完整"preprocessing → 取图 → 清理"闭环保护，不做跨线程共享 `ImagesUtils`；
- `ImageExtractJob` 消费与 ETL 消费共享 RedisStream 基建但独立 topic/consumer group，互不争用。

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
   **v1.3 增补"表格嵌套多图"造图用例**：构造一行两个含图单元格的 PDF（表格行在 Markdown
   中整行输出，同行 ≥2 占位符），锁定 ① 出现次数计数（非行计数）正确性、② `ImageNumberer`
   遍历序与 `MarkdownGenerator` 输出域的一致性——两遍历任何分歧在该用例上表现为断言失败
   （fail-closed）；
6. **后台产出一致性抽样（M5）**：同一测试 PDF，后台按 manifest 提取的图片与
   `processFile(images=external)` 参考落盘图做像素哈希比对（XOBJECT 类应逐字节一致；
   PAGE_RENDER 类同 DPI 下一致），抽样 ≥5 张。

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
| **P2 图片链路** | ParseContext / PlaceholderMarkdownGenerator / ImageNumberer / `document_image` / 后台消费者 / 上传 / **超龄 PENDING 告警（M2）** / H3 完整性断言 | 图片进 MinIO + 占位符 | §8.1/8.2/8.3/8.5 契约测试通过 | ✅（P1 之后任意时点） |
| **P3 补偿与闭环** | 孤儿对账（§6.8）+ 补偿扫描 + 终态告警（**不含读取端点**——v1.2 起端点另期立项，见 §6.5） | 存储全生命周期闭环 | §8.6 抽样一致性通过 | ✅ |

P2 落地顺序：契约测试先行 → ImageNumberer（纯函数）→ 前台管线改造 → 表 + 消费者 → 联调。

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
   进程后重启，PENDING 行被重新消费完成（RedisStream 重投验证）；
4. 幂等：同消息重放 N 次，MinIO 对象数与行数不变，无重复上传副作用；
5. **数据完整性（H3）**：Markdown 中占位符与 manifest/MinIO 对象一一对应（双射断言在线拦截 +
   抽样 20 图人工核对页码/序号/内容）；构造 miss 场景时文档 ETL 整体失败而非索引残缺文本；
6. 契约测试进入 CI：人为调坏一个扩展点签名、或增删 `closePdfResources` 一个清理步骤时，
   构建失败；
7. 后台全程不影响前台：图片任务满载运行时，同步上传→检索可见耗时无明显回归（<10% 波动）。
