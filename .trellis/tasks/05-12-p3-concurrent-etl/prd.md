# P3: 并发 ETL 编排

## 目标
StandardStrategy 使用双线程池并发执行 Extract/Transform/Load，提升批量文档处理速度。

## 实现清单

### 1. StandardStrategy 并发编排
- Extract 阶段：所有文档的 extract 提交到 IO 池，`CompletableFuture.allOf()` 等待
- Transform 阶段：所有文档的 transform 提交到 CPU 池，allOf 等待
- Load 阶段：所有文档的 load 提交到 IO 池，allOf 等待
- 每个阶段独立处理异常，不因单个文档失败影响其他文档

### 2. 异常处理
- 单文档失败：标记该文档 FAILED，记录错误信息，不影响其他文档
- 全部失败：返回所有失败结果
- 线程池拒绝（队列满）：CALLER_RUNS 策略降级为调用者线程执行

### 3. 状态管理
- 每个文档独立 TransactionTemplate 更新状态
- 并发安全：无共享可变状态，每个文档的状态更新是独立事务
- 批量进度日志：阶段完成后输出已处理/总数

### 4. 单文档场景
- 单文档仍走 StandardStrategy
- 并发开销可忽略（单任务直接执行）
- 行为与改造前完全一致（回归保障）

## 验收
- [x] 批量文档并行处理
- [x] 单文档行为不变
- [x] 单文档失败不影响其他文档
- [x] 状态更新线程安全
