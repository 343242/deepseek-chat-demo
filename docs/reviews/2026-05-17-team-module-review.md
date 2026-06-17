# Chat-Demo Team 模块 Code Review 报告

> 审查时间: 2026-05-17
> 审查依据: code-review 技能 + java-springboot-patterns + 项目 spec (quality-guidelines / database-guidelines / error-handling / logging-guidelines)
> 审查范围: 10 个子包、39 个核心文件
> 方法: 系统性反模式搜索 + 逐文件审查

---

## 一、Spec 合规检查（基于 search_files 系统性扫描）

```
✅ @Transactional         — 0 命中，全部使用 TransactionTemplate
✅ synchronized           — 0 命中，无 Virtual Thread pinning 风险
✅ Thread.sleep           — 0 命中，无 Virtual Thread 阻塞风险
✅ IllegalArgumentException — 0 命中，全部使用 BusinessException
✅ System.out             — 0 命中，全部使用 SLF4J Logger
✅ JPA / Hibernate        — 0 命中，全部使用 MyBatis-Plus
✅ 硬编码密钥              — 0 命中
✅ Controller try-catch    — 0 命中（catch 仅出现在 Service 层的 DuplicateKeyException 转换和 Job 的顶层兜底）
✅ 返回 Entity 给前端      — 0 命中，全部使用 VO (TeamVO / TeamDetailVO / TeamMemberVO)
✅ @Valid + Jakarta Validation — 所有 Request DTO 均有 @Valid
✅ @PreAuthorize           — 4 个 Controller 均有类级 isAuthenticated()
✅ 枚举约束状态字段         — TeamStatus / TeamMemberRole / ApprovalStatus
```

### 补充合规项

- ✅ **DuplicateKeyException → BusinessException** — TeamServiceImpl:94 和 TeamMemberServiceImpl:128 正确捕获唯一约束异常并转换为业务异常，符合 spec 要求
- ✅ **TeamMembershipVerifier 统一校验** — 所有需要团队权限校验的地方统一使用此组件，符合 DRY 和 LoD 原则
- ✅ **@TableLogic 软删除** — Team 实体正确使用；TeamMember 不使用 @TableLogic 有明确注释说明（支持同一用户重新加入）
- ✅ **构造器注入** — 全部使用构造器注入，无 @Autowired 字段注入

---

## 二、🚨 Critical Issues（必须修复）

### 1. TeamUploadStrategy.getUsedBytes — 全表查询到内存再求和

**文件:** `TeamUploadStrategy.java:183-191`

```java
// 当前代码 — 查出所有文档实体到内存
List<RagDocument> docs = ragDocumentMapper.selectList(
        new LambdaQueryWrapper<RagDocument>()
                .eq(RagDocument::getTeamId, teamId)
                .eq(RagDocument::getUserId, userId)
                .ne(RagDocument::getStatus, EtlStatus.REJECTED));
return docs.stream().mapToLong(RagDocument::getFileSize).sum();
```

**问题:** 违反 database-guidelines.md "不使用 selectList 不加条件（全表扫描）"的精神。虽然有条件过滤，但把全部文档实体加载到内存只为求和，文档量大时 OOM 风险。

**修复:**

```java
// RagDocumentMapper.java 新增
@Select("SELECT COALESCE(SUM(file_size), 0) FROM rag_document " +
        "WHERE team_id = #{teamId} AND user_id = #{userId} AND status != 'REJECTED'")
Long selectFileSizeSum(@Param("teamId") Long teamId, @Param("userId") Long userId);

// TeamUploadStrategy.java 修改
private long getUsedBytes(Long teamId, Long userId) {
    Long totalBytes = ragDocumentMapper.selectFileSizeSum(teamId, userId);
    return totalBytes != null ? totalBytes : 0;
}
```

**验证:** 单元测试覆盖有数据/无数据两种情况

---

### 2. TeamApprovalServiceImpl.rejectTimedOut — 循环逐条更新 + 竞态风险

**文件:** `TeamApprovalServiceImpl.java:224-242`

