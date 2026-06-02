# Phase 3: 性能修复

> 审查报告：H1, H5, H6
> 预估：2h
> 前置：无（可与 Phase 2 并行）

## 目标

消除 N+1 查询、批量操作优化、列表接口分页。

## 子步骤

### 3.1 H1a — listMyTeams N+1 修复
- **文件：** `TeamServiceImpl.listMyTeams()`
- **操作：**
  1. 批量查 Team：`teamMapper.selectBatchIds(teamIds)`
  2. 批量查成员数：在 `TeamMemberMapper` 新增 `@Select` 按 team_id GROUP BY COUNT
  3. 组装结果
- **验证：** 编译通过

### 3.2 H1b — listMembers N+1 修复
- **文件：** `TeamMemberServiceImpl.listMembers()`
- **操作：** 收集所有 userId → `sysUserMapper.selectBatchIds(userIds)` → Map 索引
- **验证：** 编译通过

### 3.3 H1c — listPending / listMyApprovals N+1 修复
- **文件：** `TeamApprovalServiceImpl`
- **操作：**
  1. `listPending()`：批量查 `ragDocumentMapper.selectBatchIds(docIds)` + `sysUserMapper.selectBatchIds(uploaderIds)`
  2. `listMyApprovals()`：批量查 `ragDocumentMapper.selectBatchIds(docIds)`
- **验证：** 编译通过

### 3.4 H5 — rejectTimedOut 批量处理
- **文件：** `TeamApprovalServiceImpl.rejectTimedOut()`
- **操作：**
  1. 改为单一事务 + 批量 `LambdaUpdateWrapper`
  2. 批量更新审批记录状态
  3. 批量更新对应文档状态（收集 documentIds → 批量 UPDATE）
  4. `ApprovalTimeoutJob` 改用 `fixedDelay`
- **验证：** 编译通过

### 3.5 H6 — 列表接口分页
- **操作：**
  1. `listMyTeams()` → 返回 `List<TeamVO>`（已有 maxTeamsPerUser=10 上限，不需要分页）
  2. `listMembers()` → 添加 `@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "50") int size`，用 MyBatis-Plus `Page`
  3. `listPending()` → 同上
  4. `listMyApprovals()` → 同上
  5. Service/Controller 同步修改
- **验证：** 编译通过 + 214 测试回归

## 完成标准

- [ ] 编译通过
- [ ] 214 测试全部通过
- [ ] git commit + push
