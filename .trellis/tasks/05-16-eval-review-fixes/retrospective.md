# RAG 评估系统复盘

> 从零设计 → 五阶段实现 → 代码审查 → 四阶段修复的完整工程闭环

---

## 时间线

| 阶段 | 产出 |
|------|------|
| 设计 | design.md — 六阶段 Pipeline 对应的评估指标体系 |
| Phase 1 | 基础设施：Properties + Config + Flyway V11 + LlmJudge + yml |
| Phase 2 | 数据集管理：实体/Repository/Generator/Exporter/Controller |
| Phase 3 | 检索评估：RetrievalMetricsCalculator + PipelineInstrumenter + Runner |
| Phase 4 | 生成评估：四个 Scorer + GenerationMetricsCalculator + REST API |
| Phase 5 | 集成验证 |
| 审查 | 三轮审查打磨，产出 6 P0 + 9 P1 + 7 P2 |
| 修复 Phase 1 | judgeModel 路由 + try-with-resources |
| 修复 Phase 2 | 四实体改 record + JsonExtractor（-321 行） |
| 修复 Phase 3 | @PreAuthorize + status 枚举 + V12 迁移 + ExecutionService + @Transactional |
| 修复 Phase 4 | 并发优化 + BeanUtils + 异常处理 + 32 单元测试 |

**总计**: 34 文件, 3386 行新增, 16 个 commit

---

## 教训一：设计文档不能替代代码审查

设计阶段产出了完整的 design.md，指标体系（Recall@K, Precision@K, MRR, NDCG, Faithfulness 等）规划得很清楚。但设计文档关注的是 **做什么**，代码审查关注的是 **怎么做**。

**遗漏的例子**：
- design.md 写了「judgeModel 可配置」，代码里 `LlmJudgeImpl` 自己 `ChatClient.builder().build()` 完全绕过了项目的 Provider 路由 → **judgeModel 配了个寂寞**
- 设计里没有明确实体用 class 还是 record → 实现时用了 mutable class + setter，与项目 30+ 处 record 的惯例冲突

**教训**: 设计文档定方向，代码审查定质量。两者不可互替。

---

## 教训二：先写测试，还是先写审查？

评估模块从零到 Phase 5 全程 **零测试**。审查报告发现了 22 个问题，其中 6 个是 P0 Blocker。

如果一开始就写测试：
- `LlmJudgeImpl` 的 judgeModel 路由问题会在第一个集成测试就暴露
- `DatasetRepository` 的 PreparedStatement 泄漏在高频调用下会连接池耗尽
- `EvaluationRunStatus` 的字符串魔法值在测试中会立刻显得脆弱

**教训**: 「先跑通再补测试」的债务，最终都以更高成本偿还。RetrievalMetricsCalculator 是纯计算类，ROI 最高的测试本应在 Phase 3 就写。

---

## 教训三：项目惯例 > 个人偏好

审查报告最密集的一类问题是「与项目既有模式不一致」：

| 审查发现 | 项目惯例 | 评估模块实际 |
|---------|---------|------------|
| 实体类型 | record（30+ 处） | mutable class + setter |
| 状态字段 | @EnumValue + @JsonValue 枚举 | String 硬编码 |
| Controller 安全 | @PreAuthorize(hasAuthority) | 无权限控制 |
| 模型获取 | ChatClientRegistry.get(modelId) | ChatClient.builder().build() |
| JSON 提取 | 各模块自己实现 | 5 处重复的 extractJson |
| 资源管理 | try-with-resources | PreparedStatement 裸用 |

**教训**: 在既有项目中新增模块，第一件事是读现有代码的 **风格**，不是设计文档的 **规范**。代码风格是活文档，spec 是死文档。

---

## 教训四：审查报告的「精准对齐」值多少钱？

审查报告经历了三轮打磨：
1. 第一轮：通用审查，产出 4 P0 + 5 P1 + 4 P2
2. 第二轮：对照 README.md 技术栈重新校准，发现 MyBatis-Plus 不支持 record 是错误判断（实际 3.5.10+ 支持）
3. 第三轮：对照 spec 增量审查，新增 2 P0 + 4 P1 + 3 P2

第三轮对照 spec 的收获最大——spec 里写了「Provider 路由统一」，但代码里完全没用。这不是代码质量问题，是 **需求符合度** 问题。

**教训**: 审查标准 ≠ 代码规范。审查标准 = spec 需求 × 项目惯例 × 代码规范，三者缺一不可。

---

## 教训五：子代理分工的正确姿势

修复阶段用了子代理并行：

| 子代理 | 任务 | 耗时 | 结果 |
|-------|------|------|------|
| Phase 2 | record 转换 + JsonExtractor | ~5min | 成功但忘了 commit |
| Phase 4 | 并发 + 测试 + 异常 | ~5min | 成功 |

**踩的坑**：
- 子代理做了额外工作（顺手把 Phase 3.1 枚举和 @PreAuthorize 也做了），导致主线程的改动和子代理的改动部分重叠 → 需要仔细 diff 才能发现
- 子代理忘了 commit → 主线程需要补 commit
- 子代理超时不设限是对的（DeepSeek V4 Pro 5-6 分钟），但需要明确告知「完成后必须 git commit」

**教训**: 子代理 task 描述要 **精确到 commit message**，不能只说「完成后 commit」。明确写出：`git add -A && git commit -m "xxx"` 才可靠。

---

## 教训六：从 bug 模式看编码习惯

6 个 P0 Blocker 的根因分析：

| Bug | 根因 | 通用模式 |
|-----|------|---------|
| judgeModel 未生效 | 不理解框架路由机制，自己 build 绕过 | **不理解就用框架的默认方式，别自作主张** |
| PreparedStatement 泄漏 | JdbcTemplate ConnectionCallback 里忘记 try-with-resources | **任何 Closeable 都用 try-with-resources，无例外** |
| 实体用 class | 没看项目现有代码风格 | **新模块先读旧代码 10 分钟，省修复 2 小时** |
| status 字符串硬编码 | 图省事 | **状态字段一律枚举，字符串只存在于 DB 层** |
| extractJson 重复 5 处 | 复制粘贴而非抽象 | **第二次复制时必须抽取** |
| Controller 无权限 | 忘了加 @PreAuthorize | **每个 Controller 写完检查安全注解** |

**最核心的一句话**: 这些 bug 不是「粗心」，是 **编码习惯**。习惯决定你写出的代码的 bug 密度。

---

## 如果重来，我会怎么做

1. **Phase 0**: 先花 30 分钟通读项目现有代码（Controller 看权限注解、实体看 class/record 选择、Repository 看 RowMapper 风格）
2. **每个 Phase 同时写测试**: 不是跑通再补，而是红-绿-重构
3. **用 spec 做验收清单**: 每个 Phase 完成后对照 spec 检查需求符合度
4. **实体一开始就用 record**: 项目 30+ 处 record 是最强的信号
5. **框架路由别绕过**: ChatClientRegistry 已有，不需要自己 build

---

## 最终状态

```
eval-rag-dev 分支
├── 评估模块: 25+ Java 源文件
├── 单元测试: 32 个（RetrievalMetricsCalculator + JsonExtractor + 枚举）
├── Flyway: V11（建表）+ V12（CHECK 约束 + 权限）
├── 6 P0 → 0（全部修复）
├── 9 P1 → 7（P1-1 Runner 拆分 + P1-8 Properties 推到下迭代）
└── 7 P2 → 5（核心项已修复）
```
