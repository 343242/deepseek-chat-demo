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
2. 新增 `ExcelDocumentParser` — 基于 FastExcel，保留表格结构
3. 两个解析器遵循现有 `DocumentParser` 接口，由 `DocumentParserFactory` 自动路由
4. 更新 `DocumentProperties.allowedMimeTypes` 补全 MIME 类型

## 依赖变更

### pom.xml 新增

```xml
<!-- FastExcel — 轻量 XLSX 读取 -->
<dependency>
    <groupId>org.dhatim</groupId>
    <artifactId>fastexcel-reader</artifactId>
    <version>0.18.6</version>
</dependency>
```

> **说明**：Apache POI 已通过 `spring-ai-tika-document-reader` 传递依赖引入（含 poi-ooxml），XSLF 可直接使用，无需额外依赖。FastExcel 是独立轻量库，不依赖 POI，读取 XLSX 性能优于 POI。

> **为什么不选 EasyExcel**：EasyExcel 基于 POI 封装，适合大文件流式写入场景；FastExcel 更轻量（~200KB），读取 API 更简洁，适合文档解析这种读多写少场景。

## 类设计

### 新增文件

```
com.demo.chat.rag.parser/
├── PptDocumentParser.java       ← 新增
└── ExcelDocumentParser.java     ← 新增
```

### 修改文件

```
com.demo.chat.rag.config.DocumentProperties.java   ← 补全 MIME 类型
```

---

## PptDocumentParser 设计

### 支持的 MIME 类型

```
application/vnd.openxmlformats-officedocument.presentationml.presentation  (PPTX)
```

> 老格式 PPT (application/vnd.ms-powerpoint) 不支持——POI XSLF 仅支持 PPTX。PPT 走 Tika 兜底即可。

### 解析策略

```
SlideShow (PPTX)
  ├── Slide 1
  │   ├── Title Shape      → Document(title, metadata:{slide:1, type:"title"})
  │   ├── Content Shapes   → Document(text, metadata:{slide:1, type:"content"})
  │   ├── Table Shape      → Document(markdown_table, metadata:{slide:1, type:"table"})
  │   └── Notes            → Document(notes, metadata:{slide:1, type:"notes"})
  ├── Slide 2
  │   └── ...
  └── ...
```

### 核心逻辑

1. 遍历每个 `XSLFSlide`，按 Slide 序号建立分组
2. 对每个 Slide 中的 `XSLFShape` 按类型处理：
   - **XSLFTextShape**：提取文本，判断是否为 Title（占位符类型）
   - **XSLFTable**：转为 Markdown 表格字符串
   - **XSLFPictureShape**：提取图片 alt 文本（如有），记录图片存在标记
   - **XSLFGroupShape**：递归处理内部 Shape
3. **Slide Notes**：`XSLFNotesTextBuilder` 提取备注文本
4. **合并策略**：同一 Slide 的所有文本合并为一个 Document（避免碎片化），但 Table 和 Notes 各自独立

### Metadata 设计

```java
metadata = {
    "parser":        "ppt",
    "mimeType":      "application/vnd.openxmlformats-officedocument.presentationml.presentation",
    "slideIndex":    0,          // Slide 序号（0-based）
    "slideCount":    12,         // 总 Slide 数
    "shapeType":     "title" | "content" | "table" | "notes",
    "hasImage":      true,       // 该 Slide 是否包含图片
    "source":        "xxx.pptx"  // 文件名
};
```

### 边界情况处理

- 空 Slide（无任何 Shape）→ 跳过
- 纯图片 Slide（无文本）→ 生成一个标记性 Document（`metadata.hasImage=true`，content 为 "[图片 Slide]"）
- PPT 文件密码保护 → 抛出明确异常

---

## ExcelDocumentParser 设计

### 支持的 MIME 类型

```
application/vnd.openxmlformats-officedocument.spreadsheetml.sheet  (XLSX)
```

> 老格式 XLS (application/vnd.ms-excel) FastExcel 不支持，走 Tika 兜底。

### 解析策略

```
Workbook (XLSX)
  ├── Sheet 1 "销售数据"
  │   ├── Header Row        → 识别表头
  │   ├── Data Rows         → 按行读取
  │   └── → Document(markdown_table, metadata:{sheet:"销售数据"})
  ├── Sheet 2 "汇总"
  │   └── → Document(markdown_table, metadata:{sheet:"汇总"})
  └── ...
```

### 核心逻辑

1. 使用 `FastExcel.read(is)` 打开 Workbook
2. 遍历每个 Sheet：
   - 读取所有非空行，判断第一行是否为表头（启发式：值是否像列名）
   - **小 Sheet（≤200 行）**：整个 Sheet 转为一个 Markdown Table，作为一个 Document
   - **大 Sheet（>200 行）**：按行数分块，每 200 行一个 Document，第一个 Document 包含表头
3. Markdown Table 格式：`| col1 | col2 | col3 |\n|---|---|---|\n| v1 | v2 | v3 |`
4. 合并单元格：FastExcel-reader 会自动填充合并区域的值，无需额外处理

### Metadata 设计

```java
metadata = {
    "parser":       "excel",
    "mimeType":     "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
    "sheetName":    "销售数据",
    "sheetIndex":   0,         // Sheet 序号（0-based）
    "sheetCount":   3,         // 总 Sheet 数
    "rowCount":     150,       // 该 Sheet 非空行数
    "chunkIndex":   0,         // 分块序号（大 Sheet 时有用）
    "hasHeader":    true,      // 是否检测到表头
    "source":       "xxx.xlsx" // 文件名
};
```

### 边界情况处理

- 空 Sheet（0 行）→ 跳过
- 公式单元格 → 取缓存的计算值（FastExcel 默认行为），无缓存值时显示为空
- 超大 Sheet → 按 200 行分块，每块带上表头行
- 纯数字列（如 ID 列）→ 保持原值，不做格式推断

---

## DocumentProperties 变更

`allowedMimeTypes` 新增：

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

> 注意：PPTX 已在现有列表中（但之前走 Tika），XLSX 是新增。

## DocumentParserFactory 变更

**无需改动**。工厂通过 `DocumentParser.supportedMimeTypes()` 自动发现并路由。新 Parser 注册为 Spring Bean 后，工厂初始化时自动拾取。

## 实施步骤

### Phase 1: 基础设施
1. pom.xml 添加 `fastexcel-reader` 依赖
2. `DocumentProperties.allowedMimeTypes` 补全 XLSX MIME 类型

### Phase 2: PptDocumentParser
3. 实现 `PptDocumentParser`
4. 单元测试：用测试 PPTX 文件验证解析结果

### Phase 3: ExcelDocumentParser
5. 实现 `ExcelDocumentParser`
6. 单元测试：用测试 XLSX 文件验证解析结果

### Phase 4: 集成验证
7. 启动应用，上传 PPTX 和 XLSX 文件，验证 ETL 全流程
8. 确认 `DocumentParserFactory` 日志中注册了新 Parser

## 测试文件准备

需要放入 `src/test/resources/test-documents/`：
- `test.pptx` — 包含标题页、内容页、表格页、备注、图片的 PPT
- `test.xlsx` — 包含多 Sheet、表头、大数据量、合并单元格的 XLS

## 不做的事（本次 Scope 外）

- PPT 老格式 (.ppt) 支持 — XSLF 不支持，走 Tika 兜底
- XLS 老格式 (.xls) 支持 — FastExcel 不支持，走 Tika 兜底
- PDF 表格/OCR 增强 — 独立任务
- 图片 Slide 的 VLM 描述 — 后续多模态任务
- PPT 动画/SmartArt — RAG 场景不需要
