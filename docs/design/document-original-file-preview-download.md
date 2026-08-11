# 原文件预览与下载设计

> 状态：实现就绪
>
> 范围：上传校验、文档授权、MinIO 流式读取与二进制 HTTP 输出、受限文本渲染、Markdown / HTML 安全隔离预览

## 1. 目标定义

后端提供两个同源鉴权端点：

```http
GET /api/documents/{id}/preview
HEAD /api/documents/{id}/preview
GET /api/documents/{id}/download
HEAD /api/documents/{id}/download
```

两者通过同一个授权与文件读取服务访问 MinIO。预览按文件类型走两条不同的输出路径：

- **透传路径**（PDF 预览、所有 download）：GET 以惰性流输出，支持完整读取和单段 HTTP Range，不在 JVM 中形成完整 byte 数组；HEAD 只返回 stat 可得的元数据，不打开内容流。PDF 预览采用 `inline`；所有 download 采用 `attachment`。
- **渲染路径**（TXT、Markdown、HTML 预览）：仅在对象不超过专用预览上限时读取完整内容，做编码检测、统一 UTF-8 编码、Markdown 渲染和 HTML 净化后输出。GET 不支持 Range，始终返回完整渲染结果；HEAD 不渲染内容，也不承诺 `Content-Length`。

类型规则：

- `preview` 仅允许 PDF 与文本类（TXT / MD / HTML）安全预览类型。
- `download` 允许所有已接受的原文件类型，`attachment`。
- OOXML（DOCX / PPTX / XLSX）只允许下载，不可预览。

MinIO 只在内部网络提供服务，浏览器始终访问应用端点。该部署只采用后端代理流式传输，不向前端返回对象存储地址或凭据。

## 2. 根因与必须同时修正的定义

原文件已经在上传时写入 MinIO，`rag_document` 也保存 bucket、storage key、文件名、大小和 MIME。缺陷不在对象缺失，而在应用层没有授权后的二进制输出契约。

同时，当前上传校验虽然执行服务端 MIME 探测，个人和团队上传仍把 `MultipartFile.getContentType()` 写入数据库（分片上传路径已落服务端检测值，是三者中的例外）。浏览器声明值不是可信安全边界，不能用于决定是否内联预览。

因此本功能必须同时完成两项根因修正：

1. 将 `rag_document.mime_type` 重新定义为服务端校验后得到的规范 MIME，所有上传路径按该定义写入现有字段。
2. 用一个统一对象读取契约替换存储层现有的流式读取接口，并让 ETL 与 HTTP 文件输出共同使用它。

> 前置条件：当前为预生产阶段，`rag_document` 无需要承载的存量数据，因此不增加数据修正逻辑或双轨读取代码。若在已有数据的环境上线，需要先执行一次按对象真实内容重探测 MIME 的迁移。

说明：现有 `FileStorageService.download(bucket, objectKey)` 返回的 `MinioStreamResource` 本身已是流式（`contentLength()` 返回 `-1` 以避免全量加载），并非全量下载。真正缺陷在于它不执行 `statObject`、不暴露已知大小、不支持 Range，无法满足 HTTP Range 与 HEAD 契约。本设计用 `open()` handle 替换它，迁移本质是"补 stat + Range"，而非"从全量改为流式"。

## 3. 规范 MIME 信任边界

### 3.1 上传校验结果

`DocumentValidator` 不再只返回 void，而是返回经过校验的元数据：

```java
public record ValidatedDocumentFile(
        String fileName,
        long fileSize,
        String canonicalMimeType
) {}
```

个人上传、团队上传和分片上传完成流程必须先取得 `ValidatedDocumentFile`，再使用其中的 `canonicalMimeType`：

- 写入 `rag_document.mime_type`；
- 作为 MinIO 对象的 `Content-Type`；
- 驱动后续预览策略和 DTO 映射。

任何路径都不能持久化 `MultipartFile.getContentType()`，也不能在读取阶段用客户端声明值覆盖规范值。

### 3.2 规范化规则

`DocumentMimePolicy` 是类型允许集合和规范化规则的唯一来源：