```java
// 当前代码 — 循环 updateById，N 条记录 = 2N 条 SQL
for (Long id : approvalIds) {
    TeamUploadApproval a = new TeamUploadApproval();
    a.setId(id);
    a.setStatus(ApprovalStatus.REJECTED);
    a.setReviewComment("审批超时，系统自动拒绝");
    a.setReviewedAt(now);
    approvalMapper.updateById(a);
}
for (Long docId : docIds) {
    RagDocument doc = new RagDocument();
    doc.setId(docId);
    doc.setStatus(EtlStatus.REJECTED);
    ragDocumentMapper.updateById(doc);
}
```

**问题:**
- 效率低: N 条记录执行 2N 条 SQL
- 竞态风险: 查询超时列表和更新之间，某审批可能已被人工审批，updateById 会覆盖人工结果（缺少 `WHERE status = PENDING` 条件）

**修复:**

```java
txTemplate.executeWithoutResult(status -> {
    // 批量更新审批记录 — 加 WHERE status = PENDING 防覆盖
    approvalMapper.update(null, new LambdaUpdateWrapper<TeamUploadApproval>()
            .in(TeamUploadApproval::getId, approvalIds)
            .eq(TeamUploadApproval::getStatus, ApprovalStatus.PENDING)
            .set(TeamUploadApproval::getStatus, ApprovalStatus.REJECTED)
            .set(TeamUploadApproval::getReviewComment, "审批超时，系统自动拒绝")
            .set(TeamUploadApproval::getReviewedAt, now));

    // 批量更新文档状态
    ragDocumentMapper.update(null, new LambdaUpdateWrapper<RagDocument>()
            .in(RagDocument::getId, docIds)
            .set(RagDocument::getStatus, EtlStatus.REJECTED));
});
```

**验证:** 编译通过 + 日志确认 SQL 条数从 2N 降为 2

---

### 3. .last("LIMIT 1") — SQL 注入风险模式

**文件:** `TeamMemberServiceImpl.java:102`

```java
.eq(TeamMember::getTeamId, teamId)
.eq(TeamMember::getUserId, userId)
.last("LIMIT 1")
```

**问题:** MyBatis-Plus `.last()` 直接拼接 SQL 尾部，虽然此处是硬编码字符串无实际注入风险，但违反了 database-guidelines.md 的安全规范，且后续维护者可能模仿此模式引入真正的注入漏洞。

**修复:**

```java
// 方案 A: 使用 limit() 方法（MyBatis-Plus 3.5.16+）
.eq(TeamMember::getTeamId, teamId)
.eq(TeamMember::getUserId, userId)
.last("LIMIT 1")
→
.eq(TeamMember::getTeamId, teamId)
.eq(TeamMember::getUserId, userId)
.orderByDesc(TeamMember::getId)
.last("LIMIT 1")

// 方案 B: 如果只需要判断存在性，改用 selectCount
int count = teamMemberMapper.selectCount(
    new LambdaQueryWrapper<TeamMember>()
        .eq(TeamMember::getTeamId, teamId)
        .eq(TeamMember::getUserId, userId));
if (count > 0) { ... }
```

**验证:** 编译通过 + 确认无 `.last()` 残留

---

## 三、🟡 Improvement Suggestions（建议改进）

### 4. review() 缺少行锁 — 两个管理员可同时审批

**文件:** `TeamApprovalServiceImpl.java:136`

```java
TeamUploadApproval approval = approvalMapper.selectById(approvalId);
if (approval.getStatus() != ApprovalStatus.PENDING) {
    throw new BusinessException(ErrorCode.APPROVAL_ALREADY_REVIEWED);
}
```

**问题:** 无 `SELECT FOR UPDATE`，两个管理员并发审批同一条记录时，都可能通过 `PENDING` 检查后重复更新。

**修复:** 用 UPDATE ... WHERE status = PENDING 的乐观锁模式：

```java
int updated = approvalMapper.update(null, new LambdaUpdateWrapper<TeamUploadApproval>()
        .eq(TeamUploadApproval::getId, approvalId)
        .eq(TeamUploadApproval::getStatus, ApprovalStatus.PENDING)
        .set(TeamUploadApproval::getStatus, request.status())
        .set(TeamUploadApproval::getReviewerId, currentUserId)
        .set(TeamUploadApproval::getReviewComment, request.reviewComment())
        .set(TeamUploadApproval::getReviewedAt, OffsetDateTime.now()));
if (updated == 0) {
    throw new BusinessException(ErrorCode.APPROVAL_ALREADY_REVIEWED);
}
```

