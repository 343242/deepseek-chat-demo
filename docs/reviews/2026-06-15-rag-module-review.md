# RAG 模块代码审查报告 — 2026-06-15

> 模块：`src/main/java/com/smart/rag/rag/`
> 技术栈：Spring Boot 3.5.14 · Java 21 · Spring AI 1.1.6 · MyBatis-Plus 3.5.16 · Redis (Redisson 3.52.0) · Agentic RAG
> 审查方式：两轮 ECC agent 自动审查 + 人工核验
> 审查工具：`ecc:java-reviewer`（正确性/架构，R1）+ `ecc:security-reviewer`（安全/字节处理，R2）
> 总发现：**28 项** — 🔴 CRITICAL 0 · 🟠 HIGH 6 · 🟡 MEDIUM 12 · 🔵 LOW 8 · 🔵 质量项 1 · 依赖 1

---

## 0. 结论

| 维度 | 判定 |
|---|---|
| 架构与防御性编码 | ✅ 扎实（策略模式、Lua 原子限流、BloomFilter 降级、崩溃安全 supersede、教科书线程池、构造器注入、参数化 SQL） |
| 正确性 | ⚠️ 4 个 HIGH（多为 null-as-success / 无分页 / 非原子） |
| 安全 | ⚠️ PASS-WITH-WARNINGS — 最吓人的 6 类（XXE/SSRF/反序列化/zip-slip/ReDoS/编码）**不可利用**，阻塞项是分片上传 MIME 绕过 |
| 是否可暴露给不可信外网 | ❌ 修完 R2-H1、R2-H2 后方可 |

两轮审查**互补、无重复**；R2 的安全视角挖出了 R1 读过却漏掉的分片 MIME 洞（R2-H1）。

---

## 1. 发现总览（Master Table）

### 🟠 HIGH（6）

| ID | 来源 | 位置 | 问题 |
|---|---|---|---|
| **R2-H1** | R2 | `ChunkUploadServiceImpl.java:74,416,386` | 分片上传只校验客户端**声明** MIME，从不跑 `detectMimeType()`；声明 MIME 原样落库 + 路由解析器 |
| **R2-H2** | R2 | `DocxDocumentParser:52` / `PptDocumentParser:60` / `ExcelDocumentParser:65` | OOXML 解析未 pin `ZipSecureFile` 阈值，docx/pptx 全文灌内存无段落上限 → OOM |
| **R1-H1** | R1 | `ChunkUploadServiceImpl.java:289` | `complete()` 合并失败后返回 `null` docId → 控制器包成 200 OK，静默失败 |
| **R1-H2** | R1 | `DocumentApplicationServiceImpl.java:94/109/170` | `listAll`/`listByTeam`/`getHistory` 裸 `selectList` 无分页 |
| **R1-H3** | R1 | `PersonalUploadStrategy.java:113-132` | 批量上传跨 MinIO/DB/ETL 非原子，中途抛异常 → 已落库文件永不进 ETL |
| **R1-H4** | R1 | `DocumentApplicationServiceImpl.java:118` + `DocumentController.java:57` | `getById`/`verifyAccess` 对"不存在"和"无权限"都返回 `null` → 200-with-null |

### 🟡 MEDIUM（12）