| 文件类别 | 服务端确认 | 规范 MIME |
| --- | --- | --- |
| PDF | 内容探测为 PDF | `application/pdf` |
| TXT | 内容探测为文本且扩展名为 `.txt` | `text/plain` |
| Markdown | 内容探测为文本且扩展名为 `.md` / `.markdown` | `text/markdown` |
| HTML | 内容探测为文本且扩展名为 `.html` / `.htm` | `text/html` |
| DOCX | 内容确认为 OOXML 且扩展名为 `.docx` | `application/vnd.openxmlformats-officedocument.wordprocessingml.document` |
| PPTX | 内容确认为 OOXML 且扩展名为 `.pptx` | `application/vnd.openxmlformats-officedocument.presentationml.presentation` |
| XLSX | 内容确认为 OOXML 且扩展名为 `.xlsx` | `application/vnd.openxmlformats-officedocument.spreadsheetml.sheet` |

内容类别与扩展名不一致时拒绝上传。OOXML 必须验证 ZIP 容器及对应包结构（`[Content_Types].xml` 与对应 part 关系），不能只根据 ZIP 魔数或扩展名放行。

**探测机制**：现有 `DocumentValidator.detectMimeType` 只读取前 8 字节做魔数匹配，无法真正识别 OOXML 包结构。本设计将输入包装为文件支撑的 `TikaInputStream`（在上传大小上限内落临时文件并在关闭时删除），再使用项目现有 Tika 3.3.0 检测候选 MIME。禁止直接调用 `Tika.detect(InputStream, name)`：普通 InputStream 会进入 Tika 的 streaming detection，只解析 `[Content_Types].xml` 即可能给出 OOXML 类型，不能兑现包关系校验。

对 DOCX / PPTX / XLSX，候选 MIME 之后还必须通过项目已有 Apache POI `OPCPackage.open(...)` 做结构确认：包中恰有可解析的 office document 主关系、关系目标 part 存在，且目标 part 的 content type 与扩展名对应。Tika 检测结果和扩展名再由 `DocumentMimePolicy` 做一致性二次校验。客户端声明 MIME 只可作为诊断信息，不参与最终类型决策；加密包、损坏包、结构不完整包和触发 POI ZIP 安全限制的包一律拒绝。

`DocumentMimePolicy` 解析现有 `DocumentProperties.allowedMimeTypes`，在应用启动时将别名规范化并校验其属于上表：`text/x-markdown` 归一化为 `text/markdown`，未知配置值使应用启动失败。运行时只通过该 Policy 获取启用的规范 MIME 集合，不直接读取或拆分配置字符串。`DocumentValidator` 内现有的 `getAllowedMimeTypes()` 双检锁缓存随之删除，避免两套解析路径并存。

## 4. 预览能力

预览按规范 MIME 和文件大小选择输出路径。`DocumentProperties` 新增 `maxPreviewFileSize`，默认 `5MB`，只限制需要全量消费的文本预览，不影响原文件下载和 PDF 透传。`DocumentPreviewPolicy` 返回确定的输出策略：

```java
public sealed interface PreviewStrategy {
    record PassThrough(String responseContentType) implements PreviewStrategy {}                       // PDF
    record Transform(String responseContentType, TransformKind kind, long maxInputBytes)
            implements PreviewStrategy {}                                                              // TXT/MD/HTML
    record Deny(DenyReason reason) implements PreviewStrategy {}                                       // OOXML/超限
}

public enum TransformKind { DETECT_CHARSET, RENDER_MARKDOWN, SANITIZE_HTML }
public enum DenyReason { UNSUPPORTED_TYPE, PREVIEW_TOO_LARGE }
```

| 规范 MIME | 路径 | preview 输出 Content-Type | `previewable` |
| --- | --- | --- | --- |
| `application/pdf` | 透传 | `application/pdf` | `true` |
| `text/plain` | 渲染 | `text/plain; charset=UTF-8` | 文件大小不超过预览上限时为 `true` |
| `text/markdown` | 渲染 | `text/html; charset=UTF-8` | 文件大小不超过预览上限时为 `true` |
| `text/html` | 渲染 | `text/html; charset=UTF-8` | 文件大小不超过预览上限时为 `true` |
| 三种 OOXML MIME | 拒绝预览，只允许下载 | — | `false` |

