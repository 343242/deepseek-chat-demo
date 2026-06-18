# 代码审查修复 — 父任务

> 审查报告：`docs/TEAM-CODE-REVIEW.md`
> 审查者：GLM + DeepSeek + Trellis Spec 补充
> 总计：4 BLOCKER / 9 HIGH / 12 MEDIUM / 6 LOW

## 总目标

根据双视角代码审查报告修复团队模块所有问题。

## 实施阶段

| Phase | 子任务 | 问题 | 预估 |
|-------|--------|------|------|
| 1 | BLOCKER 修复 | B1~B4 | 2h |
| 2 | 并发安全修复 | H2, H3, H4, H7 | 1.5h |
| 3 | 性能修复 | H1, H5, H6 | 2h |
| 4 | 校验补全 | H8, H9 | 0.5h |
| 5 | 代码质量 | M1, M2/M10, M3, M4, M5 | 2h |
| 6 | Spec 合规 | M6, M8, M9, M11, M12, L2, L3, L5, L6 | 1.5h |

## 分支

`rag-dev`

## 约束

- 每个子任务完成后编译验证 + 214 测试回归
- 每个子任务完成后 git commit + push
- 遵循 trellis spec
- LOW 级别问题（L1, L4）不在本次修复范围

## 子任务依赖关系

```
Phase 1 (BLOCKER) ← 无前置
Phase 2 (并发)    ← Phase 1
Phase 3 (性能)    ← 无前置（可与 Phase 2 并行）
Phase 4 (校验)    ← Phase 1（B4 额度校验需 Phase 1 的 B1 审批逻辑就绪）
Phase 5 (质量)    ← Phase 1（M1 模板方法重构需 B1 审批逻辑就绪）
Phase 6 (合规)    ← Phase 5（M9 PersonalUploadStrategy 位置需在 M1 重构后调整）
```
