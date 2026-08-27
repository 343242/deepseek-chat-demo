# PDF 图片提取验收 3/7 执行手册（运行期验收）

> 对应 [pdf-image-extraction.md](./pdf-image-extraction.md) §11 验收 3（图片任务最终一致/崩溃恢复）
> 与验收 7（后台满载不影响前台）。
> **已自动化部分**：验收 3 的崩溃重投机制（PEL 回收 + 无 dedupKey 语义 + 严重-2 对照）
> 已固化为 `ImageExtractCrashRecoveryTest`（Testcontainers，CI 随 `mvnw test` 执行）；
> 验收 7 与验收 3 的端到端形态（真实 kill -9、行级终态、双路径）需要部署环境执行，按本手册操作。

---

## 0. 前置条件

| 依赖 | 说明 |
|------|------|
| 部署环境 | PG + Redis + MinIO + 应用（参考 `.cnb.yml` 制品或本地 `docker compose` 三件套：smart-rag-db / smart-rag-redis / smart-rag-minio） |
| Embedding 服务 | ETL transform/load 需要 embedding API（FastTrack 的 BM25 段不需要，但验收 7 的"检索可见"需要） |
| 测试文档集 | ≥10 份 PDF，必须包含：12MB 级截图密集手册（验收 1 口径）、≤5MB 小 PDF（FastTrack 路径）、>5MB（Standard 路径） |
| 观测面 | Actuator metrics（`/actuator/prometheus`）或等价采集（GC、rag.image.*、rag.document.parse.seconds） |

关键配置（默认值即可跑通，列出以便调整）：

```yaml
app.document:
  odl-threads: 4              # 前台逐页并行度（按部署核数 §7 中-4）
  odl-image-concurrency: 1    # 后台图片消费并发（验收 7 的"满载"压力源）
  odl-image-max-per-doc: 500
app.etl.image.consumer:
  invisible-duration: 30m
```

---

## 1. 验收 3：图片任务最终一致 + 崩溃恢复（双路径执行）

### 1.1 基线：全部行到达终态

1. 上传 ≤5MB PDF（走 FastTrack）与 >5MB PDF（走 Standard）各若干；
2. 观察 `rag.image.pending_total` 归零、`rag.image.consume_seconds` 有样本；
3. DB 断言：

```sql
SELECT document_id, status, count(*) FROM document_image GROUP BY 1,2 ORDER BY 1;
-- 期望：仅 UPLOADED / SKIPPED（max-bytes-exceeded 等结构性原因），无 PENDING/FAILED
SELECT count(*) FROM document_image WHERE status='FAILED';  -- 期望 0
```

4. MinIO 断言：`images/{documentId}/` 前缀对象数 == 该文档 UPLOADED 行数；
5. 抽样 20 图人工核对页码/序号/内容（验收 5 双射的抽样部分）。

### 1.2 崩溃恢复（真实 kill -9）

> 语义已被 `ImageExtractCrashRecoveryTest` 覆盖（PEL 回收 + dedupKey 语义）；
> 本节验证端到端形态：**消费中途被杀 → 行停留 PENDING → 重启后重新消费完成**。

1. 上传一份截图密集 PDF，确认 `document_image` 出现 PENDING 行、消费日志开始；
2. 在消费进行中（UPLOADED 行数 < 总行数）执行：

```bash
# 找到应用进程并强杀（模拟消费中途崩溃：消息未 ACK 留 PEL）
kill -9 <pid>
```

3. **恢复时延预期（v1.7 中-1）**：消息停留 PEL，回收者是 PelRecoverySweeper
   （XAUTOCLAIM），阈值 `pel-min-idle` 默认 **40min**——不是 invisible-duration。
   生产默认须等 ≥40min + 扫描周期；测试环境缩短等待：

```bash
# 测试 profile 覆写（直接覆写 pel-min-idle，不必动 invisible-duration）
MESSAGING_PEL_MIN_IDLE_MS=60000   # 1min
```