### 4.1 透传路径（PDF）

PDF 由浏览器内置阅读器渲染，后端只做惰性流式输出，并支持 Range（阅读器翻页时按段取字节）。对象内容不进 JVM 完整数组，规则见第 6、8 节。PDF 的脚本能力依赖浏览器内置阅读器的沙箱约束，后端不再额外处理。

### 4.2 渲染路径（TXT / Markdown / HTML）

预览输出是生成内容（检测编码后的文本、渲染后的 HTML、净化后的 HTML），其字节长度与对象原始字节无关。因此渲染路径先用 MinIO stat 的真实大小执行 `maxPreviewFileSize` 校验，只有通过后才读取完整对象；超过上限时不打开内容流。生成结果也必须限制为输入上限的两倍，超出按预览过大拒绝。渲染路径不参与 Range 与惰性流：

- **TXT**：读全量 → `EncodingDetector`（项目已有）按检测编码解码为 String → 明确编码为 UTF-8 字节 → 以 `text/plain; charset=UTF-8` 输出。GBK / Big5 只决定输入解码方式，不作为响应编码。
- **Markdown**：读全量 → 检测编码并解码 → 使用项目依赖树中已有的 CommonMark 0.22.0 渲染为 HTML → 使用 Jsoup 1.22.1 净化 → 明确编码为 UTF-8 字节 → 以 `text/html; charset=UTF-8` 输出。CommonMark 允许内联原始 HTML，因此渲染产物必须净化。
- **HTML**：读全量 → 检测编码并解码 → 使用同一 Jsoup `Safelist` 净化 → 明确编码为 UTF-8 字节 → 以 `text/html; charset=UTF-8` 输出。

### 4.3 XSS 净化边界

净化发生在预览输出时，不依赖上传时校验，但不能作为唯一安全边界。本设计同时采用“严格净化 + 浏览器隔离”两道边界：

- 团队文档的预览者通常不是上传者：授权矩阵允许团队成员预览他人上传的 `COMPLETED` 文档。若只在上传时校验，恶意上传者可在自己上传时绕过，从而在队友预览时执行同源脚本。
- download 端点必须忠实返回原始文件（见第 8 节），因此 MinIO 中必须保留原始字节，不能以"净化后内容"覆盖存储。

上传时仍做一次轻量校验（拒绝明显畸形或超限的 HTML / MD），作为纵深防御与快速失败手段，但不作为渲染安全的依据。

Jsoup Safelist 只允许无主动行为的排版标签（标题、段落、换行、引用、代码、列表、表格、加粗、斜体、删除线和链接）以及链接的 `href` / `title`；链接协议只允许 `http` / `https`，并强制 `rel="noopener noreferrer"`。必须移除 `script`、`style`、`base`、`meta`、`form`、`iframe`、`object`、`embed`、`svg`、`math`、图片、所有 `on*` 属性、`style` / `class` / `id`、`javascript:` 和 `data:` URL。

Markdown / HTML 响应额外设置：

```http
Content-Security-Policy: sandbox; default-src 'none'; base-uri 'none'; form-action 'none'; frame-ancestors 'self'
```

前端只能把 preview URL 作为不带 `allow-same-origin` 的 sandbox iframe `src` 打开，禁止 fetch 后通过 `innerHTML` 注入主应用 DOM。响应 CSP 负责直接访问时的隔离，iframe sandbox 负责前端集成时的隔离。

对 OOXML 调用 preview 端点时，抛出 `ClientException(ClientErrorCode.DOCUMENT_PREVIEW_UNSUPPORTED)`（新增 RAG 客户端错误码 104009）；文本对象超过预览上限时抛出 `DOCUMENT_PREVIEW_TOO_LARGE`（新增 104010）。download 不受预览策略限制。