| ID | 来源 | 位置 | 问题 |
|---|---|---|---|
| **R2-M1** | R2 | `OpenDataLoaderPdfParser.java:48` | `transferTo` 写 temp 前无 size 预检（靠上游 50MB 兜底） |
| **R2-M2** | R2 | `DocumentValidator.java:97-108` | OOXML 子类型靠扩展名判定，`.xlsx` 漏在 map 外 |
| **R2-M3** | R2 | `PlainTextDocumentParser.java:43` + `EncodingDetector:88` | `contentLength()` 返回 -1 → size 检查失效 → `readAllBytes()` 无界 |
| **R1-M1** | R1 | `DocumentApplicationServiceImpl.java:194-215` | 授权缺口：团队文档不判 owner，任何成员可删/重试他人文档 |
| **R1-M2** | R1 | `ChunkUploadServiceImpl.java:341,386,404` | Redis session 字段 `Long.parseLong` 无 `NumberFormatException` 防御 |
| **R1-M3** | R1 | `EtlDispatchServiceImpl.java:95,107` | 看门狗锁续期饥饿会自动释放锁 → 双实例并发 ETL，缺注释 |
| **R1-M4** | R1 | `OrphanChunkCleaner.java:164-173` | `objectName.split("/")` 假设固定深度取 `parts[2]`，路径变就误删活跃分片 |
| **R1-M5** | R1 | `MinioFileStorageService.java:109-127` | `MinioStreamResource` 未覆写 `close()` → 解析器抛异常泄漏 MinIO HTTP 连接 |
| **R1-M6** | R1 | `DocumentDedupService.java:50` | 构造器同步全量 `selectList` 喂 BloomFilter，冷启动阻塞应用启动 |
| **R1-M7** | R1 | `DocumentValidator.java:133-143` | `getAllowedMimeTypes().split(",")` 无 trim，配置带空格静默拒绝上传 |
| **R1-M8** | R1 | `RerankDocumentPostProcessor.java:41-42` | 空文档/空 query/重排失败语义不一致，误导下游 MMR |
| **R1-M9** | R1 | `EtlStatusManager.java:83,113` | `truncate(msg,2000)` 假设列长 ≥2000，列长不足则 FAILED 写不进 → 文档卡死 |

### 🔵 LOW（8）

| ID | 来源 | 位置 | 问题 |
|---|---|---|---|
| **R2-L1** | R2 | `ChunkUploadController.java:65` | 分片 `@RequestBody byte[]` 整块入堆，建议改流式 `putObject` |
| **R2-L2** | R2 | `PptDocumentParser.java:249-282` | 表格 `rows*cols` 无上限，建议 `MAX_TABLE_ROWS/COLS` |
| **R2-L3** | R2 | `DocxDocumentParser.java:82-85` | 抛 `RuntimeException` 而非 `DocumentParseException`，不一致 |
| **R1-L1** | R1 | `TokenChunkStrategy:42` / `ParentChildChunkStrategy:70,74` | 每次 `chunk()` 重建 `TokenTextSplitter`，应构造器复用 |
| **R1-L2** | R1 | `PersonalUploadStrategy.java:196-205,86` | `computeMd5` 失败返回 `null` → 该文件永久无法秒传，仅 warn |
| **R1-L3** | R1 | `ChunkUploadServiceImpl.java:617` | `sanitizeFilename` 留 `..`（对象 key 故不可利用，建议加注释） |
| **R1-L4** | R1 | `QueryNormalizer.java:51-53` | DEBUG 日志含原始 query，RAG 场景可能 PII |
| **R1-L5** | R1 | `MmrDocumentPostProcessor.java:79,184` | O(n²) 距离查询 + `MAX_PAIRWISE_DOCS=50` 静默截断未上抛 |

### 🔵 质量项（1，用户提出 + 人工核验）

| ID | 位置 | 问题 |
|---|---|---|
| **U1** | `ChunkUploadServiceImpl.java:627,694` + `PersonalUploadStrategy.java:196` | MD5 用裸 `java.security.MessageDigest`，3 处实现 + 2 套手写 hex 编码器散落两文件 → 替换为 `commons-codec` `DigestUtils.md5Hex()` |

### 供应链（1）

| ID | 项 | 处置 |
|---|---|---|
| **R2-Dep** | `commons-compress` 本地多版本（至 1.26.1），实际解析版本未验证；CVE-2024-25710/26308 需 ≥1.26.1 | `<dependencyManagement>` pin ≥1.27.x + `mvn dependency:tree` 核实 |

---

## 2. 详细发现

### HIGH

