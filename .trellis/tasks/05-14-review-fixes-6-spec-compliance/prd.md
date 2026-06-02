# Phase 6: Spec 合规

> 审查报告：M6, M8, M9, M11, M12, L2, L3, L5, L6
> 预估：1.5h
> 前置：Phase 5（M9 需 M1 重构后调整）

## 目标

统一时间类型、修复异常处理、调整包结构、日志规范。

## 子步骤

### 6.1 M6 — rag_document 时间列统一 TIMESTAMPTZ
- **操作：**
  1. 新增 `V10__rag_document_timestamptz.sql`
     ```sql
     ALTER TABLE rag_document ALTER COLUMN create_time TYPE TIMESTAMPTZ USING create_time AT TIME ZONE 'Asia/Shanghai';
     ALTER TABLE rag_document ALTER COLUMN update_time TYPE TIMESTAMPTZ USING update_time AT TIME ZONE 'Asia/Shanghai';
     ```
  2. `RagDocument` 实体：`LocalDateTime createTime/updateTime` → `OffsetDateTime`
  3. `PersonalUploadStrategy`：`LocalDateTime.now()` → `OffsetDateTime.now()`
  4. `AbstractUploadStrategy`（Phase 5 创建的）：确保用 `OffsetDateTime`
  5. 所有引用 `RagDocument.getCreateTime()` 的地方同步修改
  6. 同步检查 V1 中其他 `TIMESTAMP` 表是否需要一并迁移（sys_user, token_usage 等）
- **验证：** 编译通过 + 214 测试回归

### 6.2 M8 — SecurityUtils 异常类型修复
- **文件：** `SecurityUtils.java` + `GlobalExceptionHandler.java`
- **操作：**
  1. `getCurrentUserId()` 中 `IllegalStateException` → 抛自定义异常或 `AuthenticationException`
  2. 在 `GlobalExceptionHandler` 中注册 401 映射（如尚未处理）
- **验证：** 编译通过 + 214 测试回归

### 6.3 M9 — PersonalUploadStrategy 包位置调整
- **前置：** Phase 5 M1 重构后
- **操作：**
  1. `UploadStrategy` 接口移到 `common/upload/`
  2. `AbstractUploadStrategy` 移到 `common/upload/`
  3. `PersonalUploadStrategy` 移到 `rag/upload/`
  4. `TeamUploadStrategy` + `UploadStrategyFactory` 保留在 `team/upload/`
  5. 更新所有 import
- **验证：** 编译通过 + 214 测试回归

### 6.4 M11 — 循环内日志降级
- **文件：** `PersonalUploadStrategy.uploadBatch()`（Phase 5 后可能在 `AbstractUploadStrategy`）
- **操作：** 循环内 `log.info` → `log.debug`，循环外汇总 `log.info`
- **验证：** 编译通过

### 6.5 M12 — createdAt/updatedAt 统一
- **操作：** 去掉 Java 层手动设置 createdAt（依赖 DB DEFAULT），保留 updatedAt 手动设置（update 时需要）
- **验证：** 编译通过

### 6.6 L2 — Flyway V9 TRUNCATE 加注释
- **文件：** `V9__add_team.sql`
- **操作：** 在 `TRUNCATE TABLE vector_store;` 前加 `-- WARNING: 开发阶段专用，生产环境需删除此行`
- **验证：** 无（仅注释）

### 6.7 L3 — TeamProperties 改用 @ConfigurationPropertiesScan
- **操作：**
  1. `TeamProperties` 去掉 `@Component`，保留 `@ConfigurationProperties`
  2. 启动类或配置类上加 `@ConfigurationPropertiesScan("com.demo.chat.team.config")`
- **验证：** 编译通过 + 启动验证

### 6.8 L5 — DocumentOwnershipChecker 位置标注
- **操作：** 暂不移包（风险大收益低），在类 Javadoc 中标注其跨模块性质和设计意图
- **验证：** 无

### 6.9 L6 — V9 DDL COMMENT 完善
- **文件：** `V9__add_team.sql`
- **操作：** 补充 `COMMENT ON COLUMN team_member.role IS '10=MEMBER(默认) 20=ADMIN 30=CREATOR';`
- **注意：** Flyway 已执行过，需新增 V10 或在 V10 中追加 COMMENT（合并到 6.1 的 V10）
- **验证：** 编译通过

## 完成标准

- [ ] 编译通过
- [ ] 214 测试全部通过
- [ ] git commit + push
- [ ] 审查报告中所有问题状态更新为已修复
