# 团队协作功能代码审查报告

> **审查时间：** 2026-05-14
> **审查范围：** commit `28b0f44` → HEAD（Phase 1~3），56 个文件
> **审查者：** GLM (zai/glm-5.1) + DeepSeek (deepseek-v4-pro)
> **审查标准：** ECC 技能 — `java-coding-standards`、`springboot-patterns`、`springboot-security`、`springboot-verification`、`security-review`
> **追加标准：** `.trellis/spec/backend/` 全量 spec（database-guidelines / error-handling / quality-guidelines / logging-guidelines / directory-structure）
> **分支：** `rag-dev`

---

## 总览

| 级别 | 数量 | 说明 |
|------|------|------|
| 🔴 BLOCKER | 4 | 必须修复才能上线 |
| 🟠 HIGH | 9 | 强烈建议修复 |
| 🟡 MEDIUM | 12 | 建议修复 |
| 🔵 LOW | 6 | 可选改进 |

**修复优先级：** B1 → B2 → B3 → B4 → H1 → H2 → 其余

---

## 🔴 BLOCKER（4）

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

**Spec 依据：** `quality-guidelines.md` — Security Checklist "唯一约束：业务层先查重 + 数据库 partial unique index 兜底"

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

**Spec 依据：** `database-guidelines.md` — "事务：统一使用 `TransactionTemplate` 编程式事务"

---

### B4. [spec·质量] 上传额度从未校验 — `UPLOAD_QUOTA_EXCEEDED(55009)` 定义但从未使用

**Spec 依据：** `quality-guidelines.md` — Security Checklist "唯一约束：业务层先查重 + 数据库 partial unique index 兜底"（延伸：所有业务限制应有代码层校验）

**文件：** `team/upload/TeamUploadStrategy.java` — `persistDocument()` 方法

**问题：** PRD 定义了成员上传额度（`uploadLimitMb`），ErrorCode 已定义 `UPLOAD_QUOTA_EXCEEDED(55009)`，但 `TeamUploadStrategy.persistDocument()` 从未校验当前成员已上传文档总大小是否超过额度。任何团队成员可以无限上传。

**修复建议：** 在 `persistDocument` 中查询该成员已上传文档总大小（`SELECT SUM(file_size) FROM rag_document WHERE team_id = ? AND user_id = ?`），与 `uploadLimitMb` 比较，超额抛出 `UPLOAD_QUOTA_EXCEEDED`。

---

## 🟠 HIGH（9）

### H1. N+1 查询（4处）

| 文件 | 方法 | 查询模式 |
|------|------|----------|
| `TeamServiceImpl` | `listMyTeams()` | N × 2（selectById + selectCount） |
| `TeamMemberServiceImpl` | `listMembers()` | N × 1（selectActiveById） |
| `TeamApprovalServiceImpl` | `listPending()` | N × 2（selectById × 2） |
| `TeamApprovalServiceImpl` | `listMyApprovals()` | N × 1（selectById） |

**修复建议：** 批量 `selectBatchIds()` + Map 索引，或 JOIN SQL。例如 `listMyTeams()` 可用一条 JOIN 查询 + `GROUP BY team_id COUNT(*)`。

**Spec 依据：** `database-guidelines.md` — "禁止：不使用 `selectList` 不加条件（全表扫描）"（延伸：N+1 循环查询等同于对 N 个 ID 逐个全表扫描）

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

**Spec 依据：** `quality-guidelines.md` — "check-then-act 要防并发：唯一约束兜底 + catch DuplicateKeyException 重查"

---

### H4. `setCreatorQuota()` Service 层无权限校验

**文件：** `team/service/impl/TeamServiceImpl.java` — `setCreatorQuota()` 方法

**问题：** Controller 有 `@PreAuthorize("hasAuthority('team:manage')")` 但 Service 层没校验。被其他 Service 调用时会越权。

**修复建议：** Service 内校验调用者是创建者或管理员。

**Spec 依据：** `quality-guidelines.md` — Security Checklist "权限：`@PreAuthorize` 注解保护接口"（Service 层也应有防御性校验）

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

**Spec 依据：** `database-guidelines.md` — MyBatis-Plus 配置了分页插件（`MyBatisPlusConfig.java`），应利用它

---

### H7. 审批并发防护缺失

**文件：** `team/service/impl/TeamApprovalServiceImpl.java` — `review()` 方法

**问题：** 两个管理员同时审批同一请求 → 竞态。状态检查 `status != PENDING` 在事务外。

**修复建议：** 事务内 SELECT + 状态检查 + UPDATE 原子操作。

---

### H8. [spec·质量] `setMemberUploadLimit()` 未校验额度范围 — `UPLOAD_LIMIT_OUT_OF_RANGE(55010)` 定义但从未使用

