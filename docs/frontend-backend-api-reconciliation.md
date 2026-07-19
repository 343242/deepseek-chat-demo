# 前后端 API 摩擦点核对结论

> 本文档针对前端梳理的 10 个前后端摩擦点（T1–T8、IA-1、IA-5），逐条给出后端基于代码事实的核对结论。
> 每条均附 `file_path:line_number` 证据，**不臆测**。
>
> 编写时间：2026-07-19

---

## 一、本轮已修复（后端改动）

### IA-5 · `/api/auth/me` 返回 `permissions`

**前端诉求**：前端权限守卫需要具体权限码（如 `chat:send`），不能依赖前端硬编码"角色→权限"映射表。

**后端改动**：
- `LoginResponse.UserInfo` record 新增 `permissions: List<String>` 字段。
- `AuthServiceImpl#getCurrentUser` 已经查 `tokenCacheService.getUserPermissions(userId)`，原本只用于 miss 判定，现在直接放进 DTO 返回。
- 登录/注册/刷新响应同步带上 `permissions`（异步预热 best-effort，可能为空；前端拿到响应后立即调 `/api/auth/me` 兜底）。

**API 契约**：
```jsonc
// GET /api/auth/me 响应
{
  "user": {
    "id": 1,
    "username": "admin",
    "nickname": "...",
    "email": "...",
    "avatar": "...",
    "roles": ["ADMIN"],
    "permissions": ["chat:send", "chat:stream", "conversation:manage", ...]  // 新增
  }
}
```

**前端使用建议**：用 `permissions.includes('chat:send')` 做权限守卫，**不要再维护角色→权限映射表**。

---

### T4 · Reference 补 `score` / `source` / `content`

**前端诉求**：引用卡片（ReferenceCard）目前只能渲染文件名，需要相关性得分、来源 Tool、文本片段。

**后端事实**：底层 `RetrievedDocument` 早已持有这三个字段，是 `Reference` 在转换时丢弃了。本轮"暴露已有数据"，无新增数据源。

**后端改动**：
- `Reference` record 末尾新增 3 个字段（向后兼容）：`score` / `source` / `content`。
- `AgentModeStrategy#buildReferences`、`ChatReferenceCollector#collect` 两处转换点补传。

**API 契约**（chat / agent 响应的 `references[]` 数组每项）：
```jsonc
{
  "refNumber": 1,
  "chunkId": "uuid-...",
  "documentId": "123",
  "fileName": "rag-handbook.pdf",
  "page": 5,
  "score": 0.873,           // 新增：向量相似度 / RRF / rerank 分
  "source": "hybridSearch", // 新增：来源 Tool（agent 路径可能为 null）
  "content": "片段文本..."  // 新增：截断后的 chunk 内容（agent 路径可能为 null）
}
```

**前端使用建议**：`score` 用于排序/置信度展示；`source` 标注"来源：混合检索/向量检索"；`content` 用于引用卡片预览（已截断到 800 字，无需前端再截）。

---

### T7 · Cookie SameSite 参数化 + None 强制 Secure

**前端诉求**：跨域部署（前后端不同源）时 Cookie `SameSite=Lax` 会失效，需参数化。

**后端改动**：
- `JwtProperties` record 新增 `cookieSameSite` 字段（默认 `Lax`，紧凑构造器兜底 null/空白）。
- `CookieTokenManager` 三处硬编码 `"Lax"` 统一改为读配置，**并加防护**：检测到 `None` 时强制 `Secure=true`（浏览器规范要求，否则 Set-Cookie 被静默拒绝）。
- `application-dev.yml` / `application-stable.yml` 显式声明 `cookie-same-site: ${JWT_COOKIE_SAMESITE:Lax}`。

**配置示例**：
```yaml
# dev（同源/反代）—— 默认行为不变
app.jwt.cookie-same-site: Lax

# 跨域部署（前后端不同源）—— 改 None，后端强制 Secure
app.jwt.cookie-same-site: None
```