#### R2-H1 · 分片上传完全绕过服务端 MIME 校验
- **位置**：`ChunkUploadServiceImpl.java:74`（init）、`:416-421`（`validateMimeType` 只查声明）、`:386-406`（`performMerge` 用 `session.get("mimeType")` 路由解析器）
- **问题**：客户端在 `ChunkUploadInitRequest` 声明 `mimeType`，`init()` 只校验是否在白名单（声明值），该值原样写入 Redis session，合并后流进 `dispatchAsync(..., session.get("mimeType"), ...)` 成为解析器路由 key 与落库 `rag_document.mimeType`。`DocumentValidator.detectMimeType()`（唯一魔法字节校验）**从不被调用**。非分片路径（`PersonalUploadStrategy`/`TeamUploadStrategy`）正确调了。
- **影响**：声明与真实内容不符，存储/审计失真；任何未来改路由的代码都继承未校验的类型。
- **修法**：`composeObject` 后、`persistDocument` 前，对合并对象头 ~16 字节跑 `detectMimeType`；用**检测到的** MIME（非 session 声明值）进 `dispatchAsync`。把 `detectMimeType` 抽成可复用 `detectMimeType(InputStream)`。

#### R2-H2 · OOXML 解析未显式钉死解压炸弹防御
- **位置**：`DocxDocumentParser.java:52`（`new XWPFDocument(is)`）、`PptDocumentParser.java:60`（`new XMLSlideShow(is)`）、`ExcelDocumentParser.java:65`（流式，最稳）
- **问题**：POI 5.5.1 的 `ZipSecureFile` 默认阈值生效（故 HIGH 非 CRITICAL），但代码/配置**未 pin、未 assert**，换依赖或加 `-Dpoi.*` 就静默失效。docx/pptx 把全文段落/shape 灌进 `ArrayList<Document>` 无数量上限 —— 塞满空段落的 docx 能绕过压缩比检查 OOM。
- **修法**：`@PostConstruct` 显式钉 `ZipSecureFile.setMinInflateRatio(0.01)` 等阈值；加 `MAX_PARAGRAPHS/MAX_SLIDES` 计数熔断（参照 `PptDocumentParser` 已有的 `MAX_GROUP_DEPTH=5`）；office 格式可把 `maxFileSize` 降到 20MB。

#### R1-H1 · `complete()` 返回 null docId 包成 200 OK
- **位置**：`ChunkUploadServiceImpl.java:289`
- **问题**：异步合并失败后查不到 doc 返回 `null`，控制器包 `GlobalResponse.ok(null)`。
- **修法**：`doc == null` 时抛 `ServiceException(ETL_FAILED)`；`performMerge` 直接返回 docId 而非重查。

#### R1-H2 · 列表查询无分页
- **位置**：`DocumentApplicationServiceImpl.java:94`（listAll）、`:109`（listByTeam）、`:170`（getHistory）
- **问题**：`selectList` + `orderByDesc(createTime)`，无 `Page`/`LIMIT`，文档量一大 OOM/慢。
- **修法**：MyBatis-Plus `IPage<DocumentDTO>` + `selectPage`，`@RequestParam` page/size，size 上限 100。

#### R1-H3 · 批量上传非原子
- **位置**：`PersonalUploadStrategy.java:113-132`
- **问题**：逐文件 upload→persist→收集，循环后才 `dispatch`。中途失败 → 已落库文件 `UPLOADED` 死状态，无补偿。
- **修法**：逐文件 try/catch 续跑返回逐文件状态；或整批补偿事务回滚 MinIO+DB；至少 catch 后把已 persist 的候选 dispatch 进 ETL。

#### R1-H4 · `getById` 返回 null 而非 404
- **位置**：`DocumentApplicationServiceImpl.java:118-121` + `DocumentController.java:57-59`
- **问题**：`verifyAccess` 对"不存在"和"无权限"都返回 `null` → 200-with-null，前端无法区分 404/403。
- **修法**：`verifyAccess` 区分抛 `DOCUMENT_NOT_FOUND` vs `ClientException(FORBIDDEN)`。

### MEDIUM

#### R2-M1 · OpenDataLoaderPdfParser 无 size 预检
- **位置**：`OpenDataLoaderPdfParser.java:48-67`
- **修法**：`transferTo` 前断言 `contentLength()` ≤ `maxFileSize`；`readString` 加上限；`finally` 清理已正确。