**Spec 依据：** `quality-guidelines.md` — "状态枚举：用枚举类约束，不接受裸 Integer"（延伸：所有数值型配置应有合理范围校验）

**文件：** `team/service/impl/TeamMemberServiceImpl.java` — `setMemberUploadLimit()` 方法

**问题：** `MemberUploadLimitRequest` 只校验了 `@Min(1)`，没有上限校验。可以将额度设为 999999MB。`UPLOAD_LIMIT_OUT_OF_RANGE(55010)` 已定义但从未使用。

**修复建议：** 添加 `@Max` 校验（参考 `TeamProperties` 中的上限值），或在 Service 层校验范围。

---

### H9. [spec·错误处理] `updateMemberRole()` 未校验目标是否为 CREATOR — `CANNOT_CHANGE_CREATOR_ROLE(55008)` 定义但从未使用

**Spec 依据：** `error-handling.md` — "业务异常统一抛 `BusinessException`"

**文件：** `team/service/impl/TeamMemberServiceImpl.java` — `updateMemberRole()` 方法

**问题：** 当前逻辑只校验了 `request.targetRole() != CREATOR`（不能指定为创建者），但未校验目标用户当前是否为 CREATOR。理论上如果 target 是 CREATOR，代码会允许将其角色改为 MEMBER/ADMIN（虽然 `operatorId.equals(targetUserId)` 检查了不能改自己，但创建者改其他创建者在多创建者场景下存在风险）。

**修复建议：** 在修改前增加：`if (target.getRole() == TeamMemberRole.CREATOR) throw CANNOT_CHANGE_CREATOR_ROLE`。

---

## 🟡 MEDIUM（12）

### M1. `PersonalUploadStrategy` / `TeamUploadStrategy` 大量代码重复

两个策略的 `upload()` / `uploadBatch()` 主体逻辑几乎相同（校验→存MinIO→写DB→触发ETL），只有状态和 teamId 不同。违反 DRY。

**建议：** 提取 `AbstractUploadStrategy` 模板方法基类，子类只实现 `determineStatus()` 和 `afterPersist()` 钩子。

**Spec 依据：** `quality-guidelines.md` — "设计模式优先：策略、工厂、模板方法等主动运用"

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

**Spec 依据：** `logging-guidelines.md` — "可恢复的异常/异常情况 → WARN 级别"

---

### M4. [spec·质量] 枚举 `@JsonValue` 返回数字而非字符串，违反 DTO 隔离原则

**Spec 依据：** `quality-guidelines.md` — Forbidden Patterns "裸 Integer 状态字段 → 枚举类 + 校验"

`ApprovalStatus` / `TeamMemberRole` / `TeamStatus` 的 `@JsonValue` 返回 int code（0/1/2），前端拿到裸数字。Spec 明确要求"不接受裸 Integer"，但 DTO 层的 VO 对象（`TeamVO`、`TeamMemberVO`、`ApprovalVO`）中 role/status 字段仍通过枚举的 `@JsonValue` 暴露为数字。

**建议：** VO 中 role/status 字段用 `.name()` 返回字符串（如 `"CREATOR"`、`"PENDING"`），保持 Entity 层 `@EnumValue` 用 int 不变。

---

### M5. `removeMember()` 错误码不精确

ADMIN 移除 ADMIN 时返回 `NOT_TEAM_CREATOR`，但实际语义是"管理员不能移除管理员"。

**建议：** 新增 `ADMIN_CANNOT_REMOVE_ADMIN(55020)`。

---

### M6. [spec·数据库] `PersonalUploadStrategy` 用 `LocalDateTime` 而 Team 实体用 `OffsetDateTime`

**Spec 依据：** `database-guidelines.md` — 列定义约定 "时间列：TIMESTAMPTZ（带时区），不用 TIMESTAMP"

`PersonalUploadStrategy.persistDocument()` 使用 `LocalDateTime.now()`，违反 spec 要求所有时间列用 `OffsetDateTime`。如果 `RagDocument.createTime` 映射到 `TIMESTAMPTZ` 列则类型不匹配。

**修复建议：** 统一 `OffsetDateTime.now()`。

---

### M7. [spec·错误处理] 3 个 ErrorCode 已定义但从未使用（死代码）

**Spec 依据：** `error-handling.md` — "业务异常统一抛 `BusinessException`"

- `CANNOT_CHANGE_CREATOR_ROLE(55008)` — 从未引用（见 H9）
- `UPLOAD_QUOTA_EXCEEDED(55009)` — 从未引用（见 B4）
- `UPLOAD_LIMIT_OUT_OF_RANGE(55010)` — 从未引用（见 H8）

**修复建议：** 在对应功能中使用（B4/H8/H9 已规划）。

