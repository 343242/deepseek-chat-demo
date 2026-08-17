# PRD：后端时间类型与时区统一

## 需求来源

设计文档：[`docs/design/backend-time-unification.md`](../../../docs/design/backend-time-unification.md)（状态：实现就绪 v2）

## 目标

全栈时间表达统一为单一契约：存储用绝对时间点（`TIMESTAMPTZ`），API 输出固定为 `yyyy-MM-dd HH:mm:ss`、展示时区可配置（默认东八区 `Asia/Shanghai`）、无偏移量、无多格式分支。

## 验收标准（来自设计文档 §12）

1. 代码中不存在 `LocalDateTime` 持久化字段或 API 入参/出参类型（`DateTimeTools` 展示文案除外）。`JacksonTimeConfig` 中不注册 `LocalDateTime` 序列化器。
2. 时间格式化与解析只有一份实现（`TimeCodec`）；Jackson 序列化器/反序列化器与 Spring `Formatter` 全部委托它。
3. 不存在手动 `toString` 时间转换胶水层。
4. 数据层通过单一新增迁移 `V25` 统一 V1 残留的 `TIMESTAMP` 列（`system_prompt`/`model_params`/`token_usage`）为 `TIMESTAMPTZ`；遵守 Flyway 不可变契约。
5. 改 `APP_TIME_ZONE` 单一环境变量，JVM 时区与 API 输出展示时区同步变化，代码零改动。
6. 16 处离群 `new ObjectMapper()` 经审计确认不向 API 边界发射裸 java.time。

## 复杂度

复杂任务。设计文档（`docs/design/backend-time-unification.md`）即本任务的 `design.md` 与 `implement.md`——§11「实现改动范围」给出逐文件动作清单，§10「测试设计」给出测试矩阵。
