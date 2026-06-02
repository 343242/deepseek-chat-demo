# Phase 5: 代码质量

> 审查报告：M1, M2/M10, M3, M4, M5
> 预估：2h
> 前置：Phase 1

## 目标

消除代码重复、修复枚举序列化、补充日志和错误码。

## 子步骤

### 5.1 M1 — 模板方法重构 UploadStrategy
- **操作：**
  1. 新增 `AbstractUploadStrategy`（`@Component` 不需要，abstract 类）
     - 持有公共依赖：`DocumentValidator`, `FileStorageService`, `MinioProperties`, `RagDocumentMapper`, `EtlDispatchService`
     - 实现 `upload()` / `uploadBatch()` 模板方法
     - 定义抽象钩子：`determineStatus()` / `afterPersist()`
  2. `PersonalUploadStrategy extends AbstractUploadStrategy`
     - `determineStatus()` → `EtlStatus.UPLOADED`
     - `afterPersist()` → 无（直接触发 ETL）
  3. `TeamUploadStrategy extends AbstractUploadStrategy`
     - 额外依赖：`TeamMemberMapper`, `TeamUploadApprovalMapper`
     - `determineStatus()` → 根据角色返回 PROCESSING / PENDING_APPROVAL
     - `afterPersist()` → 创建审批记录（如需要）
  4. 删除子类中重复的 upload/uploadBatch 逻辑
- **注意：** UploadStrategyFactory 不需要改，子类仍是 @Component
- **验证：** 编译通过 + 214 测试回归

### 5.2 M2/M10 — dissolveTeam 批量更新成员
- **文件：** `TeamServiceImpl.dissolveTeam()`
- **操作：** 逐条 `updateById` → `LambdaUpdateWrapper` 批量更新
- **验证：** 编译通过

### 5.3 M3 — approveAndTriggerEtl 补充日志
- **文件：** `TeamApprovalServiceImpl.approveAndTriggerEtl()`
- **操作：** `return` 前加 `log.warn()` 记录异常情况
- **验证：** 编译通过

### 5.4 M4 — 枚举 @JsonValue 改为返回字符串
- **文件：** `ApprovalStatus`, `TeamMemberRole`, `TeamStatus`
- **操作：** `@JsonValue` 改为 `name()` 方法而非 `getCode()`，保留 `@EnumValue` 映射 int 到 DB
- **注意：** 需同步检查前端是否依赖数字值（如有需协调）
- **验证：** 编译通过 + 214 测试回归

### 5.5 M5 — removeMember 错误码精确化
- **文件：** `ErrorCode.java` + `TeamMemberServiceImpl.removeMember()`
- **操作：**
  1. 新增 `ADMIN_CANNOT_REMOVE_ADMIN(55020, "管理员不能移除其他管理员")`
  2. 替换 `NOT_TEAM_CREATOR` → `ADMIN_CANNOT_REMOVE_ADMIN`
- **验证：** 编译通过

## 完成标准

- [ ] 编译通过
- [ ] 214 测试全部通过
- [ ] git commit + push