---

### M8. [spec·错误处理] `SecurityUtils.getCurrentUserId()` 抛 `IllegalStateException` 而非 `BusinessException`

**Spec 依据：** `error-handling.md` — Forbidden Patterns "不要抛 `IllegalArgumentException`，用 `BusinessException` 替代" + Exception Hierarchy 中 401 应走 `AuthenticationException`

**文件：** `security/util/SecurityUtils.java`

**问题：** `IllegalStateException` 不在 `GlobalExceptionHandler` 的异常体系内，会被当作 500 `internal_error` 处理，而非 401 `unauthorized`。Spec 明确要求异常走统一体系。

**修复建议：** 改抛 `AuthenticationException`（401），或自定义 `UnauthorizedException` 并在 `GlobalExceptionHandler` 中注册映射。

---

### M9. [spec·目录结构] `PersonalUploadStrategy` 放在 `team/upload/` 下语义不清

**Spec 依据：** `directory-structure.md` — "一个功能模块的 entity/mapper/service/dto/controller 放在同一个包下"

`PersonalUploadStrategy` 本质是 rag 模块的逻辑（个人上传不涉及团队），放在 `team` 包下语义不清。

**建议：** `PersonalUploadStrategy` 应留在 `rag/upload/` 或公共位置，只有 `TeamUploadStrategy` 和 `UploadStrategyFactory` 在 `team/upload/`。或者将 `UploadStrategy` 接口移到 `common/` 下。

---

### M10. [spec·数据库] `dissolveTeam()` 逐条操作且批量 UPDATE 更规范

**Spec 依据：** `database-guidelines.md` — "事务：统一使用 `TransactionTemplate` 编程式事务"

`dissolveTeam()` 虽然在事务内，但逐条 `updateById` 效率低。更关键的是：如果循环中途异常，事务回滚正确，但批量 `LambdaUpdateWrapper` 是更规范的做法。

**修复建议：** 合并到 M2 一并修复，使用批量 `LambdaUpdateWrapper`。

---

### M11. [spec·日志] `PersonalUploadStrategy.uploadBatch()` 循环内打 INFO 级别日志

**Spec 依据：** `logging-guidelines.md` — "不在循环中打 INFO 以上级别的日志"

```java
for (MultipartFile file : files) {
    ...
    log.info("Document uploaded (batch): id={}, file={}, size={}, userId={}", ...);  // 循环内 INFO
}
```

**修复建议：** 循环内改为 `log.debug()`，循环外汇总一条 `log.info()`。

---

### M12. [spec·数据库] `TeamServiceImpl.createTeam()` 中手动设置 `createdAt`/`updatedAt`

**Spec 依据：** `database-guidelines.md` — "默认值：在 DDL 中定义 `DEFAULT`，不依赖应用层"

V9 迁移中 `team` 和 `team_member` 表的 `created_at`/`updated_at` 已有 `DEFAULT NOW()`，但 Java 代码仍手动设置 `team.setCreatedAt(OffsetDateTime.now())`。双重设置无害但不一致。

**建议：** 去掉 Java 层手动设置，依赖 DB 默认值；或统一在 Java 层设置（确保 updated_at 在 update 时总是被设置）。选择一种方式保持一致。

---

## 🔵 LOW（6）

### L1. 实体类手写 getter/setter，可用 Lombok `@Data` 精简

### L2. [spec·数据库] Flyway V9 中 `TRUNCATE vector_store` 破坏性操作无注释保护

**Spec 依据：** `database-guidelines.md` — "迁移脚本必须幂等"

`TRUNCATE TABLE vector_store;` 不是幂等的（重复执行无害但语义不对），且在生产环境会丢失数据。应加注释说明仅开发阶段使用。

### L3. [spec·质量] `TeamProperties` 用 `@Component` + `@ConfigurationProperties` 而非推荐的 `@ConfigurationPropertiesScan`

**Spec 依据：** `quality-guidelines.md` — "批判式思考：每次编码前审视"（使用更现代的 Spring Boot 模式）

### L4. [spec·日志] 日志中 userId 明文，生产环境可能需要脱敏

**Spec 依据：** `logging-guidelines.md` — "不记录敏感用户信息"

### L5. [spec·目录结构] `DocumentOwnershipChecker` 放在 `team/security/` 而非 `common/`

**Spec 依据：** `directory-structure.md` — 跨模块基础设施工具放 `common/` 或对应功能模块

`DocumentOwnershipChecker` 既涉及 rag 又涉及 team，放在 `team/security/` 语义偏窄。建议移到 `common/` 或保留但重命名为更明确的位置。

### L6. [spec·数据库] V9 迁移中 `team_member.role` 默认值为 10 而非枚举描述

