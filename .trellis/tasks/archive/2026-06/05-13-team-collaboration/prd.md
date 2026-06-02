# 团队协作功能 — 父任务

> 设计文档：`docs/TEAM-FEATURE-PRD.md` v1.2
> 架构审查：`docs/reviews/team-prd-arch-review-main.md` + `team-prd-arch-review-deepseek.md`
> 规范合规：`docs/reviews/team-prd-trellis-compliance.md`

## 总目标

为 chat-demo 引入团队协作功能，支持用户创建/加入团队，团队共享文档空间，成员上传审批机制。

## 实施阶段

| Phase | 子任务 | 预估 | 前置条件 |
|-------|--------|------|---------|
| 1 | 共享组件 + EtlCandidate 改造 | 4h | 无 |
| 2 | 策略模式 + 回归测试 | 6h | Phase 1 |
| 3 | 团队模块全部功能 | 18h | Phase 2 |
| 4 | RAG 检索改造 | 6h | Phase 3 |

## 分支

`rag-dev`

## 约束

- 严格遵守 trellis spec（`.trellis/spec/backend/`）
- 编程式事务 `TransactionTemplate`
- 枚举 + `@EnumValue` 映射状态/角色字段
- 策略模式，不加 if/else 分支
- 每阶段完成后 commit + push
