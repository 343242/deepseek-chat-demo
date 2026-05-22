# PPT + XLSX 专用文档解析器设计

## 背景

当前 RAG 模块的解析器体系：

| 解析器 | 底层 | 格式 | 状态 |
|--------|------|------|------|
| PdfDocumentParser | PdfBox | PDF | ✅ 专用 |
| DocxDocumentParser | Apache POI XWPF | DOCX | ✅ 专用 |
| PlainTextDocumentParser | JDK | TXT | ✅ 专用 |
| TikaDocumentParser | Apache Tika | 兜底 | ✅ 兜底 |
| PPT/PPTX | — | — | ❌ 缺失，走 Tika |
| XLS/XLSX | — | — | ❌ 缺失，走 Tika |

**问题**：PPT 和 XLS/XLSX 走 Tika 兜底时，丢失关键结构信息：
- PPT：丢失 Slide 编号、标题层级、备注、表格结构、图片
- XLS/XLSX：丢失 Sheet 名称、行列结构、合并单元格，全部拍平成纯文本

## 目标

1. 新增 `PptDocumentParser` — 基于 Apache POI XSLF，保留 Slide 结构
2. 新增 `ExcelDocumentParser` — 基于 Apache Fesod（EasyExcel Apache 孵化版），保留表格结构
3. 两个解析器遵循现有 `DocumentParser` 接口，由 `DocumentParserFactory` 自动路由
4. 更新 `DocumentProperties.allowedMimeTypes` 补全 MIME 类型

## 依赖变更

### pom.xml 新增

```xml
<!-- Apache Fesod (EasyExcel 孵化版) — 流式读取 XLSX -->
<dependency>
    <groupId>org.apache.fesod</groupId>
    <artifactId>fesod-sheet</artifactId>
    <version>2.0.1-incubating</version>
    <exclusions>
        <!-- 项目已通过 Tika 传递引入 POI 5.5.1，排除避免版本冲突 -->
        <exclusion>
            <groupId>org.apache.poi</groupId>
            <artifactId>poi</artifactId>
        </exclusion>
        <exclusion>
            <groupId>org.apache.poi</groupId>
            <artifactId>poi-ooxml</artifactId>
        </exclusion>
        <exclusion>
            <groupId>org.apache.poi</groupId>
            <artifactId>poi-ooxml-full</artifactId>
        </exclusion>
    </exclusions>
</dependency>
```

### POI 依赖说明

Apache POI 5.5.1 已通过 `spring-ai-tika-document-reader` → `tika-parser-microsoft-module` 传递引入，包含：
- `poi` — 核心库
- `poi-ooxml` — XSLF (PPTX)、XWPF (DOCX)、XSSF (XLSX) 均在此 jar
- `poi-ooxml-full` — OOXML Schema
- `poi-scratchpad` — 老格式支持 (HSLF 等)

XSLF (PPT) 可直接使用传递依赖，无需额外声明。但如果将来移除 Tika 依赖，XSLF 会跟着消失。建议后续考虑将 `poi-ooxml` 提升为直接依赖，本次不动。

> **为什么选 Fesod 而非 FastExcel**：
> - Fesod（原 EasyExcel）是 Apache 孵化项目，社区活跃，中文文档完善
> - 基于 POI 封装，流式读取 API（`ReadListener`），大文件不 OOM
> - FastExcel 虽轻量，但社区活跃度低，且不依赖 POI 意味着多一套 XML 解析栈
> - 项目已引入 POI，Fesod 复用现有 POI 依赖，不增加额外的传递依赖树

## 类设计

### 新增文件

```
com.demo.chat.rag.parser/
├── PptDocumentParser.java       ← 新增
└── ExcelDocumentParser.java     ← 新增
```

### 修改文件

```
config.rag.com.smart.rag.DocumentProperties.java   ← 补全 MIME 类型 + 新增配置项
```

### 不变文件

- `DocumentParserFactory` — 无需改动，通过 `DocumentParser.supportedMimeTypes()` 自动发现
- `DocumentParser` 接口 — 无需改动

---

## PptDocumentParser 设计

### 支持的 MIME 类型

```
application/vnd.openxmlformats-officedocument.presentationml.presentation  (PPTX)
```

> 老格式 PPT (`application/vnd.ms-powerpoint`) 不支持——POI XSLF 仅支持 PPTX。PPT 走 Tika 兜底即可。

### 解析策略

```
SlideShow (PPTX)
  ├── Slide 1
  │   ├── Title + Content Shapes → 合并为一个 Document（text, metadata:{slide:1, shapeType:"content"})
  │   ├── Table Shape            → Document(markdown_table, metadata:{slide:1, shapeType:"table"})
  │   └── Notes                  → Document(notes, metadata:{slide:1, shapeType:"notes"})
  ├── Slide 2
  │   └── ...
  └── ...
```

