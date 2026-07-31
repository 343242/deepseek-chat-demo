# Implementation Plan — ECR DB Migration (V21 Schema)

## Reference

- 设计文档：`docs/design/entity-centric-retrieval.md` §13（行 1497-1575）为完整迁移脚本参考
- 数据模型：同文档 §3.1（行 106-178）
- 数据库规范：`.trellis/spec/backend/database-guidelines.md`（Flyway 命名、索引前缀、TIMESTAMPTZ 约定）
- 项目 HNSW 惯例：`src/main/resources/db/migration/V13__hnsw_tuning.sql`

## Checklist

### Phase 1：创建迁移文件

- [ ] 1. 创建 `src/main/resources/db/migration/V21__entity_centric_index.sql`
- [ ] 1.1 文件头部注释块（遵循 V13/V19 风格：双横线分隔 + 任务来源 + 概要描述）
- [ ] 1.2 `rag_entity` 表 DDL（14 列，严格按 §13 行 1503-1520）
- [ ] 1.3 `uk_entity_norm_user_team` 表达式唯一索引（§13 行 1522）
- [ ] 1.4 `idx_entity_embedding` HNSW 索引（`m=32, ef_construction=128`，§13 行 1523-1524）
- [ ] 1.5 `idx_entity_user_team` 隔离索引（§13 行 1525）
- [ ] 1.6 `idx_entity_name_norm` 查找索引（§13 行 1526）
- [ ] 1.7 `rag_chunk_entity` 表 DDL（复合 PK，§13 行 1529-1533）
- [ ] 1.8 `idx_ce_entity` + `idx_ce_chunk`（§13 行 1535-1536）
- [ ] 1.9 `rag_event` 表 DDL（含 `chunk_id UNIQUE`，§13 行 1539-1548）
- [ ] 1.10 `idx_event_embedding` HNSW 索引 + `idx_event_user_team`（§13 行 1549-1551）
- [ ] 1.11 `rag_entity_cooccurrence` 表 DDL（§13 行 1554-1562）
- [ ] 1.12 `uk_cocur_scope_pair` 表达式唯一索引（含 LEAST/GREATEST，§13 行 1563-1564）
- [ ] 1.13 `idx_cocur_pair` + `idx_cocur_user`（§13 行 1565-1566）
- [ ] 1.14 `v_entity_neighbors` 视图（UNION ALL，§13 行 1569-1574）
- [ ] 1.15 Down SQL 注释块（`DROP VIEW v_entity_neighbors; DROP TABLE rag_entity_cooccurrence; DROP TABLE rag_chunk_entity; DROP TABLE rag_event; DROP TABLE rag_entity;`，注意依赖顺序）

### Phase 2：验证

- [ ] 2.1 在本地 PostgreSQL 实例执行 `flyway:migrate`，确认 V21 成功
- [ ] 2.2 `\d rag_entity` — 确认 14 列 + 4 索引
- [ ] 2.3 `\d rag_chunk_entity` — 确认复合 PK + 2 索引
- [ ] 2.4 `\d rag_event` — 确认 7 列 + chunk_id UNIQUE + 2 索引
- [ ] 2.5 `\d rag_entity_cooccurrence` — 确认 6 列 + 表达式唯一索引 + 2 索引
- [ ] 2.6 `\di+ idx_entity_embedding` — 确认 HNSW `m=32, ef_construction=128`
- [ ] 2.7 表达式唯一索引功能测试：
  ```sql
  INSERT INTO rag_entity (name_norm, user_id) VALUES ('dup_test', 1);
  INSERT INTO rag_entity (name_norm, user_id) VALUES ('dup_test', 1);  -- 应报 unique violation
  ```
- [ ] 2.8 共现无向去重测试：
  ```sql
  INSERT INTO rag_entity_cooccurrence (entity_a, entity_b, co_count, user_id)
  VALUES (1, 2, 1, 1);
  INSERT INTO rag_entity_cooccurrence (entity_a, entity_b, co_count, user_id)
  VALUES (2, 1, 1, 1);  -- 应报 unique violation
  ```
- [ ] 2.9 Down SQL 手动验证：执行 DROP 语句后 `\dt` 确认四表已删除

### Phase 3：字节校验

- [ ] 3.1 将 `V21__entity_centric_index.sql` 与设计文档 §13（行 1499-1575）逐行对比，确认 DDL 完全一致（除可能的空格/注释微调外无实质差异）

## Validation Commands

```bash
# Flyway 迁移（在项目根目录执行）
./mvnw flyway:migrate -Dflyway.url=jdbc:postgresql://localhost:5432/smart_rag

# 或者通过 Spring Boot 启动自动迁移（项目 Flyway 配置为 spring 启动时自动执行）
./mvnw spring-boot:run

# 确认 Flyway 状态
./mvnw flyway:info
# 预期：V21 状态为 "Success"
```

```sql
-- PostgreSQL psql 验证
\d rag_entity
\d rag_chunk_entity
\d rag_event
\d rag_entity_cooccurrence
\d+ v_entity_neighbors

-- 索引参数
\di+ idx_entity_embedding
\di+ idx_event_embedding

-- 表达式索引确认
SELECT indexname, indexdef FROM pg_indexes
WHERE tablename IN ('rag_entity', 'rag_entity_cooccurrence')
  AND indexname LIKE 'uk_%';
```

## Review Gates

- **Gate 1（自检）**：§13 字节校验通过，DDL 与设计文档无实质差异
- **Gate 2（迁移验证）**：`flyway:migrate` 在干净 DB 成功
- **Gate 3（索引验证）**：所有索引存在、HNSW 参数正确、表达式唯一索引功能测试通过

## Rollback Points

- V21 为纯新增迁移，回滚 = 手动执行 Down SQL（DROP VIEW + DROP TABLE 四表）
- Flyway 不支持 undo（项目未启用 `flyway.undo`），回滚后 `flyway_schema_history` 仍记录 V21
- 若回滚后需重新应用，需先手动删表再 `flyway:repair`，否则 Flyway 认为已应用

## Spec References

- `.trellis/spec/backend/database-guidelines.md`：Flyway 命名、索引前缀、TIMESTAMPTZ
- `.trellis/spec/backend/quality-guidelines.md`：DDL 代码质量
- `.trellis/spec/guides/cross-layer-thinking-guide.md`：确认不侵入 vector_store（Spring AI 契约层）
