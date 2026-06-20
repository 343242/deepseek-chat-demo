# Implement — user 模块 review 修复

执行顺序：先低风险、先独立。每项独立 commit。

## Step 1 — R1 deleteRole 事务（LOW risk）
- [ ] 编辑 `service/impl/SysRoleServiceImpl.java` `deleteRole`：捕获 userIds → 包事务 → 提交后驱逐
- [ ] `./mvnw test -pl . -Dtest=SysRoleServiceTest` 绿

## Step 2 — R4 cookie-secure 配置（无代码风险）
- [ ] `application-stable.yml` 加 `cookie-secure: true`
- [ ] `application-evaluation.yml` 加 `cookie-secure: true`
- [ ] `./mvnw test` 编译通过

## Step 3 — R3 mapper role-status 过滤（CRITICAL 路径，谓词收窄）
- [ ] `SysRoleMapper.xml selectByIds` 加 `AND status = 1`
- [ ] `SysUserRoleMapper.xml selectRoleIdsByUserId` join sys_role + `r.status=1 AND r.deleted=0`
- [ ] `SysRolePermissionMapper.xml selectPermissionsByRoleIds` 加 join sys_role + role 过滤
- [ ] **全量** `./mvnw test` 绿（重点：AuthServiceTest / SysRoleServiceTest / DatabaseUserPermissionProviderTest / SysUserServiceTest）

## Step 4 — R2 Entity→VO（API 契约变更）
- [ ] 新增 `dto/RoleVO.java`、`dto/PermissionVO.java`
- [ ] `SysRoleService` 接口 4 个方法返回类型改 VO
- [ ] `SysRoleServiceImpl` 加 `toRoleVO`/`toPermissionVO` 并改返回
- [ ] `RoleDetailVO` 字段改 VO 类型；`SysRoleServiceImpl.getRoleDetail` 适配
- [ ] `RoleController` 5 个端点返回类型改 VO
- [ ] `./mvnw test` 绿（RoleControllerTest 需适配新响应结构）

## Step 5 — 验证与收尾
- [ ] `detect_changes({scope:"unstaged"})` —— 受影响执行流仅限预期
- [ ] `./mvnw test` 全绿
- [ ] 分项 commit（`fix(user,role): ...`），push
- [ ] 更新 spec（若有新约定）+ 归档任务

## 验证命令
```bash
./mvnw test -q
# 单测聚焦
./mvnw test -Dtest='AuthServiceTest,SysRoleServiceTest,SysUserServiceTest,DatabaseUserPermissionProviderTest,RoleControllerTest'
```

## 回滚点
每 Step 一个 commit；`git revert <sha>` 单点回退。