#### R2-M2 · OOXML 浅嗅探 + 缺 .xlsx
- **位置**：`DocumentValidator.java:85-127`
- **修法**：读 zip 内 `[Content_Types].xml` 判真实子类型；`EXTENSION_MIME_MAP` 补 `.xlsx`。

#### R2-M3 · contentLength()=-1 致 size 检查失效
- **位置**：`PlainTextDocumentParser.java:42-55`、`EncodingDetector.java:88`
- **问题**：`MinioStreamResource.contentLength()` 返回 -1，`> MAX_TEXT_FILE_SIZE` 永不成立 → `readAllBytes()` 无界。
- **修法**：套 `BoundedInputStream`（commons-io）让解析器自防御。

#### R1-M1 · 团队文档授权缺口
- **位置**：`DocumentApplicationServiceImpl.java:194-215`
- **问题**：`verifyAccess` 不判 owner，任何团队成员可删/重试他人上传的文档（Javadoc 声称"管理员/创建者/上传者"但代码未执行）。
- **修法**：变更操作加 `currentUserId.equals(doc.getUserId()) || isAdmin` 判定。

#### R1-M2 · session 字段 parseLong 无防御
- **位置**：`ChunkUploadServiceImpl.java:341,386,404`
- **修法**：抽 `parseSessionLong(session, key, fieldName)`，catch `NumberFormatException` 抛 `ServiceException`。

#### R1-M3 · 看门狗锁续期饥饿
- **位置**：`EtlDispatchServiceImpl.java:95,107`
- **修法**：加注释说明锁是 best-effort，向量库幂等才是真边界；若 `vectorStore.add` 非幂等则验证并文档化。

#### R1-M4 · OrphanChunkCleaner 路径拆分假设固定深度
- **位置**：`OrphanChunkCleaner.java:164-173`
- **修法**：用正则 `^chunks/[^/]+/([0-9a-f-]{36})/part-\d+$` 提取 uploadId。

#### R1-M5 · MinioStreamResource 未覆写 close()
- **位置**：`MinioFileStorageService.java:109-127`
- **修法**：覆写 `close()` 关闭 `GetObjectResponse`；`DocumentExtractor.extract` 用 try-with-resources 包 `Resource`。

#### R1-M6 · DocumentDedupService 启动阻塞
- **位置**：`DocumentDedupService.java:50`
- **修法**：`loadExistingFileMd5s` 移到 `ApplicationReadyEvent`，或分页加载。

#### R1-M7 · MIME 配置无 trim
- **位置**：`DocumentValidator.java:133-143`
- **修法**：`Arrays.stream(split).map(String::trim).filter(s -> !s.isEmpty()).collect(toSet())`。

#### R1-M8 · rerank 空结果语义不一致
- **位置**：`RerankDocumentPostProcessor.java:41-42`
- **修法**：文档化降级契约；空文档统一透传而非返回空。

#### R1-M9 · error_message 截断长度未对齐 DDL
- **位置**：`EtlStatusManager.java:83,113`
- **修法**：核实 `error_message` 列长，对齐截断常量；从 `DocumentProperties` 读。

### LOW

#### R2-L1 · 分片整块 byte[] 入堆 — `ChunkUploadController.java:65` → 改流式 `putObject` + 边算 MD5。
#### R2-L2 · PPTX 表格无上限 — `PptDocumentParser.java:249-282` → `MAX_TABLE_ROWS/COLS`。
#### R2-L3 · DocxDocumentParser 抛 RuntimeException — `DocxDocumentParser.java:82-85` → 改抛 `DocumentParseException`。
#### R1-L1 · TokenTextSplitter 重复构造 — `TokenChunkStrategy:42`、`ParentChildChunkStrategy:70,74` → 构造器复用。
#### R1-L2 · computeMd5 失败返回 null — `PersonalUploadStrategy.java:196-205` → 至少 error 级日志或上传失败。**与 U1 同处，一并处理。**
#### R1-L3 · sanitizeFilename 留 `..` — `ChunkUploadServiceImpl.java:617` → 加注释说明对象 key 安全。
#### R1-L4 · DEBUG 日志含 query PII — `QueryNormalizer.java:51-53` → 文档化或生产 redact。
#### R1-L5 · MMR 静默截断 — `MmrDocumentPostProcessor.java:79,184` → info 级日志 + 响应元数据上抛。