**合并策略（方案 A）**：同一 Slide 的 Title 和 Content 文本合并为一个 Document，避免碎片化（PPT 单个 Shape 文本通常很短，碎片化后不利于检索）。Table 和 Notes 各自独立输出。

### 核心逻辑

1. 使用 `XMLSlideShow` 打开 PPTX 文件
2. 遍历每个 `XSLFSlide`，按 Slide 序号建立分组
3. 对每个 Slide 中的 `XSLFShape` 按类型处理：
   - **XSLFTextShape**：提取文本。判断占位符类型（`XSLFTextShape.getPlaceholder()`），Title 类型的文本前置标记
   - **XSLFTable**：遍历行列，转为 Markdown 表格字符串，独立输出为一个 Document
   - **XSLFPictureShape**：不提取图片数据，仅设置 `hasImage=true` 标记
   - **XSLFGroupShape**：递归处理内部 Shape，**递归深度上限 5 层**，防止恶意嵌套
4. **Slide Notes**：通过 `XSLFSlide.getNotes()` 获取 `XSLFNotes` 对象，再遍历其 TextShape 提取备注文本，独立输出为一个 Document
5. 文本合并时，Title 文本在最前，Content 文本按 Shape 顺序拼接，用换行分隔

### Metadata 设计

```java
metadata = {
    "parser":        "ppt",
    "mimeType":      "application/vnd.openxmlformats-officedocument.presentationml.presentation",
    "slideIndex":    0,          // Slide 序号（0-based）
    "slideCount":    12,         // 总 Slide 数
    "shapeType":     "content" | "table" | "notes",
    "hasImage":      true,       // 该 Slide 是否包含图片（仅 content 类型）
    "source":        "xxx.pptx"  // 从 resource.getFilename() 获取
};
```

> 注意：`shapeType` 不再有 `"title"` 值——Title 文本合并到 `"content"` 类型中。

### 边界情况处理

- 空 Slide（无任何 Shape 或所有 Shape 文本为空）→ 跳过
- 纯图片 Slide（无文本）→ 生成标记性 Document（`hasImage=true`，content 为 `"[图片幻灯片]"`）
- 文件密码保护 / 损坏 → 抛出 `DocumentParseException`（见异常设计）
- 递归 GroupShape 超过 5 层 → 停止递归，记录 warn 日志

---

## ExcelDocumentParser 设计

### 支持的 MIME 类型

```
application/vnd.openxmlformats-officedocument.spreadsheetml.sheet  (XLSX)
```

> 老格式 XLS (`application/vnd.ms-excel`) Fesod 不支持（需要 `poi-scratchpad`），走 Tika 兜底。

### 解析策略

```
Workbook (XLSX)
  ├── Sheet 1 "销售数据"
  │   ├── Header Row → detectHeader() 判断
  │   ├── Data Rows  → 累积到 buffer
  │   └── → Document(markdown_table, metadata:{sheet:"销售数据"})
  ├── Sheet 2 "汇总"
  │   └── → Document(markdown_table, metadata:{sheet:"汇总"})
  └── ...
```

### 核心逻辑

1. 使用 `FesodSheet.read(inputStream)` 创建读取器
2. 不使用注解映射（`DemoData.class`），而是使用 `Map<Integer, String>` 作为行数据类型，配合自定义 `ReadListener`：
   - `invoke(Map<Integer, String> data, AnalysisContext context)` — 每行回调
   - `doAfterAllAnalysed(AnalysisContext context)` — Sheet 读取完成
3. 每个 Sheet 读取流程：
   - **第一行**：调用 `detectHeader()` 判断是否为表头
   - **小 Sheet（≤excelRowsPerChunk 行）**：在 `doAfterAllAnalysed` 中将所有行转为一个 Markdown Table Document
   - **大 Sheet（>excelRowsPerChunk 行）**：按 `excelRowsPerChunk` 行分块，每块一个 Document，第一个 Document 包含表头，后续块也重复携带表头行
4. Fesod 自动处理合并单元格（填充合并区域的值）

### 表头检测 — `detectHeader()` 私有方法

```java
/**
 * 启发式判断第一行是否为表头。
 *
 * 规则：
 * 1. 默认第一行为表头（最常见情况）
 * 2. 如果第一行所有非空值都是纯数字或日期格式 → 判定为无表头，用 A, B, C... 做列名
 * 3. 后续可在此方法中添加更多启发式规则
 *
 * @param firstRow 第一行数据（列索引 → 单元格文本）
 * @return true 表示第一行是表头
 */
private boolean detectHeader(Map<Integer, String> firstRow) { ... }
```

### Metadata 设计