**前端使用建议**：同源/反代部署无需改动；跨域部署时通知运维改环境变量 `JWT_COOKIE_SAMESITE=None`（`cookie-secure=true` 在 stable/prod 已配，且后端还有兜底）。

---

## 二、前端描述与实际不符（前端需修正认知，后端不动）

### T1 · `team:manage` 权限种子

**前端描述**："`team:manage` 不在 8 个种子权限内，TeamController.setCreatorQuota 引用了这个权限码，但后端 V3 种子没有它。"

**后端事实**：**前端认知过时**。
- V3 种子的 8 个权限是初始集；
- V9__add_team.sql:86-95 已补充：
  ```sql
  INSERT INTO sys_permission ... 'team:manage'  -- L88 已有
  -- L91-95 绑定到 ADMIN 角色
  INSERT INTO sys_role_permission ... WHERE r.role_name = 'ADMIN' AND p.resource_key = 'team:manage'
  ```
- `TeamController.java:54` 的 `@PreAuthorize("hasAuthority('team:manage')")` 配合的就是 V9 的种子，**能正常工作**。

**结论**：无需补权限种子，也无需改用 ADMIN 角色判定。USER 角色没有 `team:manage` 是**预期行为**（只有 ADMIN/团队 CREATOR 能管团队）。

---

### T5 · `/api/models` 只返回 ID

**前端描述**："/api/models 只返回 ID 字符串列表，模型选择器需要展示 provider 名称、能力标签等信息。"

**后端事实**：**`/api/models` 端点不存在**。
- `grep "/api/models"` 在整个后端无命中；
- 模型配置走的是 **BYOK（Bring Your Own Key）模式**：
  - `GET /api/user/llm-config`（owner 自服务，返回 `LlmConfigVO` 脱敏 VO）
  - `GET /api/admin/llm-config?userId=...`（admin 只读审计，需 `user:manage` 权限）
- 返回的 `LlmConfigVO` **不是 ID 字符串**，而是脱敏后的完整配置对象（capability/provider/modelId/参数等）。
- 后端有 `ProviderModelInfo` 实体（聚合多厂商模型展示数据），但**没有暴露成 REST 端点**。

**结论**：前端把 BYOK 配置接口误解成了"可用模型清单"。若前端需要"可选模型目录"（provider 名称 + 能力标签），需后端**新增** `GET /api/models/catalog` 类端点 —— 本轮未做，待讨论方案。

---

### T8 · prod 没 cookie-secure 配置

**前端描述**："Cookie Secure 没有 profile 配置 HTTPS，prod 没有配置。"

**后端事实**：**前端描述错误**。
- `application-prod.yml` 顶部注释明确：`Usage: SPRING_PROFILES_ACTIVE=stable,prod`（prod 是 stable 的 overlay）；
- `application-stable.yml:86` 已经有：`cookie-secure: true`；
- prod 实际运行时（`stable,prod` 叠加），`cookie-secure` 就是 `true`。

**结论**：无需在 prod.yml 重复配置。Spring profile overlay 机制保证了 prod 继承 stable 的安全配置。

---

## 三、设计有意为之（不推荐改）

### T6 · 登录响应不返回 token

**前端描述**："登录响应不返回 token（纯 Cookie 模式），跨域部署时 Cookie SameSite=Lax 可能失效，需评估 token-in-body 开关。"

**后端事实**：**这是有意的安全设计**。
- `AuthController.java:78` 注释明确：`refresh token 仅从 HttpOnly cookie 读取（纯浏览器客户端，禁止 body 携带以防 XSS 窃取）`；
- HttpOnly Cookie 防止 JavaScript 通过 XSS 读取 token —— 这是 OWASP 推荐做法；
- 若改成 token-in-body，等于把 token 暴露给 JS 可读空间，XSS 攻击面直接放大。

