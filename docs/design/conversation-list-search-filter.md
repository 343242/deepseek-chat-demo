# 会话列表搜索与筛选设计

> 状态：实现就绪
>
> 范围：后端 `conversation` 模块

## 1. 目标定义

`GET /api/conversations` 是会话列表的唯一入口。服务端在数据库分页前完成当前用户范围内的标题搜索、状态筛选和置顶筛选，返回过滤后的准确总数与稳定分页结果。

目标查询参数如下：

| 参数 | 类型 | 规则 |
| --- | --- | --- |
| `keyword` | string | 标题子串搜索；原始长度最长 100 字符（含首尾空白）；大小写不敏感；纯空白视为未提供 |
| `status` | string | 仅接受 `ACTIVE`、`ARCHIVED` |
| `pinned` | boolean | `true` 或 `false`；省略时不施加该筛选（三态：省略 / true / false） |
| `page` | integer | 默认 1，最小 1 |
| `size` | integer | 默认 50，范围 1-100 |

所有已提供的条件使用 AND 语义。`DELETED` 始终作为基础排除条件，不能通过请求参数查询。

## 2. 当前实现与根因

当前调用链为：

```text
ConversationController.list(page, size, status)
  -> ConversationService.list(userId, status, page, size)
  -> ConversationServiceImpl.list(...)
  -> ConversationMapper.selectPage(Page, LambdaQueryWrapper)
```

缺陷根因有三项：

1. HTTP 与 Service 契约没有表达 `keyword`、`pinned`，前端只能过滤已经加载的一页。
2. Controller 允许 `size <= 500`，Service 又将其截断为 100，同一个参数存在两个定义。
3. 排序只有 `pinned DESC, last_message_at DESC`，相同时间值下没有稳定次序。

本设计直接替换列表方法定义，不保留旧参数列表的委托方法。

## 3. 请求与响应契约

请求示例：

```http
GET /api/conversations?keyword=RAG&status=ACTIVE&pinned=true&page=1&size=50
```

等价查询语义：

```text
user_id = currentUserId
AND status <> DELETED
AND status = ACTIVE
AND pinned = true
AND title ILIKE escapedContainsPattern("RAG")
```

响应继续使用项目统一分页结构：

```java
GlobalResponse<PagedResult<ConversationSummary>>
```

`total`、`totalPages` 和 `content` 必须来自同一组过滤条件。默认排序固定为：

```text
pinned DESC, last_message_at DESC NULLS LAST, id DESC
```

参数错误使用现有客户端错误响应：

- `keyword` 超长、`page` 或 `size` 越界：`VALIDATION_ERROR`。
- `status` 不在公开集合内：`BAD_REQUEST`，消息固定说明仅支持 `ACTIVE`、`ARCHIVED`。
- 非法 boolean 由 Spring 参数绑定错误处理。

## 4. 查询对象与归一化

Controller 使用一个查询对象：

```java
public record ConversationListQuery(
        @Size(max = 100) String keyword,
        String status,
        Boolean pinned,
        @Min(1) Integer page,
        @Min(1) @Max(PageRequest.MAX_PAGE_SIZE) Integer size
) {}
```

唯一 Controller 方法签名为：

```java
list(@Valid @ModelAttribute ConversationListQuery query)
```

Controller 方法体内调 `SecurityUtils.getCurrentUserId()` 取得当前用户 ID，再将其作为显式参数传给 Service。`userId` 不是 HTTP 参数，不进入查询对象。

Service 接口改为：

```java
PagedResult<ConversationSummary> list(ConversationListQuery query, Long userId);
```

Service 在一次归一化中完成以下工作：

1. 将空白 `keyword` 归一化为 null。
2. 将 null `page`、`size` 分别归一化为 1、50。
3. 将 `status` 显式解析为公开状态，拒绝内部状态和未知值。
4. 将 Controller 传入的 `userId` 与已归一化参数封装为 Mapper 查询条件。

Service 不读取安全上下文，`userId` 完全由调用方提供，保持可单测。原始 HTTP 字符串不能直接传给 SQL Mapper。

## 5. 共享 LIKE 模式

会话标题和文档文件名搜索共用一个基础设施值对象：

```java
public final class SqlLikePattern {
    public static String contains(String raw) { ... }
}
```

`contains` 按 `\\`、`%`、`_` 的顺序转义 LIKE 元字符，再在两端添加 `%`。该类是项目中构造用户输入 LIKE pattern 的唯一位置。Mapper 只接收绑定参数，禁止拼接输入值或在各模块复制转义逻辑。