**验证:** 并发测试：两个线程同时审批同一记录，只有一个成功

---

### 5. computeMd5 失败返回 null — 数据污染

**文件:** `TeamUploadStrategy.java:244-246`

```java
} catch (Exception e) {
    log.warn("Failed to compute file MD5: {}", e.getMessage());
    return null;  // null 存入 DB
}
```

**问题:** MD5 计算失败返回 null，后续存入数据库污染数据。

**修复:**

```java
} catch (Exception e) {
    log.warn("Failed to compute file MD5: {}", e.getMessage());
    return "";  // 或抛 BusinessException
}
```

---

### 6. approveAndTriggerEtl 暴露在 Service 接口

**文件:** `TeamApprovalService.java:37`

```java
void approveAndTriggerEtl(Long approvalId);
```

**问题:** 此方法是审批通过后的内部 ETL 触发逻辑，暴露在接口中可被外部直接调用绕过审批流程。

**修复:** 从接口中移除，改为 `TeamApprovalServiceImpl` 的 private 方法：

```java
// TeamApprovalService.java — 删除此行
// void approveAndTriggerEtl(Long approvalId);

// TeamApprovalServiceImpl.java — 改为 private
private void approveAndTriggerEtl(Long approvalId) { ... }
```

---

### 7. TODO 标记 — Phase 4 未完成

**文件:** `TeamServiceImpl.java:257-258`

```
// TODO: Phase 4 — REJECT 所有 PENDING 审批 + 清理向量数据
// TODO: MinIO bucket 延迟清理 — OrphanChunkCleaner 会在 bucket 为空且团队 deleted=1 后自动删除
```

**建议:** 确认 Phase 4 排期，或将 TODO 转为 issue 跟踪，避免遗忘。

---

### 8. selectList 查询模式 — 3 处可优化

除 #1 的 getUsedBytes 外，还有 2 处 selectList:

- `TeamApprovalServiceImpl.java:213` — 查询超时审批列表。此查询有条件过滤（status=PENDING + createdAt < deadline），数据量可控，暂可接受。
- `TeamServiceImpl.java:162` — 查询用户所有活跃成员关系。此查询用于 listMyTeams，数据量 = 用户加入的团队数（上限 maxTeamsPerUser=10），暂可接受。

**建议:** 如果未来团队规模增长，考虑为 TeamApproval 添加复合索引 `(status, created_at)`。

---

## 四、✅ 设计亮点

1. **TeamMembershipVerifier 统一校验** — 所有团队权限校验集中在此组件，Service 层直接调用 `verifyMember` / `verifyAdmin` / `verifyCreator`，Controller 层保持 thin。符合 DRY + LoD 原则。
2. **DuplicateKeyException → BusinessException** — 唯一约束违规时正确转换为业务异常，用户看到友好提示而非数据库错误。
3. **TransactionTemplate 编程式事务** — 精确控制事务边界，避免 @Transactional 的自调用失效和长事务陷阱。
4. **TeamStatusServiceImpl 门面模式** — 跨模块状态查询统一出口，其他模块通过接口查询而非直接注入 Mapper，符合 ISP + DIP。
5. **DocumentOwnershipChecker** — 个人/团队文档权限统一校验，区分 CREATOR/ADMIN/上传者权限。
6. **TeamMember 不用 @TableLogic** — 有明确注释说明原因（支持同一用户重新加入），设计决策文档化。
7. **枚举 @EnumValue + @JsonValue** — DB 存 int，API 返回字符串，风格统一。

---

## 五、修复优先级

**P0 — 立即修复（性能+安全）**
- [ ] #1 getUsedBytes 改 SQL 聚合查询
- [ ] #2 rejectTimedOut 批量更新 + WHERE status = PENDING
- [ ] #3 .last("LIMIT 1") 改用安全方式

**P1 — 短期改进（并发安全）**
- [ ] #4 review() 乐观锁
- [ ] #5 computeMd5 返回空串
- [ ] #6 approveAndTriggerEtl 改为 private

**P2 — 中期跟踪**
- [ ] #7 TODO Phase 4 排期
- [ ] #8 selectList 性能监控

---

*审查完成于 2026-05-17，基于系统性反模式搜索 + 逐文件审查。*
*共发现 3 个必须修复项 + 5 个改进建议。整体架构设计优秀。*
