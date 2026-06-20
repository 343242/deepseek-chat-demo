# user 模块 review 剩余项修复

## Goal
落地 `docs/code-review/2026-06-20-user-module-review.md` 中 Top3 之外的 MEDIUM/LOW 项。详细执行见 `implement.md`。

## Requirements
- 修复 M1/M2/M4/M5/M6/M7/M8/M11 与 L2/L3/L4/L6/L7/L9/L10/L11/L12（见 implement.md 清单）。
- 对需产品/部署决策或破坏性变更的项（M9/M10/L1/L5/L13/L14/L15）明确不改并文档化理由。

## Acceptance Criteria
- [ ] implement.md 清单全部完成或显式标注不改
- [ ] `./mvnw test` 全绿
- [ ] commit + push + archive

## Notes
- 改前 impact：register=LOW（仅 AuthController 调用）。DTO/枚举为叶子节点。
