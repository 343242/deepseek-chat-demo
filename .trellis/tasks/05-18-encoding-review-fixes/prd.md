# UTF-8 编码检测功能审查修复 PRD

**任务 ID**: 05-18-encoding-review-fixes
**关联 commit**: efed601 (feat: add encoding detection for text/markdown uploads)
**分支**: eval-rag-dev
**审查日期**: 2026-05-18

## 背景

commit `efed601` 为 `PlainTextDocumentParser` 和 `MarkdownDocumentParser` 新增了基于 juniversalchardet 的编码自动检测功能（GBK/GB2312/GB18030 → UTF-8 转码）。36 个测试全绿。

按项目 spec 规范进行 code review 后，发现 **3 个 BLOCKER + 4 个 P1 + 3 个 P2**。

本任务按修正优先级分为 3 个 Phase 逐步修复。

---

## Phase 1: BLOCKER 修复（3 项）

### 1.1 B1: PlainTextDocumentParser 异常类型不一致

**现状**：`PlainTextDocumentParser.parse()` 抛 `RuntimeException`
**问题**：项目已有 `DocumentParseException(fileName, parserName, message, cause)` 四参数构造器，其他 Parser 应统一使用。`RuntimeException` 缺少文件级上下文，上游无法区分编码错误和 IO 错误。
**修复**：
```java
// Before
throw new RuntimeException("Failed to parse plain text: " + resource.getFilename(), e);

// After
throw new DocumentParseException(
    resource.getFilename(), "plain-text",
    "Failed to parse plain text", e);
```
**文件**: `PlainTextDocumentParser.java`

### 1.2 B2: Charset.forName 可能抛 UnsupportedCharsetException

**现状**：`detectAndTranscode` 中 `Charset.forName(detectedEncoding)` 未防护
**问题**：如果 UniversalDetector 返回 JVM 不支持的编码名，直接抛异常。外层 catch 虽兜住但包装为"读取资源失败"，语义不准确。
**修复**：用 `try-catch(UnsupportedCharsetException)` 包裹，降级为 UTF-8 + WARN 日志。抽取 `safeCharset(String encodingName)` 私有方法。
**文件**: `EncodingDetector.java`

### 1.3 B3: detectEncoding 缺少防御性处理

**现状**：`detectEncoding()` 直接 new UniversalDetector → handleData → dataEnd → getDetectedCharset
**问题**：
1. 未做 null 防护（bytes 为 null 时 NPE）
2. 未调用 `detector.reset()`（防御性编码）
3. 违反项目铁律"防御性编码"精神

**修复**：
```java
private static String detectEncoding(byte[] bytes) {
    if (bytes == null || bytes.length == 0) return null;
    UniversalDetector detector = new UniversalDetector(null);
    try {
        detector.handleData(bytes);
        detector.dataEnd();
        return detector.getDetectedCharset();
    } finally {
        detector.reset();
    }
}
```
**文件**: `EncodingDetector.java`

---

## Phase 2: P1 修复（4 项）

### 2.1 P1-1: 大文件内存双倍问题

**现状**：`detectAndTranscode` 全量 `readAllBytes()` 到内存
**问题**：50MB 文件 → 原始 bytes + UTF-8 bytes + 中间 String ≈ 150MB 峰值
**修复**：
- 添加大小阈值常量 `MAX_DETECT_SIZE = 10 * 1024 * 1024`（10MB）
- 超过阈值的文件只取前 10KB 做编码检测（UniversalDetector 只需头部特征即可判断）
- 全量内容按检测到的编码流式读取
**文件**: `EncodingDetector.java`

### 2.2 P1-2: 补充 Big5 / Shift-JIS 编码测试

**现状**：只测了 GBK/GB2312/GB18030/UTF-8/ASCII
**修复**：在 `EncodingDetectorTest` 新增：
- Big5 编码测试（繁体中文）
- Shift-JIS 编码测试（日文）
- 乱码恢复测试（错误编码的字节 → 检测失败 → 降级 UTF-8）
**文件**: `EncodingDetectorTest.java`

### 2.3 P1-3: isUtf8Compatible 没有覆盖 BOM 场景

**现状**：只检查 4 种名称（UTF-8/UTF8/ASCII/US-ASCII）
**问题**：UTF-8 with BOM 可能被识别为特殊名称
**修复**：改用 `StandardCharsets.UTF_8.contains(detectedCharset)` 语义判断 + 名称模糊匹配
**文件**: `EncodingDetector.java`

### 2.4 P1-4: PlainTextDocumentParser Javadoc 未更新

**现状**：Javadoc 仍写"直接读取文本内容"
**修复**：补充编码自动检测说明
**文件**: `PlainTextDocumentParser.java`

---

## Phase 3: P2 优化（3 项）

### 3.1 P2-1: EncodingDetector 改为 Spring Bean

**现状**：纯 static 工具类
**原因**：如果未来需要配置（最大检测字节数、允许编码白名单），static 不方便扩展
**修复**：
- 改为 `@Component` + `@ConfigurationProperties` 绑定
- 保留 static 便捷方法作为内部实现委托
- 或者保持 static 但预留 `EncodingDetectorProperties` 配置类入口

### 3.2 P2-2: MarkdownDocumentParser 转码后丢失 filename

**现状**：`ByteArrayResource.getFilename()` 返回 null
**修复**：创建子类或用自定义 Resource 保留原始 filename
```java
Resource transcoded = EncodingDetector.detectAndTranscode(resource);
// 包装为保留 filename 的 Resource
Resource named = new NamedByteArrayResource(
    transcoded.getInputStream().readAllBytes(), resource.getFilename());
```

### 3.3 P2-3: GB18030 测试用例补充注释

**现状**：测试用了 `㐀 (U+3400)` 但无注释说明为什么选这个字符
**修复**：加注释说明该字符在 GB18030 四字节区间，GBK 无法编码

---

## 验收标准

- [ ] Phase 1 全部修复后：编译通过 + 原有 36 测试 + 新增测试全绿
- [ ] Phase 2 全部修复后：编译通过 + 所有测试全绿
- [ ] Phase 3 按需修复，不影响功能
- [ ] 每个 Phase 完成后 git commit + push，commit message 写清楚修复了什么
