# PRD: RAG ETL 并发改造 + 智能路由

## 背景

当前 ETL Pipeline 是单线程同步执行：Extract → Transform → Load 串行。单文档上传后用户需等待全流程完成才能得到响应。批量上传场景下更慢。

同时，对于小规模文档（<10个、<5MB），向量化并非立即必要——BM25 全文检索已经可以提供可用结果。

## 目标

### 1. 多线程并发 ETL

- 文档解析（Extract）、分块（Transform）、向量化（Load）支持**批量并行**
- 线程池参数外部化配置（`application.yml`），支持动态调整
- 区分 IO 密集型线程池（文件读取/MinIO/API调用）和 CPU 密集型线程池（分块/文本处理）
- 线程安全：共享状态（RagDocument 状态、ChatMemory）使用编程式事务 + 并发安全集合

### 2. 智能路由（小文档快速通道）

- **判定条件**：文档数量 ≤ 10 且总大小 ≤ 5MB 时触发"快速通道"
- **快速通道行为**：
  1. 解析后直接将原文写入 BM25 全文检索表（`vector_store.content` + `content_tsv`）
  2. 立即返回成功（用户可立即通过 BM25 检索到内容）
  3. 后续的切分 + 向量化改为**异步执行**（提交到线程池）
- **普通通道行为**：保持现有的同步 ETL 流程不变

### 3. 设计约束

- **OCP**：新增路由逻辑通过策略模式注入，不修改现有 ETLPipelineServiceImpl
- **策略模式**：`EtlRouteStrategy` 接口 + `FastTrackStrategy` / `StandardStrategy` 实现
- **工厂模式**：`EtlRouteStrategyFactory` 根据文档特征选择策略
- **可测试性**：策略判定逻辑和线程池配置均可独立单元测试

## 架构设计

### 新增文件清单

```
com.demo.chat.rag/
├── config/
│   └── EtlExecutorProperties.java          # 线程池配置属性
├── etl/
│   ├── EtlExecutorConfig.java              # IO + CPU 线程池 Bean 定义
│   ├── EtlRouteStrategy.java               # 路由策略接口
│   ├── FastTrackStrategy.java              # 快速通道策略
│   ├── StandardStrategy.java               # 标准同步策略
│   └── EtlRouteStrategyFactory.java        # 策略工厂
├── service/
│   ├── EtlDispatchService.java             # 新增：ETL 调度服务接口
│   └── impl/
│       └── EtlDispatchServiceImpl.java     # 路由 + 并发编排
```

### 线程池配置

```yaml
app:
  etl:
    executor:
      io:
        core-pool-size: 4
        max-pool-size: 8
        queue-capacity: 50
        thread-name-prefix: "etl-io-"
      cpu:
        core-pool-size: 2
        max-pool-size: 4
        queue-capacity: 20
        thread-name-prefix: "etl-cpu-"
    fast-track:
      enabled: true
      max-doc-count: 10
      max-total-size: 5MB
```

### 线程池分工

| 阶段 | 线程池 | 原因 |
|------|--------|------|
| Extract（MinIO 下载 + 文件解析） | IO 池 | 网络IO + 文件IO |
| Transform（文本分块） | CPU 池 | 纯文本计算 |
| Load（Embedding API + PGvector 写入） | IO 池 | 网络IO + DB写入 |
| 快速通道 BM25 写入 | IO 池 | DB写入 |

### ETL 调度流程

```
DocumentApplicationService.upload()
    │
    ▼
EtlDispatchService.dispatch(documents)
    │
    ├── EtlRouteStrategyFactory.resolve(documents)
    │   ├── docCount <= 10 && totalSize <= 5MB → FastTrackStrategy
    │   └── else → StandardStrategy
    │
    ├── FastTrackStrategy.execute():
    │   ├── IO池: 并行 Extract 所有文档
    │   ├── 同步: 原文写入 content + content_tsv（BM25 可用）
    │   ├── 同步: 返回成功
    │   └── 异步提交: IO池 + CPU池完成后续 Transform → Load
    │
    └── StandardStrategy.execute():
        ├── IO池: 并行 Extract
        ├── CPU池: 并行 Transform
        └── IO池: 并行 Load
```

### 线程安全保障

| 共享资源 | 安全措施 |
|---------|---------|
| RagDocument 状态更新 | TransactionTemplate 独立事务，每次更新原子操作 |
| VectorStore.add() | Spring AI PgVectorStore 内部已同步 |
| JdbcTemplate BM25 写入 | 每个文档独立 INSERT，无竞态 |
| 批量上传文件列表 | Collections.unmodifiableList / defensive copy |

## 子任务

| ID | 标题 | 范围 |
|----|------|------|
| P1 | 线程池基础设施 | EtlExecutorProperties + EtlExecutorConfig（IO/CPU 双线程池） |
| P2 | ETL 策略模式 | EtlRouteStrategy 接口 + FastTrackStrategy + StandardStrategy + Factory |
| P3 | 并发 ETL 编排 | EtlDispatchService 接口 + 实现（批量并行 Extract/Transform/Load） |
| P4 | 快速通道 BM25 | 小文档原文直接写入 content_tsv，异步后续向量化 |
| P5 | 集成改造 | DocumentApplicationService 接入 EtlDispatchService，批量上传端点 |
| P6 | 配置 + 测试 | application.yml 配置项，编译验证 |

## 验收标准

1. 单文档上传走标准通道，行为与改造前一致（回归）
2. 批量上传（>10个或>5MB）走标准并发通道，各阶段并行执行
3. 小批量上传（≤10个且≤5MB）走快速通道，解析后立即可 BM25 检索
4. 快速通道的向量化异步完成，不阻塞响应
5. 线程池参数通过 YAML 配置，不硬编码
6. 编译通过，无破坏性改动
