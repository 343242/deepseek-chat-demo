-- V31__drop_llm_config.sql — 删除 BYOK 模型配置表
-- BYOK 功能整体移除（design llm-client-stateless v3.0 决策 1/决策 8）：
-- 表随功能删除，无数据迁移（实施前数据库整体清空重置，无存量数据）。
-- Flyway 追加式历史不改写 V16 建表文件，删除意图以本迁移表达。
DROP TABLE IF EXISTS llm_config;
