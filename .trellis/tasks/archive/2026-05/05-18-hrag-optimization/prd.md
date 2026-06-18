# H-RAG Pipeline 优化实施

**任务类型**: feature
**优先级**: P1
**分支**: rag-dev（基于现有）
**设计文档**: `.trellis/tasks/05-18-rag-pipeline-hrag-refinement/design.md`

## 背景

基于 H-RAG (arXiv:2605.00631) 论文，识别出两个可落地的优化项：
1. **O1**: Query Rewrite 缺少守卫规则 — 短事实型查询被 LLM 过度展开
2. **O3**: Parent 替换后无重排 — H-RAG 证明 parent-level rescoring 收益最大（+0.0197 nDCG@5）

O2 (Temperature) 已决定暂不实施，O4 (消融实验) 不在本任务范围内。

## 修复项

### Phase 1: O1 — Query Rewrite 守卫规则

**文件**: `RagConfig.java`
**改动**: 在 rewrite prompt 中增加守卫指令

```
IMPORTANT: If the query is already clear, specific, and standalone,
return it EXACTLY as is. Do NOT over-elaborate short factual queries.
```

**验证**:
- [ ] 编译通过
- [ ] 现有 RewriteQueryTransformer 相关测试不受影响
- [ ] git commit + push

### Phase 2: O3 — Parent-level Rescoring

**文件**: `ParentDocumentPostProcessor.java`
**改动**:
1. 新增 `resolveScore(Document)` 辅助方法（rerankScore > rrfScore > 0.5 优先级链）
2. 在 `process()` 子→父替换 + 去重后，按子块 max-score 聚合排序父文档
3. 零额外 API 调用、零额外 DB 查询

**算法**:
```
1. 构建 parentId → max(childScore) 映射
2. 按 parentScore 降序排列父文档
3. 拼接 non-child 文档（保持原序）
```

**测试文件**: `ParentDocumentPostProcessorTest.java`
**新增测试**:
- [ ] resolveScore() 三种场景：有 rerankScore / 仅有 rrfScore / 无分数
- [ ] rescore 排序逻辑：父文档按子块最高分降序
- [ ] 混合场景：父文档 + non-child 文档

**验证**:
- [ ] 编译通过
- [ ] 新增测试全绿
- [ ] 现有 ParentDocumentPostProcessorTest 不受影响
- [ ] git commit + push

## 约束

- 不改变 Pipeline 链路结构
- 不引入新的外部依赖
- 不引入厂商特定的 ChatOptions（O2 暂不实施的原因）
- 每个 Phase 完成后 git commit + push

## 文件改动清单

| 文件 | 改动类型 | Phase |
|------|----------|-------|
| `RagConfig.java` | 修改 rewrite prompt | P1 |
| `ParentDocumentPostProcessor.java` | 增加 resolveScore + rescore 排序 | P2 |
| `ParentDocumentPostProcessorTest.java` | 新增测试 | P2 |

**总改动量**: ~35 行代码 + ~40 行测试
