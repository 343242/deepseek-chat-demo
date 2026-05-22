# P2: ETL 路由策略模式

## 目标
通过策略模式实现 ETL 路由：小文档走快速通道（BM25先行+异步向量化），大文档走标准通道。

## 实现清单

### 1. EtlRouteStrategy 接口
- 路径: `etl.rag.com.smart.rag.EtlRouteStrategy`
- 方法:
  - `boolean shouldApply(List<EtlCandidate> candidates)` — 判断是否使用该策略
  - `List<EtlResult> execute(List<EtlCandidate> candidates)` — 执行 ETL
- EtlCandidate: record(documentId, bucket, objectKey, fileName, mimeType, fileSize)
- EtlResult: record(documentId, status, chunkCount, errorMessage)

### 2. FastTrackStrategy
- 路径: `etl.rag.com.smart.rag.FastTrackStrategy`
- shouldApply: docCount ≤ maxDocCount && totalSize ≤ maxTotalSize
- execute:
  1. IO池并行 Extract 所有文档
  2. 同步：原文直接 INSERT 到 vector_store（content + content_tsv），BM25 立即可用
  3. 立即返回 status=COMPLETED（BM25 可检索）
  4. 异步提交后续 Transform + Load（CPU池分块 → IO池向量化）

### 3. StandardStrategy
- 路径: `etl.rag.com.smart.rag.StandardStrategy`
- shouldApply: 总是 true（兜底策略）
- execute:
  1. IO池并行 Extract
  2. CPU池并行 Transform
  3. IO池并行 Load
  4. 同步等待全部完成，返回结果

### 4. EtlRouteStrategyFactory
- 路径: `etl.rag.com.smart.rag.EtlRouteStrategyFactory`
- 构造器注入 `List<EtlRouteStrategy>`
- `resolve(candidates)` → 遍历策略，第一个 shouldApply=true 的胜出
- OCP: 新增策略只需新增实现类，不改 Factory

### 5. 配置
```yaml
app:
  etl:
    fast-track:
      enabled: true
      max-doc-count: 10
      max-total-size: 5MB
```

## 验收
- [x] 策略接口 + 两个实现 + 工厂
- [x] FastTrackStrategy 判定逻辑可测试
- [x] 新增策略不改现有代码
