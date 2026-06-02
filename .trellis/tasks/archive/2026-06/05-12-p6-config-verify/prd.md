# P6: 配置 + 编译验证

## 目标
补全 YAML 配置，全局编译验证，确保无破坏性改动。

## 实现清单

### 1. application-dev.yml 配置
```yaml
app:
  etl:
    executor:
      io:
        core-pool-size: 4
        max-pool-size: 8
        queue-capacity: 50
        thread-name-prefix: "etl-io-"
        keep-alive-seconds: 60
      cpu:
        core-pool-size: 2
        max-pool-size: 4
        queue-capacity: 20
        thread-name-prefix: "etl-cpu-"
        keep-alive-seconds: 60
    fast-track:
      enabled: true
      max-doc-count: 10
      max-total-size: 5MB
```

### 2. 编译验证
- `mvn compile -q` 通过
- 无新增依赖
- 所有新增文件符合 directory-structure 规范

### 3. Git
- commit + push
- message: `feat(rag): concurrent ETL with fast-track BM25 routing`

## 验收
- [x] 配置项完整
- [x] 编译零错误
- [x] Git push 成功
