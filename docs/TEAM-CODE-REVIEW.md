# 团队协作功能代码审查报告

> **审查时间：** 2026-05-14  
> **审查范围：** commit `28b0f44` → HEAD（Phase 1~3），56 个文件  
> **审查者：** GLM (zai/glm-5.1) + DeepSeek (deepseek-v4-pro)  
> **审查标准：** ECC 技能 — `java-coding-standards`、`springboot-patterns`、`springboot-security`、`springboot-verification`、`security-review`  
> **分支：** `rag-dev`

---

## 总览

| 级别 | 数量 | 说明 |
|------|------|------|
| 🔴 BLOCKER | 3 | 必须修复才能上线 |
| 🟠 HIGH | 7 | 强烈建议修复 |
| 🟡 MEDIUM | 8 | 建议修复 |
| 🔵 LOW | 4 | 可选改进 |

**修复优先级：** B1 → B2 → B3 → H1 → H2 → 其余

---

## 🔴 BLOCKER（3）

### B1. `TeamUploadStrategy` 全部设 PROCESSING，审批流形同虚设

**文件：** `team/upload/TeamUploadStrategy.java` — `persistDocument()` 方法（约第137行）

**问题：** 无论上传者是普通成员还是管理员，状态都直接设为 `EtlStatus.PROCESSING` 并触发 ETL。审批模块（`TeamApprovalServiceImpl`）虽已实现但完全未接入。普通成员上传的文件绕过审批直接进入团队知识库，**权限模型失效**。

```java
// 当前代码
doc.setStatus(EtlStatus.PROCESSING);  // 全部直接通过
```

**修复建议：** 注入 `TeamMemberMapper`，根据上传者角色决定状态：

```java
TeamMember member = teamMemberMapper.selectByTeamAndUser(teamId, userId);
boolean isManager = member != null &&
    (member.getRole() == TeamMemberRole.CREATOR || member.getRole() == TeamMemberRole.ADMIN);
doc.setStatus(isManager ? EtlStatus.PROCESSING : EtlStatus.PENDING_APPROVAL);

if (!isManager) {
    // 创建 TeamUploadApproval 记录
}
```

**两方共识。**

---

### B2. 重复团队名称无业务校验，裸 DB 异常

**文件：** `team/service/impl/TeamServiceImpl.java` — `createTeam()` 方法（约第64-80行）

**问题：** `ErrorCode.TEAM_NAME_DUPLICATE(55002)` 已定义但创建团队时未使用。DB 有 `uk_team_name_active` 唯一部分索引，并发创建同名团队时抛出 `DataIntegrityViolationException` → 500 错误，而非友好的业务异常。

```java
// 当前：直接 insert，无名称查重
teamMapper.insert(team);
```

**修复建议：** 方案一：insert 前 count 检查；方案二：`try-catch DuplicateKeyException` 转换为 `TEAM_NAME_DUPLICATE`（推荐，更安全）。

---

### B3. `setCreatorQuota()` 双写无事务，数据一致性风险

**文件：** `team/service/impl/TeamServiceImpl.java` — `setCreatorQuota()` 方法（约第176-190行）

**问题：** 先更新 `team.creatorUploadLimitMb`，再更新 `team_member.uploadLimitMb`，两次 DB 写操作不在同一事务中。若第二步失败（如 creator 成员记录不存在），团队表和成员表数据不一致。

```java
teamMapper.updateById(team);           // 成功
// 如果这里失败...
creator.setUploadLimitMb(maxUploadMb);
teamMemberMapper.updateById(creator);  // 失败 → team 已更新，member 未更新
```

**修复建议：** 包裹 `txTemplate.executeWithoutResult()`。

---

## 🟠 HIGH（7）

### H1. N+1 查询（4处）