### 质量项

#### U1 · MD5 用裸 MessageDigest（用户提出，人工核验）
- **位置**：`ChunkUploadServiceImpl.java:627`（`hexFormat(md.digest())`）、`:694`（`md5Hex(byte[])`，失败抛 `RuntimeException`）、`PersonalUploadStrategy.java:196-205`（手写 `String.format("%02x")` 循环）
- **现状**：3 处实现 `MessageDigest.getInstance("MD5")` + 2 套手写 hex 编码器散落两文件。hex 编码本身**无 leading-zero bug**（`%02x`/`hexFormat` 均正确补零），故非 bug，是维护性/一致性债务 —— 三套实现 + 两套编码器是未来 bug 温床。
- **影响**：与 R1-L2 同处（`computeMd5` 失败返回 null），重构可顺带改善错误处理。
- **修法**：替换为 `commons-codec` `DigestUtils.md5Hex(InputStream/byte[])`，3 处统一为一行调用，删除 `hexFormat` 与手写循环。
- **依赖处置（⚠️ 重要）**：
  - pom.xml **未显式声明** commons-codec（目前纯传递依赖，Spring Boot web/httpclient5 带入）。
  - 本地 m2 当前最高 **1.19.0**，**无 1.22.0**。
  - Spring Boot **3.5.14** BOM 管理 commons-codec 版本。
  - **推荐**：直接 `import org.apache.commons.codec.digest.DigestUtils;` 用 BOM 管理版本（最干净）；或如需 pin 1.22.0，在 `<dependencyManagement>` 显式声明，但**构建前必须**：(a) 确认 1.22.0 与 Spring Boot 3.5.14 管理版本不冲突；(b) 从中央仓库拉取（m2 暂无）。

---

## 3. 跨切片主题（根因聚类）

修一类连带解决多条：

1. **null 当成功返回（5 条）**：R1-H1、R1-H4、R1-L2、R2-L3（+ R1-M8 透传）
   → 系统约定："查不到/失败"一律抛 `ServiceException`，禁止返回 null 让控制器包 200。
2. **MIME 校验漏洞（3 条）**：R2-H1（分片绕过）、R2-M2（浅嗅探+缺 .xlsx）、R1-M7（trim）
   → `detectMimeType` 抽可复用方法，三条上传路径统一调用 + trim + 深嗅探。
3. **无界读取/OOM（6 条）**：R1-H2（列表）、R2-H2（OOXML）、R2-M3（plain text）、R2-M1（temp）、R2-L1（byte[]）、R2-L2（表格）
   → 系统加 `BoundedInputStream` + 计数熔断 + 分页。
4. **资源泄漏/非原子（3 条）**：R1-H3（批量非原子）、R1-M5（连接 close）、R1-M4（误删活跃分片）
   → try-with-resources + 补偿事务。
5. **防御性编程缺位（3 条）**：R1-M2（parseLong）、R1-M6（启动阻塞）、R1-M9（列长假设）
   → 防御性 helper + 异步初始化 + 配置对齐。

---

## 4. R2 已确认「不可利用」的安全类（6 类，无需修，备忘）

| 威胁 | 判定 | 理由 |
|---|---|---|
| XXE | ✅ 不可利用 | POI 5.5.1 / Tika 3.3.0 默认 secure XML；仓库唯一自建 DBF 在 `PromptLoaderServiceImpl:173` 且已锁死，解析 classpath 资源 |
| Zip-slip / 路径穿越 | ✅ 不可利用 | 无按名解压；`createTempFile` 随机名 + `walkFileTree` 删除；存储 key 是 MinIO 对象名 |
| SSRF | ✅ 不可利用 | 无解析器/loader 接收客户端 URL；parser 包零 `new URL(`/`HttpClient` |
| Java 原生反序列化 | ✅ 不可利用 | 无 `ObjectInputStream`/`XMLDecoder`/Kryo；Jackson 仅 `@Valid` DTO |
| ReDoS | ✅ 不可利用 | 攻击可控内容上正则全锚定线性，均预编译 |
| 编码攻击 | ✅ 低风险 | `UniversalDetector` + BOM 处理 + `safeCharset` 兜底 UTF-8 |

