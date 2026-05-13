# 团队模块复盘：从设计到修复

> 项目：chat-demo（Spring Boot + MyBatis-Plus + PG + MinIO）
> 分支：rag-dev
> 时间跨度：2026-05-08 ~ 2026-05-14

---

## 一、时间线总览

```
05-08  PRD v1.0 编写
  ↓     ↓ 双视角架构审查（GLM + DeepSeek）
05-09  PRD v1.1 → v1.2 修订（采纳审查意见）
  ↓     ↓ trellis spec 合规审查
05-10  Phase 1: 共享组件 + EtlCandidate 改造
  ↓
05-11  Phase 2: 策略模式 + 回归测试
  ↓     Phase 3: 团队模块全部功能（CRUD + 成员管理 + 审批流 + 额度）
  ↓     Phase 4: RAG 检索改造（向量隔离 + teamId 穿透）
05-13  端到端联调 + 回归测试（214 测试全绿）
  ↓
05-14  代码审查 → 6 Phase 修复（3B + 7H + 8M + 4L = 31 项问题）
```

---

## 二、开发阶段回顾

### Phase 1 — 共享组件（4h）

**做了什么：**
- 新增 `EtlCandidate` record，统一 ETL 调度参数（加入 `teamId` 字段）
- 改造 `EtlDispatchService` 接口，支持批量调度
- 新增 `team` 相关枚举（`TeamStatus`、`TeamMemberRole`、`ApprovalStatus`）

**踩坑：** 无大坑。这步主要是接口设计，后续修改少。

### Phase 2 — 策略模式（6h）

**做了什么：**
- `UploadStrategy` 接口（个人 vs 团队上传路由）
- `PersonalUploadStrategy`：原有个人上传逻辑提取
- `TeamUploadStrategy`：团队上传 + MinIO 存储 + EtlCandidate 构建
- `UploadStrategyFactory`：根据 `teamId == null` 路由
- 单文件/批量上传两种模式

**踩坑：** 这步的 `TeamUploadStrategy` 写得太"顺利"——审批流逻辑完全没接入，直接 `PROCESSING`。审查时发现这是 **BLOCKER B1**。

**教训：** 策略模式的路由逻辑容易写对，但**策略内部的业务规则**（审批、额度、权限）才是难点。写策略时应该先写业务规则的 TODO 清单。

### Phase 3 — 团队模块全部功能（18h）

**做了什么：**
- 团队 CRUD：创建、更新、解散（`TeamServiceImpl`）
- 成员管理：邀请、移除、退团、角色变更、额度设置（`TeamMemberServiceImpl`）
- 审批流：提交审批、审批操作、超时自动拒绝（`TeamApprovalServiceImpl`）
- 定时任务：`ApprovalTimeoutJob`
- 权限校验：`DocumentOwnershipChecker`
- 成员资格验证：`TeamMembershipVerifier`
- DDL：V9 迁移脚本（team + team_member + team_upload_approval 三张表）

**踩坑最多的一步：**
1. `dissolveTeam` 逐条 `updateById` 移除成员 → 审查发现 N+1 + 无行锁
2. `addMember` 没有 DuplicateKey 兜底 → 并发邀请同一用户会抛 DB 异常
3. `setCreatorQuota` 没有权限校验 → 任何成员都能改创建者额度
4. 审批 `review` 方法在事务外查状态 → 并发审批可重复处理

### Phase 4 — RAG 检索改造（6h）

**做了什么：**
- 向量存储 `teamId` 过滤：个人文档 `teamId IS NULL`，团队文档 `teamId = ?`
- 检索服务按 teamId 隔离
- 文档列表支持按团队/个人筛选

**踩坑：** 向量存储的 metadata filter 与 PGvector 的兼容性需要验证。

---

## 三、代码审查发现的问题分类

审查采用双视角（GLM + DeepSeek），基于 5 个 ECC 技能 + trellis spec 全量扫描。

### 按严重级别

| 级别 | 数量 | 典型问题 |
|------|------|---------|
| 🔴 BLOCKER | 4 | 审批流未接入、重名校验缺失、双写无事务、额度校验缺失 |
| 🟠 HIGH | 7 | N+1 查询、行锁缺失、事务缺失、权限漏洞、审批并发、定时任务重叠 |
| 🟡 MEDIUM | 8 | 枚举返回值、批量更新、日志精度、包位置、配置方式 |
| 🔵 LOW | 6 | DDL 注释、Flyway 警告、Javadoc 标注 |

### 按问题类型

| 类型 | 数量 | 根因分析 |
|------|------|---------|
| 功能缺失 | 4 | 审批流/校验逻辑只设计了接口没实际接入 |
| 并发安全 | 4 | 乐观假设"不会并发操作"，缺行锁/DuplicateKey 兜底 |
| 性能 | 3 | 循环内单条查询/更新，列表无分页 |
| 校验缺失 | 2 | 额度范围/角色保护没做边界检查 |
| Spec 合规 | 9 | 时间类型/异常类型/包结构/日志级别/配置方式 |

---

## 四、修复阶段回顾

### 修复 Phase 1 — BLOCKER（4 项）

| 编号 | 问题 | 修复方案 |
|------|------|---------|
| B1 | 审批流未接入 | `TeamUploadStrategy` 重写：按角色分叉（管理员→PROCESSING，普通→PENDING_APPROVAL） |
| B2 | 团队名重名校验 | `createTeam` 加 `selectCount` + 唯一约束 |
| B3 | `setCreatorQuota` 双写无事务 | `txTemplate.executeWithoutResult` 包裹 team + member 更新 |
| B4 | 上传额度校验缺失 | `TeamUploadStrategy` 加按 user 汇总已用 MB 检查 |