## 6. 数据访问

使用 `ConversationMapper.xml` 定义唯一的分页查询。选择 XML 是为了显式表达 `ILIKE ... ESCAPE`、`NULLS LAST` 和稳定排序，不在 MyBatis-Plus wrapper 与 XML 之间保留两套实现。

查询必须包含：

```sql
WHERE user_id = #{userId}
  AND status <> 'DELETED'
  AND (status = #{status})            -- status 有值时生成
  AND (pinned = #{pinned})            -- pinned 有值时生成
  AND (title ILIKE #{keywordPattern} ESCAPE E'\\') -- keyword 有值时生成
ORDER BY pinned DESC,
         last_message_at DESC NULLS LAST,
         id DESC
```

动态节点只决定某个业务筛选谓词是否存在；用户隔离和排除删除态是无条件基础谓词。对应的 MyBatis 动态结构示意：

```xml
<select id="selectListPage" resultType="com.smart.rag.conversation.entity.Conversation">
    SELECT * FROM conversation
    <where>
        user_id = #{userId}
        AND status &lt;&gt; 'DELETED'
        <if test="status != null">AND status = #{status}</if>
        <if test="pinned != null">AND pinned = #{pinned}</if>
        <if test="keywordPattern != null">
            AND title ILIKE #{keywordPattern} ESCAPE E'\\'
        </if>
    </where>
    ORDER BY pinned DESC, last_message_at DESC NULLS LAST, id DESC
</select>
```

MyBatis 分页插件基于同一 SQL 生成 count 与 page 查询。

本功能使用现有表结构和数据库能力，不新增数据库对象或 DDL。性能验证以真实 PostgreSQL 数据集上的查询耗时和慢 SQL 指标为准；列表始终有 100 条的单页上限。

## 7. 安全与观测

- 搜索范围仅为当前用户的 `conversation.title`，不搜索消息正文。
- Mapper 参数全部使用预编译绑定。
- 任何条件组合都不能移除 `user_id` 和 `status <> 'DELETED'`。
- 日志记录筛选器是否启用、耗时和结果数，不记录完整 keyword。

## 8. 测试设计

Controller 测试覆盖查询对象绑定、默认分页、100 条上限以及非法状态和分页参数（`page=0`、`size=0`、`size=101` 返回 `VALIDATION_ERROR`）。

Service/Mapper 测试使用 PostgreSQL，至少覆盖：

- 不同用户之间严格隔离，`DELETED` 永不返回。
- 标题大小写不敏感搜索。
- `%`、`_`、`\\` 按字面量搜索。
- 空白 keyword 等价于未提供 keyword。
- `ACTIVE`、`ARCHIVED`、`pinned=true`、`pinned=false` 及多条件 AND。
- `pinned` 省略与 `pinned=false` 必须产生不同结果集（验证三态筛选语义）。
- 相同 `last_message_at` 下以 `id DESC` 保持跨页稳定。
- 过滤后的 `total`、`totalPages` 与列表内容一致。

验收时，搜索必须命中未加载页中的会话，且任何筛选组合都不能返回其他用户或删除态会话。

## 9. 实现改动范围

| 文件 | 动作 |
| --- | --- |
| `common/util/SqlLikePattern.java` | 新增共享 LIKE pattern 值对象 |
| `conversation/dto/ConversationListQuery.java` | 新增查询 DTO |
| `conversation/controller/ConversationController.java` | 改为单一查询对象、统一分页约束，并在方法体内取 `userId` 显式传入 Service |
| `conversation/service/ConversationService.java` | 用 `list(ConversationListQuery, Long userId)` 替换旧列表签名 |
| `conversation/service/impl/ConversationServiceImpl.java` | 归一化查询并调用唯一 Mapper 查询 |
| `conversation/mapper/ConversationMapper.java` | 声明分页查询 |
| `conversation/mapper/ConversationMapper.xml` | 实现过滤、计数和稳定排序 SQL |
| `conversation/.../*Test.java` | 更新 Controller、Service 与 PostgreSQL 查询测试 |

## 10. 完成标准

- 代码中只存在一个会话列表 Controller 方法、一个 Service 列表契约和一个 Mapper 查询实现。
- `size` 在 HTTP、Service 和分页对象中统一为 1-100。
- LIKE 转义只由 `SqlLikePattern` 提供。
- 查询在数据库分页前完成过滤，分页计数准确且顺序稳定。
- 不包含旧签名委托、功能开关或数据库结构变更。