---

## 5. 依赖姿态

| 库 | 版本 | 状态 |
|---|---|---|
| Apache POI | 5.5.1 | 安全主线，`ZipSecureFile` 默认生效（需 pin，见 R2-H2） |
| Apache Tika | 3.3.0 | 安全主线，默认禁外部实体/网络 |
| Apache PDFBox | 3.0.7 | 近期版本 |
| junrar | 7.5.10 | 已 pin（排除 Tika 传递依赖） |
| **commons-compress** | 多版本(至 1.26.1) | ⚠️ **实际解析版本未验证，需 pin ≥1.27.x**（R2-Dep） |
| **commons-codec** | 未声明 | ⚠️ **U1 重构需引入/确认**（见 U1 依赖处置） |
| opendataloader-pdf-core | 1.11.0 | 非 Central 仓库，黑盒；受 temp 边界 + 上传上限约束 |

---

## 6. 修复路线图（按 ROI 分波）

| 波次 | 内容 | 条目 | 工作量 |
|---|---|---|---|
| **W1 安全加固** | R2-H1 + R2-H2 + R2-Dep + R2-M3 | 4 | 阻塞外网暴露，必修 |
| **W2 正确性小修** | R1-H1 + R1-H4 + R1-M7 + R2-L3 + R1-L1 + **U1** | 6 | 低风险高收益 |
| **W3 无界读取** | R1-H2（分页）+ R1-M6（异步初始化）+ R2-M1 | 3 | 中等 |
| **W4 资源/原子性** | R1-H3 + R1-M5 + R1-M4 | 3 | 中等 |
| **W5 授权与长尾** | R1-M1 + R1-M2 + R1-M3 + R1-M8 + R1-M9 + R1-L2~L5 + R2-L1/L2 | 余项 | 渐进 |

---

## 7. 覆盖度声明

**R1（java-reviewer）已读**：controllers、upload（controller/service/orphan cleaner/personal strategy）、ETL（pipeline/dispatch/status/executor/strategies/consumer）、retrieval（hybrid/MMR/rerank/normalizer/parent-doc）、services、parsers（factory+PDF）、mappers、entity、chunk strategies、config。
**R1 未深读**：Docx/Excel/Ppt/Tika/OpenDataLoader/Markdown/PlainText 解析器、EncodingDetector —— 由 R2 补齐。

**R2（security-reviewer）已读**：parser 全集（DocumentParser/Factory/Docx/Excel/Ppt/Tika/OpenDataLoader/Pdf/Markdown/PlainText/EncodingDetector/NamedByteArrayResource/DocumentParseException）、upload/storage/validation（DocumentValidator/MinioFileStorageService/DocumentExtractor/ChunkUploadServiceImpl/ChunkUploadController/PersonalUploadStrategy/TeamUploadStrategy 头部/EtlDispatchServiceImpl/EtlExecutorConfig/DocumentProperties）。
**R2 未覆盖**：etl 策略类（操作已解析文本，无原始字节风险，R1 已覆盖）、chunk 策略、retrieval/agent（查询时非摄入）、opendataloader-pdf-core 内部（闭源 JAR，仅按调用模式评估）、**未做动态/fuzz 测试（纯静态读）**、`mvn dependency:tree` 未跑（commons-compress 实际版本待确认）。

---

*本报告由 ECC `java-reviewer` + `security-reviewer` 两轮自动审查生成，人工核验 MD5 用法（U1）并整合。所有发现可在后续 Trellis 任务（W1–W5）PRD 中引用。*
