# user 模块 code review 修复

## Goal

落地 `docs/code-review/2026-06-20-user-module-review.md` 中确认的 4 项修复，消除 1 处事务缺陷、1 处 spec 硬违规、1 处 latent 授权漏洞、1 处生产配置缺口。

## Requirements

- **R1（HIGH·事务）**：`SysRoleServiceImpl.deleteRole` 三表删除包进 `TransactionTemplate`，并修正缓存驱逐顺序（先捕获 userIds → 事务提交 → 再驱逐）。
- **R2（HIGH·规范）**：`RoleController` / `SysRoleService` / `RoleDetailVO` 不再直返/内嵌 Entity，新增 `RoleVO`/`PermissionVO`，剔除 `deleted` 等内部字段。
- **R3（HIGH·latent 安全）**：三个 mapper 查询补 role status/deleted 过滤（`SysRoleMapper.selectByIds`、`SysUserRoleMapper.selectRoleIdsByUserId`、`SysRolePermissionMapper.selectPermissionsByRoleIds`）—— 纯 SQL 谓词收窄，不改方法签名。
- **R4（MEDIUM·配置）**：`application-stable.yml`（及 evaluation）补 `app.jwt.cookie-secure: true`。

## Constraints

- 改前对每个符号跑 `impact`（已做：deleteRole=LOW，三个 mapper=CRITICAL-path 但谓词收窄安全）。
- 禁 `@Transactional`，用 `TransactionTemplate`；禁 JPA；SQL 全 `#{}`；DTO 用 record。
- R3 改动在鉴权主链（login/refresh/chat），改后必须跑全量 `mvnw test`。

## Acceptance Criteria

- [ ] R1：`deleteRole` 写操作在单一事务内；缓存驱逐在事务提交后；`getRoleNames`/assignPermissions 既有行为不变。
- [ ] R2：`RoleController` 5 个端点返回 VO（无 `SysRole`/`SysPermission` Entity 出现在响应）；`RoleDetailVO` 字段为 VO 类型。
- [ ] R3：禁用角色（status=0）的角色名不进 JWT、其权限不被 `loadUserPermissions` 加载；正常角色不受影响。
- [ ] R4：stable/evaluation profile 下 `cookie-secure=true`。
- [ ] `./mvnw test` 全绿；`detect_changes` 受影响执行流仅限预期范围、无意外回归。
- [ ] commit 符合 Conventional Commits（`fix(user,...)`）。

## Notes

- 完整分析见 `docs/code-review/2026-06-20-user-module-review.md`（design 依据）。
- 不在本任务范围：MEDIUM 的 M1(register RuntimeException)/M2/M5-M11 及 LOW 项 —— 后续单独处理。
