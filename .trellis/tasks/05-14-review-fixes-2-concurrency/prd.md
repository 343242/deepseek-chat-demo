# Phase 2: 并发安全修复

> 审查报告：H2, H3, H4, H7
> 预估：1.5h
> 前置：Phase 1

## 目标

消除竞态条件和权限漏洞。

## 子步骤

### 2.1 H2 — dissolveTeam 加行锁
- **文件：** `TeamServiceImpl.dissolveTeam()`
- **操作：** `selectById` → `LambdaQueryWrapper.last("FOR UPDATE")`（事务内）
- **注意：** 需在 Mapper 层新增 `selectByIdForUpdate(Long id)` 方法，用 `@Select("SELECT * FROM team WHERE id = #{id} FOR UPDATE")`
- **验证：** 编译通过

### 2.2 H3 — addMember 加事务
- **文件：** `TeamMemberServiceImpl.addMember()`
- **操作：** 整个方法体包裹 `txTemplate.execute()`，返回值从内部取出
- **补充：** catch `DuplicateKeyException` → 查重后抛 `ALREADY_TEAM_MEMBER`（唯一索引兜底）
- **验证：** 编译通过

### 2.3 H4 — setCreatorQuota Service 层权限校验
- **文件：** `TeamServiceImpl.setCreatorQuota()`
- **操作：** 在事务内校验调用者是创建者（`team.getCreatorId().equals(SecurityUtils.getCurrentUserId())`），非创建者抛 `NOT_TEAM_CREATOR`
- **验证：** 编译通过

### 2.4 H7 — 审批并发防护
- **文件：** `TeamApprovalServiceImpl.review()`
- **操作：** 将 `approval` 的查询和状态检查移入事务内，状态校验 + 更新在同一事务
- **验证：** 编译通过

## 完成标准

- [ ] 编译通过
- [ ] 214 测试全部通过
- [ ] git commit + push