`role SMALLINT NOT NULL DEFAULT 10` — 裸数字 10 对应 `MEMBER`。DDL 层裸数字可接受（数据库不识枚举），但 COMMENT 应更详细：`10=MEMBER(默认) 20=ADMIN 30=CREATOR`。

---

## 附录 A：两方审查发现对比

| 发现 | GLM | DeepSeek | 最终 |
|------|-----|----------|------|
| 审批绕过（B1） | ✅ | ✅ | 共识 |
| 重名无校验（B2） | — | ✅ | 采纳 |
| setCreatorQuota 无事务（B3） | — | ✅ | 采纳 |
| 上传额度未校验（B4） | — | — | spec 补充 |
| N+1 查询（H1） | ✅ | ✅ | 共识 |
| dissolveTeam 无行锁（H2） | ✅ | ✅ | 共识 |
| addMember TOCTOU（H3） | — | ✅ | 采纳 |
| setCreatorQuota 越权（H4） | ✅ | ✅ | 共识 |
| rejectTimedOut 性能（H5） | ✅ | ✅ | 共识 |
| 列表无分页（H6） | — | ✅ | 采纳 |
| 审批并发（H7） | — | ✅ | 采纳 |
| 额度范围未校验（H8） | — | — | spec 补充 |
| CREATOR 角色未校验（H9） | — | — | spec 补充 |
| 策略代码重复（M1） | ✅ | — | 保留 |
| dissolve 逐条更新（M2/M10） | — | ✅ | 采纳+spec 强化 |
| 静默吞异常（M3） | — | ✅ | 采纳 |
| 枚举返回数字（M4） | ✅ | — | spec 强化 |
| 错误码不精确（M5） | — | ✅ | 采纳 |
| 时间类型不一致（M6） | ✅ | — | 保留+spec 依据 |
| 死代码 ErrorCode（M7） | — | ✅ | 采纳+spec 强化 |
| SecurityUtils 异常类型（M8） | — | ✅ | 采纳+spec 依据 |
| PersonalUpload 位置（M9） | — | — | spec 补充 |
| 手动设置时间戳（M12） | — | — | spec 补充 |
| 循环内 INFO 日志（M11） | — | — | spec 补充 |

---

## 附录 B：Trellis Spec 核查清单

基于 `.trellis/spec/backend/` 逐项核查：

| Spec 规则 | 核查结果 | 关联问题 |
|-----------|---------|---------|
| **事务：TransactionTemplate，不用 @Transactional** | ⚠️ 部分违反 | B3（setCreatorQuota 无事务）、H3（addMember 无事务） |
| **异常：BusinessException，不用 IllegalArgumentException** | ⚠️ 部分违反 | M8（SecurityUtils 用 IllegalStateException） |
| **DTO：record + @Valid** | ✅ 合规 | 所有 DTO 均为 record + @Valid |
| **状态字段：枚举类约束，不用裸 Integer** | ⚠️ API 层暴露裸数字 | M4（@JsonValue 返回 int） |
| **唯一约束：业务层查重 + DB partial unique index 兜底** | ⚠️ 缺业务层查重 | B2（团队名称）、H3（成员唯一性） |
| **软删除：查询条件必须包含 deleted=0** | ✅ 合规 | team 表用 @TableLogic，team_member 用显式 WHERE status=1 |
| **时间列：TIMESTAMPTZ，不用 TIMESTAMP** | ⚠️ 部分违反 | M6（PersonalUploadStrategy 用 LocalDateTime） |
| **Flyway 迁移：必须幂等** | ⚠️ TRUNCATE 不幂等 | L2 |
| **默认值：DDL 定义，不依赖应用层** | ⚠️ 不一致 | M12（Java 手动设置 createdAt） |
| **编程式事务** | ⚠️ 部分方法未包裹 | B3、H3 |
| **设计模式优先** | ✅ 合规 | 策略模式（UploadStrategy）+ 工厂模式（UploadStrategyFactory） |
| **OCP 强制** | ✅ 合规 | 新增团队功能未修改现有个人上传逻辑 |
| **封装彻底** | ⚠️ 部分泄漏 | M9（PersonalUploadStrategy 位置）、L5（DocumentOwnershipChecker 位置） |
| **日志：不在循环中打 INFO** | ⚠️ 违反 | M11 |
| **日志：参数化日志** | ✅ 合规 | 所有日志均为参数化格式 |
| **Controller 不处理异常** | ✅ 合规 | Controller 只做参数接收和响应封装 |
| **Entity 不暴露给前端** | ✅ 合规 | 所有返回均通过 DTO/VO 转换 |
| **分页插件** | ⚠️ 未使用 | H6 |
| **目录结构** | ⚠️ 部分不一致 | M9、L5 |