```java
metadata = {
    "parser":       "excel",
    "mimeType":     "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
    "sheetName":    "销售数据",
    "sheetIndex":   0,         // Sheet 序号（0-based）
    "sheetCount":   3,         // 总 Sheet 数
    "rowCount":     150,       // 该 Sheet 非空行数
    "chunkIndex":   0,         // 分块序号（大 Sheet 时有用，单块时为 0）
    "hasHeader":    true,      // detectHeader() 的结果
    "source":       "xxx.xlsx" // 从 resource.getFilename() 获取
};
```

### 边界情况处理

- 空 Sheet（0 行）→ 跳过
- 公式单元格 → Fesod 默认取缓存的计算值，无缓存值时显示为空字符串
- 超大 Sheet → 按 `excelRowsPerChunk` 配置项分块
- 纯数字列（如 ID 列）→ 保持原值，不做格式推断
- 文件损坏 → 抛出 `DocumentParseException`

---

## DocumentProperties 变更

### allowedMimeTypes 新增

```
application/vnd.openxmlformats-officedocument.spreadsheetml.sheet
```

最终完整列表：
```
application/pdf,
application/vnd.openxmlformats-officedocument.wordprocessingml.document,
application/vnd.openxmlformats-officedocument.presentationml.presentation,
application/vnd.openxmlformats-officedocument.spreadsheetml.sheet,
text/plain,
text/markdown,
text/x-markdown,
text/html
```

> PPTX 已在现有列表中（但之前走 Tika），XLSX 是新增。

### 新增配置项

```java
// === Excel 解析参数 ===
/** Excel 单 Sheet 分块行数，默认 200 */
private int excelRowsPerChunk = 200;
```

---

## 异常设计

### 自定义异常类

新增 `DocumentParseException`（放在 `com.demo.chat.rag.parser` 包下），统一文档解析阶段的异常：

```java
/**
 * 文档解析异常 — 文件级不可恢复错误时抛出。
 * <p>
 * 使用场景：
 * - 文件损坏、格式不合法
 * - 文件加密 / 密码保护
 * - IO 错误（文件不存在、权限不足）
 * <p>
 * 不使用的场景（静默处理）：
 * - 空 Sheet / 空 Slide → 跳过，返回空 List
 * - 个别 Shape 解析失败 → 跳过该 Shape，记录 warn 日志
 * - 公式无缓存值 → 空字符串
 */
public class DocumentParseException extends RuntimeException {

    private final String fileName;
    private final String parserName;

    public DocumentParseException(String fileName, String parserName, String message, Throwable cause) {
        super(String.format("[%s] Failed to parse '%s': %s", parserName, fileName, message), cause);
        this.fileName = fileName;
        this.parserName = parserName;
    }
}
```

### 异常处理原则

| 场景 | 处理方式 | 示例 |
|------|----------|------|
| 文件级不可恢复 | 抛 `DocumentParseException` | 文件损坏、加密、格式不合法 |
| 结构级可恢复 | 跳过当前单元 + warn 日志 | 单个 Shape 解析失败、GroupShape 超递归深度 |
| 数据级正常情况 | 静默处理 | 空 Sheet/Slide、空单元格、公式无缓存 |

现有 Parser（如 `DocxDocumentParser`）使用 `RuntimeException`，后续可统一迁移为 `DocumentParseException`，本次不涉及。

---

## 实施步骤

### Phase 1: 基础设施
1. pom.xml 添加 `fesod-sheet` 依赖（含 POI exclusion）
2. `DocumentProperties` 补全 XLSX MIME 类型 + 新增 `excelRowsPerChunk` 配置
3. 新增 `DocumentParseException` 异常类

### Phase 2: PptDocumentParser
4. 实现 `PptDocumentParser`
5. 单元测试：用测试 PPTX 文件验证解析结果

### Phase 3: ExcelDocumentParser
6. 实现 `ExcelDocumentParser`（含 `detectHeader()` 私有方法）
7. 单元测试：用测试 XLSX 文件验证解析结果

### Phase 4: 集成验证
8. 启动应用，上传 PPTX 和 XLSX 文件，验证 ETL 全流程
9. 确认 `DocumentParserFactory` 日志中注册了新 Parser

## 测试文件准备

需要放入 `src/test/resources/test-documents/`：
- `test.pptx` — 包含标题页、内容页、表格页、备注、图片、GroupShape 的 PPT
- `test.xlsx` — 包含多 Sheet、表头、合并单元格、纯数字 Sheet（无表头）的 XLSX

## 不做的事（本次 Scope 外）

- PPT 老格式 (.ppt) 支持 — XSLF 不支持，走 Tika 兜底
- XLS 老格式 (.xls) 支持 — Fesod 不支持，走 Tika 兜底
- PDF 表格/OCR 增强 — 独立任务
- 图片 Slide 的 VLM 描述 — 后续多模态任务
- PPT 动画/SmartArt — RAG 场景不需要
- 现有 Parser 异常迁移到 `DocumentParseException` — 后续清理
- 将 `poi-ooxml` 提升为直接依赖 — 等 Tika 移除时再处理