4. 重启应用，等待 PEL 回收 + 重投，回到 1.1 的终态断言；
5. **额外 15min 兜底验证**：即使不等 PEL 回收，`ImageReconciliationScheduler`
   会在 15min（`app.etl.image.reconcile-interval-ms` 周期）后发现超龄 PENDING
   并主动重投触发消息（`rag.image.extract_stale` 计数应 +1）。

### 1.3 幂等（验收 4 顺带）

DLQ/重放或手动重复投递：

```bash
# 手动重发触发消息（消息仅是触发器，幂等由行级条件更新保证）
redis-cli XADD SMART_RAG_rag_extract_images '*' topic rag_extract_images \
  tag '' dedupKey '' hashKey '<documentId>' headers '{}' \
  payload '{"documentId":<id>,"bucket":"...","objectKey":"...","fileName":"..."}' \
  bornTs 0 attempt 0 contentType application/json
```

重放 N 次后：MinIO 对象数与行数不变（findPending 空转 ACK / 已终态行条件更新 0 行）。

---

## 2. 验收 7：满载不影响前台（p95 <10% + GC 归因）

### 2.1 两组压测

口径（§11.7）：同一文档集（≥10 份、含大手册），分别在**空载**与**满载**两组时窗内执行
"上传 → 检索可见"全流程，比较 p95；复用 P1 分段计时日志的 e2e 段，不引入新埋点。

**空载组**：
1. 确认 `rag.image.pending_total == 0`、`redis-process-rag_extract_images-*` 线程空闲；
2. 按文档集执行上传→检索可见（Apifox/JMeter，参考 [performance-testing-guide.md](../performance-testing-guide.md)），
   记录每文档 e2e 耗时 + 时窗。

**满载组**：
1. 批量上传截图密集 PDF 制造 PENDING 积压（`rag.image.pending_total` 持续 >0）；
2. 确认处理池全忙：`redis-process-rag_extract_images-1`（concurrency=1 时单线程）
   持续处于 RUNNING（线程 dump 或 consume_seconds 持续有新样本）；
3. 满载状态下执行同一文档集压测，记录 e2e p95 + 时窗。

**判定**：两组 p95 延迟差 <10%。分段计时（download/extract/markdown）可定位回归落在哪一段。

### 2.2 GC pause 分布采集（L5）

两组时窗同步采集，测试报告须记录；若 p95 达标但满载组 GC 显著恶化，须显式标注 GC 归因：

```bash
# 方式一：JVM 启动参数（推荐，两组时窗对齐分析）
-XX:+UseG1GC -Xlog:gc*:file=/var/log/app/gc-%t.log:time,uptime,level,tags

# 方式二：Micrometer（已暴露 /actuator/prometheus）
jvm_gc_pause_seconds{action="end_of_minor_gc"} histogram
jvm_gc_pause_seconds_max
```

采集口径：两组各自的 **GC pause 总时长 / 次数 / 单次 max**，以及大对象分配压力
（图片解码 144DPI 整页 ≈8MB/页与前台共堆，重点看 humongous allocation）。

### 2.3 回归定位提示

- e2e 的 extract 段回归 + 满载组 GC 恶化 → 渲染内存并发挤占：下调 `odl-image-concurrency`；
- download 段回归 → MinIO 带宽争用（图片上传与 PDF 下载共享）；
- 纯 markdown 段回归 → 不太可能（无共享资源），优先怀疑测量噪声。

---

## 3. 结果记录模板

| 项 | 空载组 | 满载组 | 判定 |
|----|--------|--------|------|
| e2e p95（每文档集） | __ms | __ms | 差 <10%？ |
| GC pause 总时长 / 时窗 | __s | __s | 显著恶化则标注归因 |
| GC 单次 max | __ms | __ms | — |
| rag.image.pending_total（满载组维持） | 0 | >0 且处理池全忙 | 前提成立 |
| 崩溃恢复时延 | — | kill -9 → 终态 __min（含 pel-min-idle 等待） | 行全部终态 |
| extract_stale 计数 | 0 | 兜底重投是否触发 | — |