| 文件 | 方法 | 查询模式 |
|------|------|----------|
| `TeamServiceImpl` | `listMyTeams()` | N × 2（selectById + selectCount） |
| `TeamMemberServiceImpl` | `listMembers()` | N × 1（selectActiveById） |
| `TeamApprovalServiceImpl` | `listPending()` | N × 2（selectById × 2） |
| `TeamApprovalServiceImpl` | `listMyApprovals()` | N × 1（selectById） |

**修复建议：** 批量 `selectBatchIds()` + Map 索引，或 JOIN SQL。例如 `listMyTeams()` 可用一条 JOIN 查询 + `GROUP BY team_id COUNT(*)`。

---

### H2. `dissolveTeam()` 注释写 SELECT FOR UPDATE 但实际无锁

**文件：** `team/service/impl/TeamServiceImpl.java` — `dissolveTeam()` 方法（约第132行）

```java
// SELECT FOR UPDATE 防并发  ← 注释
Team team = teamMapper.selectById(teamId);  // ← 实际没加锁
```

**修复建议：** 使用 `new LambdaQueryWrapper<Team>().eq(Team::getId, teamId).last("FOR UPDATE")` 或自定义 Mapper 方法。

---

### H3. `addMember()` TOCTOU 竞态 + 无事务

**文件：** `team/service/impl/TeamMemberServiceImpl.java` — `addMember()` 方法（约第61-130行）

**问题：** 成员数检查（`selectCount`）和 `insert` 之间无事务保护。并发邀请同一用户可能突破 `maxMembersPerTeam` 限制或触发 DB 唯一约束异常。

**修复建议：** 整个方法包裹 `txTemplate.execute()`，DB 唯一索引兜底。

---

### H4. `setCreatorQuota()` Service 层无权限校验

**文件：** `team/service/impl/TeamServiceImpl.java` — `setCreatorQuota()` 方法

**问题：** Controller 有 `@PreAuthorize("hasAuthority('team:manage')")` 但 Service 层没校验。被其他 Service 调用时会越权。

**修复建议：** Service 内校验调用者是创建者或管理员。

---

### H5. `rejectTimedOut()` 逐条事务，性能差

**文件：** `team/service/impl/TeamApprovalServiceImpl.java` — `rejectTimedOut()` 方法（约第158-179行）

**问题：** 100 条超时 = 100 次事务 + 200 次 SQL。且 `fixedRate` 可能重叠执行。

**修复建议：** 批量 `LambdaUpdateWrapper` + 单一事务 + 改用 `fixedDelay` 防重叠。

---

### H6. 列表接口无分页

**涉及文件：** `TeamApprovalController.listPending()` / `listMyApprovals()`、`TeamMemberController.listMembers()`

**问题：** 长时间运行的团队，审批记录无限增长。`listMyApprovals` 无上限。

**修复建议：** 添加 `page` / `size` 参数，返回分页结果。

---

### H7. 审批并发防护缺失

**文件：** `team/service/impl/TeamApprovalServiceImpl.java` — `review()` 方法

**问题：** 两个管理员同时审批同一请求 → 竞态。状态检查 `status != PENDING` 在事务外。

**修复建议：** 事务内 SELECT + 状态检查 + UPDATE 原子操作。

---

## 🟡 MEDIUM（8）

### M1. `PersonalUploadStrategy` / `TeamUploadStrategy` 大量代码重复

两个策略的 `upload()` / `uploadBatch()` 主体逻辑几乎相同（校验→存MinIO→写DB→触发ETL），只有状态和 teamId 不同。违反 DRY。

**建议：** 提取 `AbstractUploadStrategy` 模板方法基类，子类只实现 `determineStatus()` 和 `afterPersist()` 钩子。

---

### M2. `dissolveTeam()` 逐条更新成员，应批量 UPDATE

```java
for (TeamMember m : members) { m.setStatus(0); teamMemberMapper.updateById(m); }
```

**修复建议：** 改为 `LambdaUpdateWrapper` 批量更新。