**关键决策：** B1 和 B4 紧密耦合，一起改 `TeamUploadStrategy`，避免多次改同一文件。

### 修复 Phase 2 — 并发安全（4 项）

| 编号 | 问题 | 修复方案 |
|------|------|---------|
| H2 | `dissolveTeam` 无行锁 | `TeamMapper` 新增 `selectForUpdate`，事务内加锁再操作 |
| H3 | `addMember` 无事务/并发兜底 | 整体包入事务 + `catch DuplicateKeyException` 重查 |
| H4 | `setCreatorQuota` 无权限校验 | 加创建者身份校验 |
| H7 | 审批并发可重复处理 | 查询+状态检查移入事务内 |

### 修复 Phase 3 — 性能（3 项）

| 编号 | 问题 | 修复方案 |
|------|------|---------|
| H1 | N+1 查询（4 个方法） | 批量 `selectBatchIds` + `Collectors.toMap` 内存关联 |
| H5 | `rejectTimedOut` 循环内逐条事务 | 合并为单事务批量更新 |
| H6 | 列表无分页 | `listMembers`/`listPending`/`listMyApprovals` 接入 `PageRequest` + `PagedResult` |

### 修复 Phase 4+5 — 校验 + 代码质量（6 项）

- 额度范围 `@Max(10240)`
- CREATOR 角色保护（不能修改创建者角色）
- `dissolveTeam` 批量 `LambdaUpdateWrapper` 替代循环
- `approveAndTriggerEtl` 补充 warn 级别日志
- 枚举 `@JsonValue` 改返回 `name()`（API 友好）
- 新增 `ADMIN_CANNOT_REMOVE_ADMIN` 错误码

### 修复 Phase 6 — Spec 合规（9 项）

- `RagDocument` 时间列 `LocalDateTime` → `OffsetDateTime` + V10 迁移
- `SecurityUtils` 异常 `IllegalStateException` → 自定义 `AuthenticationException`
- `UploadStrategy` 接口移 `common.upload`，`PersonalUploadStrategy` 移 `rag.upload`
- 循环内 `log.info` → `log.debug`，循环外汇总 `log.info`
- V9 DDL TRUNCATE 加 WARNING 注释
- `TeamProperties` 改用 `@ConfigurationPropertiesScan`
- `DocumentOwnershipChecker` 加跨模块设计意图 Javadoc
- DDL COMMENT 补充 role/status 字段说明

---

## 五、核心教训

### 1. 功能完整性 > 代码结构

策略模式、工厂模式写得再漂亮，如果策略内部的**业务规则**（审批、额度、权限）没接入，就是 0。审查发现 4 个 BLOCKER 全是"接口有了但没接"。

**以后做法：** 每个策略类写完后，对照 PRD 逐条检查业务规则是否落地，不能只看接口签名。

### 2. 并发是后端最大的坑

9 个 HIGH 问题中 4 个是并发相关。乐观假设"不会并发操作"是后端最大的幻觉。

**以后做法：**
- 涉及状态变更的操作，默认加事务
- `check-then-act` 模式必须加行锁或唯一约束兜底
- 成员/审批这类"自然有并发"的场景，写代码时就要想"两个人同时点会怎样"

### 3. N+1 是最容易犯的性能问题

4 个方法里有 N+1 查询，开发时觉得"数据量小无所谓"，但这是技术债。

**以后做法：** 涉及关联查询的方法，写的时候就用批量查询，不要事后优化。

### 4. 审查标准要明确

这次用了 5 个 ECC 技能 + trellis spec 全量扫描，双视角审查。如果没有明确的标准，审查容易变成"看代码感觉不太好"。

**以后做法：** 审查前先列出检查清单（并发/事务/权限/性能/枚举/日志/异常），逐项过。

### 5. 编译通过 ≠ 运行正确

这个教训在 MEMORY.md 里已经有了，这次又验证了一次——审批流编译通过、214 测试全绿，但业务逻辑完全没接入。

**以后做法：** 每个功能模块完成后，写端到端测试场景（哪怕手动 curl），验证完整业务链路。

---

## 六、数据统计

| 指标 | 数值 |
|------|------|
| 开发 commit | 4（Phase 1-4） |
| 修复 commit | 5（Phase 1-6） |
| 修复文件数 | 20+ |
| 审查问题数 | 31（3B + 7H + 8M + 4L，实际修复含审查外发现约 35 项） |
| 测试数量 | 214（全程全绿） |
| 新增 Flyway 迁移 | V9 + V10 |
| 新增枚举 | 3（TeamStatus / TeamMemberRole / ApprovalStatus） |
| 新增错误码 | 10+ |

---

## 七、以后做类似功能的标准流程

1. **PRD → 审查 → 修订**（本次做得好）
2. **接口设计 → 列出业务规则清单**（本次缺了这步）
3. **逐条业务规则实现 + 自测**（不能只看接口签名）
4. **编译 + 测试 + 端到端手动验证**（不能只看测试全绿）
5. **提交前自查清单**（并发/事务/权限/性能/枚举/日志/异常）
6. **代码审查**（双视角 + 明确标准）
7. **修复 → 回归**（本次做得好）