**结论**：**不推荐加 token-in-body 开关**。
- 跨域问题应通过 T7（SameSite 参数化）解决 —— 配 `SameSite=None; Secure` 即可让 Cookie 在跨站请求中携带；
- 如果真有无法用 Cookie 的客户端（如原生 App），可单独讨论 OAuth2 Bearer 方案，不要在 Web 端点里加 token-in-body。

---

## 四、暂不做（待讨论方案）

### T2 · chunks 内容查看端点

**前端诉求**：知识库文档卡片和聊天中的引用卡片需要展示文档片段内容。

**后端现状**：
- `DocumentController` 没有 chunks 端点；
- 底层 `vector_store` 表（Spring AI 的 chunk 存储）通过 `VectorStoreMapper.xml` 已有 `vectorStoreRow` resultMap，但**缺按 `documentId` 查 chunks 的 SQL**。

**待讨论**：
- 端点形状：`GET /api/documents/{id}/chunks?page=&size=` 还是 `GET /api/chunks/{chunkId}`？
- 权限：`isAuthenticated()` 够吗？还是要校验文档归属（个人/团队）？
- 字段裁剪：返回 content 全文还是预览？分页？

### T3 · Agent 事件历史 REST 端点

**前端诉求**：Agent Trace 完整视图（意图识别 → 查询改写 → 混合检索 → 重排 → 生成）依赖事件数据。

**后端现状**：
- 数据层完整：`agent_session_event`（V15）+ `trace_event`（V20）两张表；
- 服务层完整：`AgentEventStore`（含 `selectBySessionIdOrderByPriority` 等查询）+ `TraceRecorder`；
- **缺 REST Controller** —— 没有任何端点暴露这些数据。

**待讨论**：
- 端点形状：`GET /api/agent/sessions/{id}/events` + `GET /api/trace/sessions/{id}` 两个，还是合并？
- 多租户隔离：按 `user_id` 过滤，还是允许 admin 查任意会话？
- 字段裁剪：`data` JSONB 原样返回还是拆平？

### IA-1 · features.evaluation

**前端诉求**：评估导航项需要同时满足"eval profile 开启"和"用户有 evaluation:manage 权限"，希望 `/api/auth/me` 返回 `features: { evaluation: boolean }`。

**后端现状**：
- `evaluation:manage` 权限已存在（V12__eval_status_check_and_permission.sql），仅绑定 ADMIN；
- profile 检测用纯 `@Profile("evaluation")` 注解，**无运行时 `Environment.acceptsProfiles` 探测**。

**本轮不做**：用户明确指示"先不管评估模块的任何问题"。待评估模块方向明确后再统一处理。

---

## 五、改动清单速查

| 编号 | 类型 | 改动 | 涉及文件 |
|------|------|------|----------|
| IA-5 | ✅ 已修复 | `/me` 返回 permissions | `LoginResponse.java`、`AuthServiceImpl.java` + 3 测试 |
| T4 | ✅ 已修复 | Reference 加 score/source/content | `Reference.java`、`AgentModeStrategy.java`、`ChatReferenceCollector.java` |
| T7 | ✅ 已修复 | SameSite 参数化 + None 强制 Secure | `JwtProperties.java`、`CookieTokenManager.java`、`application-dev.yml`、`application-stable.yml` |
| T1 | ⚠️ 前端认知过时 | V9 已有种子 | 无 |
| T5 | ⚠️ 前端理解错误 | `/api/models` 不存在 | 无（待讨论是否新增 catalog 端点） |
| T8 | ⚠️ 前端描述错误 | prod overlay 自 stable 继承 | 无 |
| T6 | 🚫 设计有意为之 | token-in-body 会放大 XSS 风险 | 无 |
| T2 | 🕐 待讨论 | chunks 端点 | 无 |
| T3 | 🕐 待讨论 | Agent/Trace 事件端点 | 无 |
| IA-1 | 🕐 暂不做 | 评估模块 | 无 |