`DocumentDTO` 必须包含非空 primitive `boolean previewable`。DTO 使用数据库中的规范 MIME 与文件大小计算该值；preview 端点使用相同 Policy，并以 MinIO stat 的真实大小做最终判定。正常数据下两者一致；若数据库大小与对象大小不一致，端点采用更保守的较大值并记录告警。

## 5. 授权边界

文件读取沿用文档应用服务已有的可见性语义：

| 文档 | 访问者 | 结果 |
| --- | --- | --- |
| 个人文档 | owner | 允许 |
| 个人文档 | 其他用户 | 拒绝 |
| 团队 `COMPLETED` | 团队成员 | 允许 |
| 团队非 `COMPLETED` | uploader、ADMIN、CREATOR | 允许 |
| 团队非 `COMPLETED` | 其他 MEMBER | 文档不存在 |
| 逻辑删除文档 | 任意用户 | 文档不存在 |

该矩阵与现有 `DocumentApplicationServiceImpl.verifyAccess(id)` 的 R1-M1 语义一致（owner / manager / uploader 放行；非 `COMPLETED` 对非管理者返回 `DOCUMENT_NOT_FOUND` 以不泄露存在性；`@TableLogic` 自动过滤逻辑删除）。本设计复用该统一权限判断，不另起一套。

说明：`SUPERSEDED`、`PENDING_APPROVAL`、`REJECTED` 等状态对 owner / manager 仍可访问（manager 检查通过即放行，不因状态拒绝），因此它们可被预览 / 下载。列表接口对全员排除 `SUPERSEDED` 与"按 id 直达预览"有意不同，不在预览端点额外加状态过滤。

应用服务新增内部读取契约：

```java
public record AuthorizedDocumentFile(
        Long documentId,
        String fileName,
        long declaredFileSize,
        String canonicalMimeType,
        String bucket,
        String objectKey
) {}
```

`DocumentApplicationService.authorizeFileRead(id)` 调用现有统一权限判断后返回该描述符。`bucket` 和 `objectKey` 只在该内部类型中流转，**不进入 Controller、不进日志、不进 JSON DTO**。该类型携带存储定位信息，是面向同模块文件服务的内部契约，不向外暴露。权限判断必须在任何 MinIO stat 或流打开操作之前完成。

## 6. 统一存储读取契约

`FileStorageService` 保留上传职责，并用一个 `open` 方法定义所有对象读取：

```java
public interface FileStorageService {
    void ensureBucketExists(String bucket);
    void upload(String bucket, String objectKey, Resource resource, String mimeType);

    StoredObjectHandle open(String bucket, String objectKey);

    void delete(String bucket, String objectKey);
}

public sealed interface ObjectReadRange {
    record Full() implements ObjectReadRange {}
    record Bytes(long offset, long length) implements ObjectReadRange {}
}

public interface StoredObjectHandle {
    long totalSize();
    StoredObjectContent content(ObjectReadRange range);
}

public record StoredObjectContent(
        Resource resource,
        long offset,
        long contentLength
) {}
```

旧的 `download(bucket, objectKey)`（无 stat、`contentLength` 返回 `-1`、无 Range）和零调用方的 `presignedUrl(...)` 从接口及实现中删除；内嵌的 `MinioStreamResource` 及其测试 `MinioStreamResourceTest` 一并移除。`DocumentExtractor` 等 ETL 调用方改为 `open(...).content(Full)`，HTTP 透传调用方使用同一个 handle，渲染调用方同样以 `open(...).content(Full)` 取得内容后全量消费。代码库中只有这一条对象读取路径。

`MinioFileStorageService.open` 执行一次 `statObject` 并返回包含准确元数据的 handle。透传场景先用 `totalSize` 解析包括后缀范围在内的 HTTP Range，再调用 `content(Full|Bytes)` 得到惰性 `Resource`。`Resource.getInputStream()` 被响应写出器真正读取时才调用 MinIO `getObject`：

