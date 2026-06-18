# Phase 4: 校验补全

> 审查报告：H8, H9
> 预估：0.5h
> 前置：Phase 1

## 目标

启用已定义但从未使用的 ErrorCode。

## 子步骤

### 4.1 H8 — 额度范围校验
- **文件：** `MemberUploadLimitRequest` + `TeamMemberServiceImpl.setMemberUploadLimit()`
- **操作：**
  1. `MemberUploadLimitRequest` 添加 `@Max(value = ..., message = "...")` 注解（上限参考 TeamProperties）
  2. 或 Service 层校验范围，超范围抛 `UPLOAD_LIMIT_OUT_OF_RANGE(55010)`
- **验证：** 编译通过

### 4.2 H9 — CREATOR 角色保护
- **文件：** `TeamMemberServiceImpl.updateMemberRole()`
- **操作：** 在修改前增加校验：
  ```java
  if (target.getRole() == TeamMemberRole.CREATOR) {
      throw new BusinessException(ErrorCode.CANNOT_CHANGE_CREATOR_ROLE);
  }
  ```
- **验证：** 编译通过

## 完成标准

- [ ] 编译通过
- [ ] 214 测试全部通过
- [ ] git commit + push
