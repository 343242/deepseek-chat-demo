# 文档列表搜索与筛选设计

> 状态：实现就绪
>
> 范围：后端 `rag` 文档列表

## 1. 目标定义

`GET /api/documents` 是个人和团队文档列表的唯一入口。服务端先应用文档可见性，再组合文件名、状态和规范 MIME 筛选，最后在 PostgreSQL 中分页。

| 参数 | 类型 | 规则 |
| --- | --- | --- |
| `teamId` | long | 不提供表示个人空间；提供正数表示指定团队 |
| `keyword` | string | 原始文件名子串搜索；trim 后最长 100 字符；大小写不敏感 |
| `status` | string list | 可重复传递；同字段多值使用 OR |
| `mimeType` | string list | 可重复传递；使用规范 MIME 精确匹配；最多 10 项 |
| `page` | integer | 默认 1，最小 1 |
| `size` | integer | 默认 20，范围 1-100 |

不同字段使用 AND 语义。主列表始终排除 `SUPERSEDED` 和逻辑删除数据，历史版本由文档历史端点负责。

## 2. 当前实现与根因

当前 Controller 通过两个映射分别调用 `listAll` 和 `listByTeam`，两条 Service 路径各自构造分页 wrapper。它们只表达作用域和分页，不能表达搜索与筛选；继续在两条路径上复制参数会让权限谓词、排序和校验发生漂移。

本设计用一个查询对象、一个应用服务方法和一个 Mapper SQL 直接替换两条列表路径。旧的 `listAll`、`listByTeam` 及其测试调用一并删除。

## 3. API 与 DTO 契约

个人空间请求：

```http
GET /api/documents?keyword=report&status=COMPLETED&mimeType=application/pdf&page=1&size=20
```

团队请求：

```http
GET /api/documents?teamId=7&status=FAILED&status=VECTOR_FAILED&page=1&size=20
```

响应定义为：

```java
GlobalResponse<PagedResult<DocumentDTO>>
```

`DocumentDTO` 的新定义必须包含必填字段：

```java
boolean previewable
```

`previewable` 由共享的 `DocumentPreviewPolicy` 根据持久化的规范 MIME 与 `file_size` 计算：PDF 始终可预览，TXT / Markdown / HTML 仅在不超过专用预览上限时可预览，OOXML 不可预览。所有 `DocumentDTO` 构造和映射位置同时更新，不允许 null，也不允许 Controller 或前端复制 MIME 白名单或大小阈值。

默认排序固定为：

```text
create_time DESC, id DESC
```

参数规则：

- `status` 必须属于主列表公开状态集合，显式拒绝 `SUPERSEDED` 和未知值。
- `mimeType` 必须属于 `DocumentMimePolicy` 定义的规范 MIME 集合；去重后最多 10 项。
- list 中的空白元素是非法参数，不静默丢弃。
- `teamId`、`page`、`size` 越界以及 `keyword` 超长使用现有参数校验错误。

## 4. 查询对象与唯一入口

```java
public record DocumentListQuery(
        @Positive Long teamId,
        @Size(max = 100) String keyword,
        List<String> status,
        List<String> mimeType,
        @Min(1) Integer page,
        @Min(1) @Max(PageRequest.MAX_PAGE_SIZE) Integer size
) {}
```

Controller 只保留：

```java
list(@Valid @ModelAttribute DocumentListQuery query)
```

应用服务接口只保留：

```java
PagedResult<DocumentDTO> list(DocumentListQuery query);
```

Service 将 null `page`、`size` 归一化为 1、20，解析状态与 MIME 集合，并通过 `SqlLikePattern.contains` 生成文件名 pattern。`SqlLikePattern` 与会话搜索共用，任何文档模块代码都不重复实现 `%`、`_`、`\\` 转义。

## 5. 可见性上下文

Service 在调用 Mapper 前生成一个明确的 `DocumentVisibilityScope`，包含当前用户、空间类型、团队 ID 和团队角色。团队请求必须先通过 `TeamAccessGate.verifyAccess`。

固定可见性定义如下：

```text
个人空间：
  user_id = currentUserId
  AND team_id IS NULL

团队 CREATOR / ADMIN：
  team_id = requestedTeamId

团队 MEMBER：
  team_id = requestedTeamId
  AND (user_id = currentUserId OR status = COMPLETED)
```

三种作用域都无条件追加：

```text
deleted = 0
AND status <> SUPERSEDED
```

用户请求的 `keyword`、`status`、`mimeType` 在完整可见性谓词外层使用 AND 组合。普通成员请求 `status=FAILED` 时，只能得到自己上传且状态为 FAILED 的文档。