- `Full` 不设置 offset / length；
- `Bytes` 设置精确 offset / length；
- `StoredObjectContent.contentLength()` 返回 record 中已知长度，**不通过读流计算**；
- 流正常结束、读取异常和客户端断开时都关闭 `GetObjectResponse`。

`Bytes` 必须校验 `offset >= 0`、`length > 0`、`offset < totalSize` 且 `length <= totalSize - offset`，避免越界和加法溢出。对象 Content-Type 不进入读取 handle，也不参与响应类型决策；响应类型始终来自授权后取得的规范 MIME。

HEAD 只构造 handle 和元数据结果，绝不调用 `content(...)`。Controller 显式返回无 body 的 `ResponseEntity<Void>`，不能仅依赖 Servlet / Spring 对 HEAD body 的隐式抑制，因为消息转换器仍可能调用 `Resource.getInputStream()`。

本设计不生成 ETag / Last-Modified，也不引入 304。GET 同时收到 `Range` 与 `If-Range` 时，因为没有可用于强比较的校验器，必须忽略 Range 并返回完整 `200 OK`；不能把 `If-Range` 当作不存在后继续返回 206。若后续需要缓存协商再单独引入。

## 7. 文件应用服务

Controller 只依赖 `DocumentFileService`。透传与渲染两条路径在服务内分流：

```text
documentId + preview/download + GET/HEAD + Range/If-Range
  -> authorizeFileRead                            # 复用统一权限判断
  -> DocumentPreviewPolicy（preview 请求）         # 先按规范 MIME 拒绝不支持类型
  -> FileStorageService.open                      # stat；与 declaredFileSize 比较
  -> DocumentPreviewPolicy（preview 请求）         # 按真实大小执行渲染上限
  -> 分流：
       HEAD：只返回 Metadata，不调用 content
       GET 透传：解析 Range -> handle.content(Full|Bytes)
       GET 渲染：content(Full) -> 有界全量消费 -> 检测编码 / 渲染 / 净化 / UTF-8 编码
  -> DocumentFileResult
```

返回给 Controller 的传输结果使用封闭类型：

```java
public sealed interface DocumentFileResult {
    record Body(
            HttpStatus status,
            Resource resource,        // 透传：MinIO 惰性 Resource；渲染：生成内容的 Resource
            long contentLength,
            long totalSize,            // 206 的完整对象大小；200 时等于 contentLength
            long offset,              // 渲染路径固定 0
            String responseContentType,
            String fileName,
            Disposition disposition,
            RangeCapability rangeCapability
    ) implements DocumentFileResult {}

    record Metadata(
            HttpStatus status,
            Long contentLength,        // 渲染 HEAD 为 null；透传 HEAD 为 stat 大小
            String responseContentType,
            String fileName,
            Disposition disposition,
            RangeCapability rangeCapability
    ) implements DocumentFileResult {}

    record RangeNotSatisfiable(long totalSize)
            implements DocumentFileResult {}
}

public enum RangeCapability { BYTES, NONE }
```

`RangeNotSatisfiable` 仅可能出现在透传 GET，是传输协议结果，不抛给 `GlobalExceptionHandler`。渲染 GET 不支持 Range：请求带 `Range` 头时按“忽略 Range、返回完整渲染结果”处理。HEAD 对所有路径忽略 Range。权限、文档不存在、不可预览、预览过大和对象存储失败仍走项目现有业务错误响应。

数据库文件大小用于列表展示和 DTO `previewable` 的快速计算，不作为 HTTP 传输长度。透传路径的 `Content-Length`、Range 计算和 416 响应全部使用 MinIO stat 的真实大小；两者不一致时记录不含对象 key 的告警，并在预览大小策略中采用两者较大值。渲染 GET 的 `Content-Length` 等于生成内容的 UTF-8 字节长度。

## 8. Range 与 HTTP 契约

**Range 仅适用于透传 GET**（PDF 预览、所有 download）。渲染 GET 始终返回完整生成内容和 `200 OK`；HEAD 的 Range 语义未由 HTTP 定义，因此一律忽略。

透传路径使用 Spring `HttpRange.parseRanges`，规则固定如下：