---

### M3. `approveAndTriggerEtl()` 静默吞异常

`approval == null` 和 `doc == null` 直接 return，无日志。审批通过但 ETL 不触发时无法排查。

**修复建议：** 添加 `log.warn()` 记录异常情况。

---

### M4. 枚举 `@JsonValue` 返回数字而非字符串

`ApprovalStatus` / `TeamMemberRole` / `TeamStatus` 的 `@JsonValue` 返回 int code（0/1/2），前端难以理解。

**建议：** 保持内部 `@EnumValue` 用 int，API 层返回字符串 name。需与项目已有约定一致。

---

### M5. `removeMember()` 错误码不精确

ADMIN 移除 ADMIN 时返回 `NOT_TEAM_CREATOR`，但实际语义是"管理员不能移除管理员"。

**建议：** 新增 `ADMIN_CANNOT_REMOVE_ADMIN(55020)`。

---

### M6. `PersonalUploadStrategy` 用 `LocalDateTime` 而 Team 实体用 `OffsetDateTime`

时区不一致风险。个人文档创建时间和团队文档创建时间使用不同类型。

**修复建议：** 统一 `OffsetDateTime.now()`。

---

### M7. 3 个 ErrorCode 已定义但从未使用（死代码）

- `CANNOT_CHANGE_CREATOR_ROLE(55008)` — 从未引用
- `UPLOAD_QUOTA_EXCEEDED(55009)` — 从未引用
- `UPLOAD_LIMIT_OUT_OF_RANGE(55010)` — 从未引用

**修复建议：** 在对应功能中使用，或标注 `@Reserved` 说明为后续 Phase 预留。

---

### M8. `SecurityUtils.getCurrentUserId()` 抛 `IllegalStateException` 而非 401

**文件：** `security/util/SecurityUtils.java`

**问题：** `IllegalStateException` 会被全局异常处理器当作 500 处理，而非 401 Unauthorized。

**修复建议：** 自定义 `UnauthorizedException` 或提供 `Optional<Long>` 返回变体。

---

## 🔵 LOW（4）

### L1. 实体类手写 getter/setter，可用 Lombok `@Data` 精简
### L2. Flyway V9 中 `TRUNCATE vector_store` 应加注释说明仅开发阶段
### L3. `TeamProperties` 可用 `@ConfigurationPropertiesScan` 替代 `@Component`
### L4. 日志中 userId 明文，生产环境可能需要脱敏

---

## 附录：两方审查发现对比

| 发现 | GLM | DeepSeek | 最终 |
|------|-----|----------|------|
| 审批绕过（B1） | ✅ | ✅ | 共识 |
| 重名无校验（B2） | — | ✅ | 采纳 |
| setCreatorQuota 无事务（B3） | — | ✅ | 采纳 |
| N+1 查询（H1） | ✅ | ✅ | 共识 |
| dissolveTeam 无行锁（H2） | ✅ | ✅ | 共识 |
| addMember TOCTOU（H3） | — | ✅ | 采纳 |
| setCreatorQuota 越权（H4） | ✅ | ✅ | 共识 |
| rejectTimedOut 性能（H5） | ✅ | ✅ | 共识 |
| 列表无分页（H6） | — | ✅ | 采纳 |
| 审批并发（H7） | — | ✅ | 采纳 |
| 策略代码重复（M1） | ✅ | — | 保留 |
| dissolve 逐条更新（M2） | — | ✅ | 采纳 |
| 静默吞异常（M3） | — | ✅ | 采纳 |
| 枚举返回数字（M4） | ✅ | — | 保留 |
| 错误码不精确（M5） | — | ✅ | 采纳 |
| 时间类型不一致（M6） | ✅ | — | 保留 |
| 死代码 ErrorCode（M7） | — | ✅ | 采纳 |
| SecurityUtils 异常类型（M8） | — | ✅ | 采纳 |