## 6. 数据访问

使用 `RagDocumentMapper.xml` 实现唯一的分页列表查询。XML 负责完整表达团队成员括号、`ILIKE ... ESCAPE`、多值 IN、逻辑删除和稳定排序；不保留 wrapper 版本。

查询结构为：

```sql
WHERE deleted = 0
  AND status <> 'SUPERSEDED'
  AND <visibility predicate>
  AND (file_name ILIKE #{keywordPattern} ESCAPE E'\\')
  AND status IN (<bound status values>)
  AND mime_type IN (<bound MIME values>)
ORDER BY create_time DESC, id DESC
```

三个业务筛选谓词只在对应参数存在时生成。所有值均使用 MyBatis 绑定，字段名和排序不接受请求输入。MyBatis 分页插件基于同一过滤 SQL 计算 `total`。

本功能使用现有字段和数据库能力，不新增数据库对象或 DDL。性能通过真实 PostgreSQL 集成数据上的耗时、分页上限与慢 SQL 监控验证。

## 7. 规范 MIME 与列表筛选

`mime_type` 的定义改为服务端在上传校验阶段判定并规范化的类型，不再保存浏览器声明值。具体判定规则见《原文件预览与下载设计》。文档列表和文件响应读取这一列，不对文件扩展名或客户端 `Content-Type` 做二次猜测；`DocumentDTO.previewable` 额外结合现有 `file_size` 和共享预览上限计算。`text/x-markdown` 只作为上传声明别名接受，持久化和列表筛选统一使用 `text/markdown`。

规范 MIME 的允许集合由 `DocumentMimePolicy` 单点维护，并同时服务于：

- 上传校验与持久化；
- 文档列表 `mimeType` 参数校验；
- `DocumentPreviewPolicy` 的预览能力判断；
- 原文件响应的 `Content-Type`。

## 8. 测试设计

Controller 测试覆盖：

- 个人和团队查询都绑定到同一个列表方法。
- 重复 `status`、`mimeType` 参数正确绑定。
- 默认分页、分页边界、非法团队 ID、空白 list 元素和 MIME 数量上限。

PostgreSQL Service/Mapper 集成测试覆盖：

- 个人查询始终限制当前用户和 `team_id IS NULL`。
- 团队请求先校验成员身份。
- CREATOR、ADMIN、MEMBER 三类可见性。
- MEMBER 的 `(自己上传 OR 全队 COMPLETED)` 括号在叠加状态筛选后仍正确。
- 逻辑删除与 `SUPERSEDED` 永不进入主列表。
- 文件名大小写、中文以及 `%`、`_`、`\\` 字面搜索。
- 状态/MIME 单选、多选、重复值、非法值和多字段组合。
- `create_time` 相同时按 `id DESC` 稳定分页。
- 过滤后的 `total`、`totalPages` 与内容一致。

DTO 测试覆盖所有映射路径都输出非空、正确的 `previewable`。

## 9. 实现改动范围

| 文件 | 动作 |
| --- | --- |
| `common/util/SqlLikePattern.java` | 与会话搜索共用 LIKE pattern 值对象 |
| `rag/dto/DocumentListQuery.java` | 新增查询 DTO |
| `rag/dto/DocumentDTO.java` | 将 `previewable` 加入必填定义 |
| `rag/controller/DocumentController.java` | 用一个列表映射替换现有两个映射 |
| `rag/service/DocumentApplicationService.java` | 用 `list(DocumentListQuery)` 替换两个旧方法 |
| `rag/service/impl/DocumentApplicationServiceImpl.java` | 归一化参数并构造可见性上下文 |
| `rag/mapper/RagDocumentMapper.java` | 声明唯一分页查询 |
| `rag/mapper/RagDocumentMapper.xml` | 实现权限、筛选、计数与排序 SQL |
| `rag/service/DocumentMimePolicy.java` | 提供规范 MIME 集合与校验 |
| `rag/service/DocumentPreviewPolicy.java` | 统一计算必填 `previewable` |
| `rag/.../*Test.java` | 替换旧列表测试并增加 PostgreSQL 与 DTO 测试 |

## 10. 完成标准

- Controller、Service、Mapper 各只有一个文档列表入口。
- 代码中不存在 `listAll`、`listByTeam` 或它们的委托层。
- 所有筛选发生在数据库分页前，并与可见性谓词正确组合。
- `DocumentDTO.previewable` 在所有响应中为必填 boolean，且只由 `DocumentPreviewPolicy` 计算。
- 文档与会话搜索只使用共享 `SqlLikePattern`。
- 不包含功能开关、并行查询实现或数据库结构变更。