| 请求 | 结果 |
| --- | --- |
| 无 `Range` | `200 OK`，完整对象 |
| 一个合法范围 | `206 Partial Content`，精确 offset / length |
| 一个合法后缀范围 | `206 Partial Content`，按总大小换算 |
| 同时包含 `If-Range` | 忽略 Range，`200 OK` 返回完整对象 |
| 语法错误 | `416 Range Not Satisfiable` |
| 起点越界或空范围 | `416 Range Not Satisfiable` |
| 多个语法合法范围 | 不实现 multipart；忽略 Range，`200 OK` 返回完整对象 |

多段范围不是“不可满足范围”，不能仅因服务端未实现 `multipart/byteranges` 就返回 416。这里选择 RFC 允许的忽略 Range 并返回完整 200，以保持单读取路径。单段范围使用 `HttpRange.getRangeStart(totalSize)` 与 `getRangeEnd(totalSize)` 计算 offset / length，再交给 MinIO `getObject(offset, length)`；禁止对已经从 MinIO 截取的 Resource 调用 `toResourceRegion()`，否则 Spring 写出器会再次跳过 offset。

HEAD 显式映射但不复用 GET body 写出：忽略 `Range` / `If-Range` 并返回 `200 OK`。PDF preview 和所有 download 的 `Content-Length` 来自 MinIO stat；TXT / MD / HTML preview 因不读取和渲染内容而省略 `Content-Length`。所有 HEAD 都不得调用 `handle.content(...)` 或 MinIO `getObject`。

Controller 对 `RangeNotSatisfiable` 直接构造：

```http
HTTP/1.1 416 Range Not Satisfiable
Content-Range: bytes */{totalSize}
Accept-Ranges: bytes
```

该结果不经过会把状态包装为 HTTP 200 的全局异常处理器。

所有成功响应包含：

```http
Cache-Control: private, no-store
X-Content-Type-Options: nosniff
```

透传 GET / HEAD 额外包含 `Accept-Ranges: bytes`；透传响应及渲染 GET 包含准确 `Content-Length`。渲染 GET / HEAD 包含 `Accept-Ranges: none`，其中渲染 HEAD 省略 `Content-Length`。

206 额外包含：

```http
Content-Range: bytes {offset}-{end}/{totalSize}
```

preview 使用 `inline`，download 使用 `attachment`。文件名必须通过 Spring `ContentDisposition` 以 UTF-8 构造，禁止手工拼接。download 端点忠实返回原始字节（不做净化、不做编码转换），满足“原文件下载”。GET 成功响应使用 `ResponseEntity<Resource>`，HEAD 使用 `ResponseEntity<Void>`，二者均不包装 `GlobalResponse`。Markdown / HTML preview 还必须包含第 4.3 节的 CSP。

## 9. 错误、安全与观测

- 对象不存在、MinIO 超时或读取失败不能暴露 bucket、objectKey、内部 endpoint 或底层异常文本。
- **对象存储异常脱敏**：现有 `FileStorageException` 会进入 `GlobalExceptionHandler` 的兜底处理；客户端实际收到 HTTP 200 + 通用 `INTERNAL_ERROR`，不会直接看到 bucket / objectKey，但服务端异常堆栈会记录包含定位信息的 message。MinIO 属于远程基础设施，设计将异常翻译为 `RemoteException(RemoteErrorCode.FILE_STORAGE_UNAVAILABLE)`（新增 300004），对外消息固定为“文件存储暂不可用”，detail 不得包含对象定位字段，并保留 cause。内部日志只记录 documentId、操作类型、MinIO 错误分类和 traceId，不记录 bucket / objectKey / endpoint。
- stat 或首次打开内容流在响应提交前失败时，可以由全局异常处理器返回业务错误；正文已开始传输后的读取失败或客户端断开无法改写为 JSON 错误，必须关闭流、终止连接并只记录脱敏指标 / 日志。不得承诺已提交的 200 / 206 能在中途切换为业务错误响应。
- preview 渲染路径必须同时执行有界读取、HTML 净化和浏览器隔离；MD 渲染产物与 HTML 使用同一 Safelist 和 CSP。
- 每个 GET 请求最多打开一个 MinIO 内容流；HEAD 和预览大小超限请求不打开内容流。
- 日志和指标记录 documentId、preview / download、200 / 206 / 416、字节量、耗时、客户端中断和错误类别。
- 日志不记录完整文件名、对象定位信息或授权头。
- 端点继续受 `@PreAuthorize("isAuthenticated()")` 保护。

