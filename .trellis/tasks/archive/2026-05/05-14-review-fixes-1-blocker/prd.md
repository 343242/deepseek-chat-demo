# Phase 1: BLOCKER 修复

> 审查报告：`docs/TEAM-CODE-REVIEW.md` — B1, B2, B3, B4
> 预估：2h

## 目标

修复 4 个阻塞级别问题，使审批流真正生效、数据一致性有保障。

## 子步骤

### 1.1 B2 — 团队名称重复校验
- **文件：** `TeamServiceImpl.createTeam()`
- **操作：** `try-catch DuplicateKeyException` → 抛 `TEAM_NAME_DUPLICATE`
- **验证：** 手动测试同名校验

### 1.2 B3 — setCreatorQuota 双写加事务
- **文件：** `TeamServiceImpl.setCreatorQuota()`
- **操作：** 包裹 `txTemplate.executeWithoutResult()`
- **验证：** 编译通过

### 1.3 B4 — 上传额度校验
- **文件：** `TeamUploadStrategy.persistDocument()`
- **操作：**
  1. 注入 `RagDocumentMapper`（已有）
  2. 新增方法查询成员已上传总量：`SELECT COALESCE(SUM(file_size),0) FROM rag_document WHERE team_id=? AND user_id=? AND status NOT IN ('REJECTED')`
  3. 与成员 `uploadLimitMb` 比较，超额抛 `UPLOAD_QUOTA_EXCEEDED(55009)`
- **验证：** 编译通过

### 1.4 B1 — 审批流接入
- **文件：** `TeamUploadStrategy`
- **操作：**
  1. 注入 `TeamMemberMapper`
  2. `persistDocument()` 根据角色判断状态：
     - CREATOR/ADMIN → `PROCESSING` + 直接触发 ETL
     - MEMBER → `PENDING_APPROVAL` + 创建 `TeamUploadApproval` 记录（注入 `TeamUploadApprovalMapper`）
  3. `upload()` 和 `uploadBatch()` 中只对 `PROCESSING` 状态触发 ETL（当前已是，确认无遗漏）
- **验证：** 编译通过 + 214 测试回归

## 完成标准

- [ ] 编译通过
- [ ] 214 测试全部通过
- [ ] git commit + push