## 10. 测试设计

上传与 MIME 测试：

- 个人、团队、分片完成三条路径都持久化服务端规范 MIME，并以该值上传对象。
- 伪造客户端 `Content-Type` 不能改变规范结果。
- PDF、文本、Markdown、HTML、DOCX、PPTX、XLSX 的规范值正确。
- OOXML 校验拒绝缺 `[Content_Types].xml`、缺 `_rels/.rels`、主关系目标不存在、content type 与扩展名不符、损坏、加密或触发 ZIP 安全限制的包。

权限测试：

- 个人 owner、团队 MEMBER / ADMIN / CREATOR / uploader 的允许和拒绝矩阵。
- 不存在、逻辑删除、无权文档不会调用 `FileStorageService.open`。
- `SUPERSEDED` 等状态的 owner / manager 仍可预览 / 下载。
- 新文件端点不改变 get / history / chunks / delete / retry 的权限行为。

HTTP 测试：

- preview / download 的 inline / attachment、UTF-8 文件名、缓存头和 nosniff。
- PDF 透传预览：完整 GET 为 200；首段、中段、后缀段为 206，响应字节与请求范围精确一致且无双重 offset；语法错误和越界返回真实 416 与 `bytes */total`。
- 合法多段 Range、`Range + If-Range` 返回完整 200；不返回错误的 416。
- download 透传：所有类型 attachment，忠实返回原始字节。
- 文本 / MD / HTML 预览：始终 200，不支持 Range（带 `Range` 头时返回完整渲染结果）。
- 编码：UTF-8 / GBK / Big5 输入都被正确解码，响应字节和 `charset=UTF-8` 一致且不乱码。
- MD 预览渲染为 HTML；HTML 与 MD 渲染产物均按明确 Safelist 净化，响应包含 CSP；`script`、事件属性、危险 URL、form / iframe / svg 等被剥离。
- 文本对象超过预览上限时 `previewable=false`，preview 返回 `DOCUMENT_PREVIEW_TOO_LARGE`，且不打开内容流。
- OOXML 预览被拒绝（`DOCUMENT_PREVIEW_UNSUPPORTED`）。
- HEAD 忽略 Range 并返回 200；透传 HEAD 返回 stat 长度，渲染 HEAD 省略长度，所有 HEAD 均不调用 `content(...)` / `getObject`。

MinIO 集成与资源测试：

- `Full` 与 `Bytes` 正确映射到 MinIO 参数。
- ETL 通过统一 `open(...).content(Full)` 契约提取文档。
- 渲染路径与透传路径共享同一 `open` 入口。
- 正常完成、读取异常和客户端断开都关闭底层响应。
- stat / 首次打开失败在响应提交前映射为业务错误；传输中断不会尝试追加 JSON 错误体。
- 50MB 并发传输（透传）时 JVM 堆不随文件总字节数线性增长。
- 渲染输入 / 输出边界和并发预览不会突破预期堆内存上限。
- 数据库大小与对象大小不一致时以对象真实大小生成响应。
- MinIO 故障（对象不存在 / 超时）映射为 `FILE_STORAGE_UNAVAILABLE`，响应体不含 bucket / objectKey。

## 11. 实现改动范围

| 文件 | 动作 |
| --- | --- |
| `rag/service/impl/DocumentValidator.java` | 返回含规范 MIME 的校验结果；使用文件支撑的 Tika 检测 + POI OPC 结构确认；删除 `getAllowedMimeTypes` 缓存 |
| `rag/service/DocumentMimePolicy.java` | 单点定义允许类型与规范化规则，启动期校验配置 |
| `rag/config/DocumentProperties.java` | 由 MIME Policy 统一解析；新增 `maxPreviewFileSize`（默认 5MB） |
| `rag/service/DocumentPreviewPolicy.java` | 按规范 MIME 和文件大小单点定义 PassThrough / Transform / Deny 与 `previewable` |
| `rag/upload/PersonalUploadStrategy.java` | 持久化并上传规范 MIME |
| `team/upload/TeamUploadStrategy.java` | 持久化并上传规范 MIME |
| `rag/upload/ChunkUploadServiceImpl.java` | 分片完成时持久化并上传规范 MIME（与已有检测逻辑收敛到 Policy） |
| `rag/dto/DocumentDTO.java` 及 `toDTO` 映射 | 增加必填 `previewable` |
| `rag/service/DocumentApplicationService.java` | 增加内部 `authorizeFileRead` 与 `AuthorizedDocumentFile` |
| `rag/service/impl/DocumentApplicationServiceImpl.java` | 复用 `verifyAccess` 返回内部描述符 |
| `rag/service/FileStorageService.java` | 用统一 `open` handle 替换 `download` / `presignedUrl` |
| `rag/service/impl/MinioFileStorageService.java` | 实现 stat + 惰性 Full / Range Resource；移除内嵌 `MinioStreamResource`；存储异常翻译为脱敏 `RemoteException` |
| `rag/etl/DocumentExtractor.java` | 改用统一 `open(...).content(Full)` |
| `rag/service/DocumentFileService.java` | 编排授权、预览策略分流、Range 与渲染 / 透传输出 |
| `rag/service/DocumentRenderService.java`（新） | 有界读取、输入编码检测、CommonMark 渲染、Jsoup 净化、UTF-8 输出 |
| `rag/controller/DocumentController.java` | 新增 preview / download 的 GET 与独立无 body HEAD 响应；按策略设置 Range / CSP 头 |
| `infrastructure/.../errorcode/ClientErrorCode.java` | 新增 `DOCUMENT_PREVIEW_UNSUPPORTED`、`DOCUMENT_PREVIEW_TOO_LARGE` |
| `infrastructure/.../errorcode/RemoteErrorCode.java` | 新增 `FILE_STORAGE_UNAVAILABLE`（300004） |
| `rag/.../*Test.java` | MIME、权限、Range、渲染净化、编码、资源关闭、MinIO 集成测试 |
| `src/test/.../MinioStreamResourceTest.java` | 移除 |
| `pom.xml` | 将依赖树中已有的 CommonMark 0.22.0 与 Jsoup 1.22.1 声明为直接依赖，避免业务代码依赖传递依赖；不引入新的 artifact |

## 12. 完成标准

- 所有上传路径只持久化服务端规范 MIME。
- `DocumentDTO.previewable` 是必填 boolean，且与 preview 端点共用 MIME + 大小策略。
- ETL、HTTP 透传与 HTTP 渲染只通过 `FileStorageService.open` 读取对象。
- 透传的完整 / Range GET 使用同一个 `StoredObjectHandle` 与惰性内容契约；HEAD 只使用 handle 元数据且无 body；渲染路径在专用大小上限内读全量后变换输出，不参与 Range。
- PDF 预览与 download 的 GET 支持单段 Range；文本 / MD / HTML 预览不支持 Range，输入按检测编码解码，响应统一 UTF-8。
- MD / HTML 预览同时使用明确 Safelist、CSP 和无同源权限的 iframe sandbox；上传时存储原始字节，download 忠实返回原文件。
- 非法、越界 Range 返回真实 HTTP 416；合法多段、`If-Range` 和所有 HEAD Range 被忽略并返回完整 / 元数据 200。
- 透传过程不缓存完整文件；渲染输入和输出均有硬上限；对象存储失败经 `RemoteException` 脱敏，不暴露对象定位信息。
- 不包含旧 `download` / `presignedUrl` 方法、`MinioStreamResource`、外部对象 URL 路径、ETag / 304 条件请求、功能开关或数据库结构变更。
