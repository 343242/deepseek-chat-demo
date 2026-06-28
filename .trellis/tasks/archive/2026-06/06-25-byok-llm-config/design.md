# Design — BYOK 模型配置 CRUD 与 LLM SPI 动态化

> 关联：[prd.md](./prd.md)（需求/验收）、[implement.md](./implement.md)（执行计划）。
> Q1–Q6 决策结论见 prd.md §决策结论。

## 1. 现状（SPI 数据流）

```
yml(app.llm) ──@ConfigurationProperties──▶ LlmConfig record(providers, capabilities, resilience)
GenericOpenAiProviderRegistrar(BDRPP,启动一次) ──▶ 注册 GenericOpenAiProvider Bean
                                                └─ Spring 注入 Map<String,LlmProvider>
LlmClientFactory.buildSnapshot() ──遍历 LlmCapability──▶ ModelGroup.toModelCandidates()
        createRawClient(@169) → provider.createClient → wrapWithResilience(@191)
        → RegistrySnapshot(clientsById / fallbackChains / defaultClients / deepThinkingClients / filteredChains / disabledSet)
LlmClientRegistry.getDefault(@185)/getChain(@215) ◀── 14 个 d=1 调用方（upstream = MEDIUM）
```

关键源码锚点：
| 符号 | 位置 |
|---|---|
| `LlmConfig` record | `infrastructure/llm/config/LlmConfig.java:18` |
| `ModelGroup` | `infrastructure/llm/config/ModelGroup.java:22`（`toModelCandidates` @58） |
| `GenericOpenAiProviderRegistrar` | `infrastructure/llm/provider/generic/GenericOpenAiProviderRegistrar.java:30`（BDRPP） |
| `GenericOpenAiProvider` | `.../generic/GenericOpenAiProvider.java:18`（构造 `(id, config, strategyRegistry)`，可 new） |
| `LlmProvider` | `infrastructure/llm/LlmProvider.java:18`（`id/config/createClient`） |
| `LlmClientFactory` | `infrastructure/llm/registry/LlmClientFactory.java:46` |
| `LlmClientRegistry` | `infrastructure/llm/registry/LlmClientRegistry.java:43`（`refresh` @137） |
| `RegistrySnapshot` | `infrastructure/llm/registry/RegistrySnapshot.java:22`（record，6 字段） |
| `AbstractModelCandidate` | `infrastructure/llm/AbstractModelCandidate.java:19`（sealed POJO） |
| `LlmCapability` | `infrastructure/llm/LlmCapability.java:15`（CHAT/EMBEDDING/RERANKING，`yamlKey`） |

### 1.1 三个启动固化点（BYOK 要破的）
1. `GenericOpenAiProviderRegistrar` 只在启动跑一次 → 运行时无法新增 provider Bean
2. `LlmClientFactory.providers`（`Map<String,LlmProvider>`）来自 Spring 注入 → 用户 url+key 进不来
3. `buildSnapshot()` 只读 `LlmConfig`(yml) → 无 DB 源、无 user 维度

### 1.2 三个可复用机制（利好）
1. `GenericOpenAiProvider(id, ProviderConfig, CapabilityStrategyRegistry)` → 可脱离 Spring 容器直接 `new`
2. `ModelCandidate` 是 POJO（`AbstractModelCandidate` 有 setter）→ 可从 DB entity 构造
3. `LlmClientRegistry` 已有 `AtomicReference<RegistrySnapshot>` + `refresh()` → 已有热刷新入口，读写分离

---

## 2. 数据模型

### 2.1 `llm_config` 表（V16 迁移）

```sql
CREATE TABLE llm_config (
    id                  BIGINT PRIMARY KEY,               -- snowflake（IdType.INPUT + 项目 snowflake）
    user_id             BIGINT NOT NULL,                  -- 所属用户(admin 也是用户,如 userId=1); 无"系统级"DB 层,系统默认=yml
    capability_type     VARCHAR(16)  NOT NULL,            -- CHAT / EMBEDDING / RERANKING（对齐 LlmCapability 枚举名）
    provider_code       VARCHAR(64)  NOT NULL,            -- bailian / deepseek / 用户自定义
    base_url            VARCHAR(512) NOT NULL,
    api_key_cipher      BYTEA        NOT NULL,            -- AES/GCM/NoPadding 密文（含 16B auth tag）
    api_key_iv          BYTEA        NOT NULL,            -- 每行独立 12B IV
    model_name          VARCHAR(128) NOT NULL,            -- ModelCandidate.model（实际调用名）
    display_name        VARCHAR(128),
    endpoints           JSONB,                            -- {"chat":..,"embedding":..,"rerank":..} nullable
    dimension           INT,                              -- 仅 EMBEDDING
    supports_streaming  BOOLEAN NOT NULL DEFAULT FALSE,
    supports_thinking   BOOLEAN NOT NULL DEFAULT FALSE,
    priority            INT NOT NULL DEFAULT 100,
    is_default          BOOLEAN NOT NULL DEFAULT FALSE,   -- (user_id, capability_type) 下唯一；DB 部分唯一索引强制（并发安全 P0-2）
    status              SMALLINT NOT NULL DEFAULT 1,      -- 1=enabled 0=disabled
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by          VARCHAR(64),
    updated_by          VARCHAR(64),
    deleted             SMALLINT NOT NULL DEFAULT 0       -- MyBatis-Plus 逻辑删除
);
CREATE INDEX idx_llm_config_user_cap ON llm_config(user_id, capability_type, status, deleted);
-- 幂等 upsert 唯一键（ON CONFLICT 目标）；user_id NOT NULL，无需 COALESCE。
-- ⚠ 部分索引 ON CONFLICT 谓词（对抗审查 R4）：upsert SQL 必须显式 `ON CONFLICT (...) WHERE deleted = 0`，
-- 谓词与索引 WHERE 一致，否则 PG 报 "no unique constraint matching"。MyBatis-Plus 默认 insertOrUpdate 不支持部分索引，禁用。
CREATE UNIQUE INDEX uk_llm_config_user_model
    ON llm_config(user_id, capability_type, provider_code, model_name)
    WHERE deleted = 0;
-- is_default 互斥（并发安全，对抗审查 P0-2）：同 (user_id, capability_type) 至多一行 default。
-- 应用层"写前清旧"无法防并发双写（两请求都清旧→都置 default），必须 DB 部分唯一索引兜底。
CREATE UNIQUE INDEX uk_llm_config_default
    ON llm_config(user_id, capability_type)
    WHERE is_default = TRUE AND deleted = 0;
-- 逻辑删除累积治理（对抗审查 P2-16）：deleted=1 行不进唯一约束、可重复 add，长期累积膨胀。
-- 由定时任务物理清理（如保留 30 天后 hard delete），本期 migration 不含，记入运维清单。
-- 字段注释、表注释按 V15 风格补充
```

### 2.2 命名冲突处理
现有 `infrastructure/llm/config/LlmConfig.java`（record，yml 绑定）**不能与新 entity 同名**。
- entity：`LlmModelConfig`，`@TableName("llm_config")`
- 业务模块：新建 `modelconfig/`（controller/mapper/service/entity/dto），不污染 `user/` 与 `infrastructure/llm/`

---

## 3. 加密方案（AES/GCM/NoPadding）

| 项 | 方案 |
|---|---|
| 转换名 | `AES/GCM/NoPadding`（GCM 流认证模式；Java 名即 `AES/GCM/NoPadding`，含 128-bit auth tag） |
| Key | 256-bit，`app.llm.crypto.master-key`（env 注入，base64 编码 32B），启动载入内存单例 |
| IV | 每行随机 12B（`SecureRandom`），不重复即满足 GCM 安全 |
| 存储 | `api_key_cipher`（密文‖tag）+ `api_key_iv`（IV）两列 |
| 组件 | `ApiKeyCipher`（`@Component`）：`encrypt(plain)→(cipher,iv)` / `decrypt(cipher,iv)→plain` |
| 回显 | CRUD 返回 `sk-***<last4>` 脱敏；明文仅 SPI 取用时瞬态解密 |
| 启动校验 | master-key 缺失/长度错 → 启动失败（fail-fast），避免运行时才暴露。**仅当 `app.llm.byok.enabled=true` 时校验**；`enabled=false`（紧急回滚）跳过校验——否则 master-key 缺失场景下回滚指令被 fail-fast 阻断、启动不可达（对抗审查 P0-3） |

> `master-key` 旋转不纳入本期（密文无版本标记），仅文档标注为后续工作。
> **误改检测（对抗审查 P2-10）**：master-key 合法但与存量密文不匹配（运维误改 env）→ 解密静默失败、BYOK 静默回落系统级。启动时若 `llm_config` 有存量行，取一行做 canary 解密自检，失败 → WARN（不 fail-fast，让运维感知但不阻断启动）。

---

## 4. 配置解析语义（用户级 DB > yml，无系统级 DB 层）

**用户级 > yml 整行 fallback**：该用户有配置 → 用用户级整链；无配置 → 回落 yml 系统默认。

```java
// LlmModelConfigService
List<LlmModelConfig> resolveUserChain(Long userId, LlmCapability cap) {
    return mapper.selectEnabled(userId, cap);             // WHERE user_id = userId AND status=1，ORDER BY priority
}
List<LlmModelConfig> selectAll(Long userId, LlmCapability cap) {
    return mapper.selectAll(userId, cap);                 // WHERE user_id = userId AND deleted=0（不论 status）—— 区分"无行"vs"全 disabled"（R1）
}
// 上层 LlmConfigSource 区分三态：无行 / 全 disabled / 有 enabled（见 §5.4）
```

映射：`LlmModelConfig` → `ModelCandidate`（`ChatCandidate`/`EmbeddingCandidate`/`RerankCandidate`，复用 `ModelGroup.toModelCandidates` 的构造思路，但从 entity set 字段）。

> admin 也是用户（如 V3 seed `userId=1`），其 BYOK 即 `user_id=1` 的行——**无独立"系统级"层**。**写入权限见 §12：仅 owner 本人可改**，admin 只读（运维/审计）。
> **baseUrl SSRF 防护（§13）**：`LlmModelConfigService.upsert` 入口调 `BaseUrlValidator.validate(baseUrl)`，非法 URL fail-fast（不落库、不构建 provider）。

---

## 5. SPI 改造

### 5.1 运行时构造 provider（不缓存 — 核对 Mimo P2.8 更优解）

> **去掉 `LlmProviderCache`**：核对 `HttpClientFactory` 后确认 provider 缓存是过度设计，从根源消除 `computeIfAbsent` 死锁（连 `getIfPresent`/`putIfAbsent` 都不需要）：
> - `GenericOpenAiProvider` 构造仅赋值 3 个 final 字段（providerId/providerConfig/strategyRegistry），**无状态、无资源**
> - 连接复用与 provider 无关：`HttpClientFactory.buildRestClient` 每次 per-candidate 新建 `RestClient`+`HttpClient`（由 `HttpHandles.close` 释放）；`sharedOkHttpClient` 按超时缓存，也在工厂层
>
> 因此 `buildSnapshot` 内对每个 candidate 直接 `new GenericOpenAiProvider(...)`；client 资源仍由 `RegistrySnapshot` 生命周期管理（snapshot 切换时 close 旧 client）。省去 cache 失效/弱引用/泄漏管理。

```java
// buildSnapshot 内，per-candidate 构造（无 cache、无锁、无 I/O）
LlmProvider provider = new GenericOpenAiProvider(
    code, ProviderConfig.of(baseUrl, apiKey, endpoints), strategyRegistry);
CapabilityClient raw = provider.createClient(candidate);
```

### 5.2 `LlmClientFactory` 增构建重载（不改旧方法）
```java
// 已解析候选（含连接信息，解耦 ModelCandidate 不带 url/key 的问题）
record ResolvedCandidate(ModelCandidate candidate, String providerCode,
                         String baseUrl, String apiKey, Map<String,String> endpoints) {}

// 现有 buildSnapshot() 保留（系统级 yml 链路不动，作 fallback 底座）
RegistrySnapshot buildSnapshot(List<ResolvedCandidate> resolved) {
    // 抽出 @96-@125 的 createRawClient + wrapWithResilience 循环；
    // provider 直接 new（无 cache，见 §5.1）：
    //   var p = new GenericOpenAiProvider(rc.providerCode(),
    //       ProviderConfig.of(rc.baseUrl(), rc.apiKey(), rc.endpoints()), strategyRegistry);
}
```
- `ResolvedCandidate` 由 `LlmConfigSource` 产生：DB 行直接转（含解密 key）；yml fallback 时从 `LlmConfig.providers` map 取 url/key 拼装。
- **candidateId 命名空间隔离（P1 强制 — 共用 resilience 的前提）**：用户级 candidateId 必须 `u:{userId}:{modelCode}`。`circuitBreakerRegistry` 是**全局单例**（系统级 + 所有用户级共享 `getOrCreate(candidateId)`），若不隔离 → 用户 A 的 provider 故障熔断会污染系统级同名 candidate / 用户 B（同 key 同熔断器实例）。构建用户 candidate 时 id 改写为命名空间形式。
- **candidateId 双轨制（对抗审查 R1 — metrics cardinality，同类风险）**：`CapabilityClient` 增 `modelKey()` 返回 `{provider}:{modelCode}`（不含 userId 前缀）。`LlmMetrics` 所有 timer/counter/gauge 标签**改用 `modelKey`** 而非 `candidateId`——否则用户级 `u:{userId}:{model}` 会让全局 `MeterRegistry` 产生 N×M 时间序列（Prometheus 高基数爆炸，与 circuitBreakerRegistry 污染**同模式**）；`registerCircuitBreakerGauge` 的 `registeredGauges` Set + gauge 同理改用 modelKey。`candidateId` 仅用于熔断器/snapshot key 隔离。
- **modelKey 聚合 trade-off（对抗审查 R6）**：`modelKey={provider}:{model}` 隐含"同 provider_code:model = 同后端"假设。BYOK 下 `provider_code` 是用户自填字符串、不绑定 baseUrl——两个用户都填 `openai:gpt-4` 但分别指向官方端点与自建代理时，metrics（延迟/错误率/熔断 gauge）聚合同一条序列，自建端点的慢/错会污染官方端点用户。本期接受该 trade-off（主流场景 provider_code↔baseUrl 一一对应、不污染；替代方案 `baseUrlHost` 标签在自建端点多的部署下仍偏高基数）。若实际观测到污染，加 `baseUrlHost` 标签作 follow-up。
- **ResilienceConfig 来源**（核对 Mimo P1.4）：新重载继续用 `llmConfig.resolveResilience()`（系统级 yml）——用户级 **共享系统级弹性配置**（重试次数/熔断阈值/probe 超时参数）。可接受：熔断器**实例**因 candidateId 隔离而独立（仅参数共享）；per-user 弹性配置本期不支持。
- **ProbeHandler 共享**（核对 Mimo P2.9）：`LlmClientFactory.cachedProbeHandler` 工厂级单例，全局 probe 配置一致，本期不支持 per-user probe。
- **disabledSet key 规范（对抗审查 P1-5，实施前必须代码确认）**：candidateId 双轨制（系统级 `{modelCode}` vs 用户级 `u:{userId}:{modelCode}`）使"系统级紧急禁用某 candidate 同步作用于 BYOK 用户"面临命名空间不匹配风险。**Step 10 前先跑 `context({name:"RegistrySnapshot"})`** 确认 `disabledSet` 实际存 candidateId 还是 modelCode；**无论实现如何，合并语义统一按 modelCode 归一化**（剥 `u:{userId}:` 前缀后匹配）——这样系统级禁用对 BYOK 用户必然生效，`getUserChain` 落实该归一化逻辑。

### 5.3 `LlmClientRegistry` 增 per-user 快照（不改旧签名）
```java
// 有界缓存（对抗审查 R2：无界 Map → snapshot×N → client×N → 内存/连接耗尽）
private final Cache<Long, RegistrySnapshot> userSnapshots = Caffeine.newBuilder()
    .maximumSize(llmByokProps.userCacheSize())        // app.llm.byok.user-cache-size
    .expireAfterAccess(Duration.ofHours(1))
    .removalListener((id, snap, cause) -> { if (snap != null) asyncClose(snap); })
    .build();

CapabilityClient        getUserDefault(LlmCapability cap, Long userId);
List<CapabilityClient>  getUserChain(LlmCapability cap, Long userId);

/** 配置变更后清旧快照——下次请求 cache miss → lazy 从 DB 重建（§6） */
void invalidateUser(Long userId) {
    RegistrySnapshot old = userSnapshots.remove(userId);
    if (old != null) {
        asyncClose(old);                                              // 异步 close 旧 client
        old.clientsById.keySet().forEach(circuitBreakerRegistry::remove);  // P1-6 防熔断器泄漏
    }
}
// 旧 getDefault(cap)/getChain(cap) 不动 → 14 个系统级调用方零改动
// 注：eager reload（reloadUser）已砍——前端"保存→切页面→发消息"天然有延迟，即时生效无价值（用户决策）。
//     配置生效走 cache-aside：upsert 同步落库 → invalidateUser → 下次请求 lazy 构建（§6）。
```
- **cache-aside（eager reload 已砍）**：`upsert/delete` 同步落库后调 `invalidateUser(userId)` 清旧快照；下次请求 cache miss → lazy 构建（下方）。
- **cache miss 兜底（= 主路径）**：`getUserChain` 在 `userSnapshots` 未命中时：DB 有该用户行 → lazy 从 `LlmConfigSource.userChain(userId, cap)` 构建并缓存；DB 无行 → **直接 delegate 系统级 `snapshotRef`（yml），不缓存**（避免为每个无 BYOK 用户构建等价 yml 的 snapshot）。**空链回落（P0-1）天然满足**：删光配置 → invalidate → 下次 miss → DB 无行 → delegate yml，无需 eager 空链特判。
- candidateId 命名空间：`u:{userId}:{modelCode}`（见 design §5.2）。
- **destroy 清理**（核对 Mimo P1.3）：`@PreDestroy destroy()` 除现有系统级 `snapshotRef` 外，**遍历 `userSnapshots.values()` 并行 close 所有用户 client**（避免 HTTP 连接池泄漏）；`shutdown() + awaitTermination(30s)` 等异步 close 完成（R3）。
- **asyncClose 池配置与监控（对抗审查 R3）**：异步 close 旧 client 的专用小池：core=2 / max=4 / 有界队列 100 / **`CallerRunsPolicy`**（close 任务降级同步，宁可慢不丢连接）。`asyncClose` 内部 catch `HttpClient.close()` 异常 → `llm.byok.close.errors` counter + WARN（不抛，不影响 invalidateUser 调用方）。防慢关闭/连接池饱和时资源静默泄漏。
- **userSnapshots 有界**（对抗审查 R2 — per-user 维度无界）：Caffeine `maximumSize` + `expireAfterAccess` + `removalListener`（淘汰时异步 close 该 snapshot 所有 client）。避免海量用户 → snapshot×N → HttpClient 连接耗尽。容量由 `app.llm.byok.user-cache-size` 配置。
- **熔断器实例清理（对抗审查 P1-6 — R2 同模式漏网）**：`circuitBreakerRegistry`（Resilience4j 全局单例）的 `getOrCreate(candidateId)` **不随用户快照淘汰自动移除**；用户级 candidateId = `u:{userId}:{modelCode}` 含 userId，海量用户轮换 → 熔断器实例无界累积 → 内存泄漏（AC15 只覆盖 userSnapshots，没覆盖 circuitBreakerRegistry）。`removalListener` / `invalidateUser` 在 close client 同时**必须 `circuitBreakerRegistry.remove(candidateId)`**。
- **disabledSet 传播**（核对 Mimo P1.6）：`getUserChain` 读取时**合并系统级 `snapshotRef.disabledSet`**（紧急禁用某 candidate 同时作用于 BYOK 用户）；不复制 disabledSet 到每个用户快照，避免 fan-out 写。
- **本期 scope = CHAT only**（核对 Mimo P0.1 + 用户决策，见 §11）：`getUser*` 仅服务 chat 路径；EMBEDDING/RERANKING 仍走系统级 `getDefault`。

### 5.4 新增 `LlmConfigSource`（用户级 DB > yml）
```java
@Component
class LlmConfigSource {
    /** 用户链三态（对抗审查 R1）：无行→fallback yml；全 disabled→fallback yml + 可观测；有 enabled→BYOK 链 */
    List<ModelCandidate> userChain(Long userId, LlmCapability cap) {
        var allRows = configService.selectAll(userId, cap);               // deleted=0，不论 status
        if (allRows.isEmpty()) return ymlChain(cap);                      // 从未配置 → fallback yml（正常）
        var enabled = allRows.stream().filter(r -> r.status() == 1).toList();
        if (enabled.isEmpty()) {                                          // 有行但全 disabled
            log.warn("user {} all BYOK disabled for {}, fallback yml", userId, cap);
            metrics.counter("llm.byok.fallback", "reason", "all_disabled").increment();
            return ymlChain(cap);                                         // 仍 fallback 保证可用（R1 决策）
        }
        return toCandidates(enabled);                                     // 有 enabled → BYOK 链
    }
}
```
- **三态语义**：完全无行 = 用户从未配置 BYOK（正常 fallback）；全 disabled = 用户明确禁用全部（fallback 但 WARN + `llm.byok.fallback{reason=all_disabled}` 计数，前端可经 GET 接口自查）；有 enabled = 正常 BYOK。
- `ymlChain(cap)` 复用现有 `llmConfig.getCapabilityGroup(cap).toModelCandidates(cap)`——**系统默认就是 yml**，现有 buildSnapshot 链路完全不动。
- 不再有 `systemChain`：系统级 = yml 全局 snapshot（现有 `snapshotRef`），不是 DB 行。

---

## 6. 配置生效链路（同步落库 + lazy cache-aside，方案 b — 砍 eager reload）

> **用户决策（砍 eager reload）**：前端 UX 为"填供应商/Key → 保存 → 关弹窗/切页面 → 开始对话 → 发消息"，天然存在数秒到数十秒延迟；"即时生效"无价值，其复杂度（eager reload + 异步并行落库 + seq 守卫 + 落库异常处理 + 优雅关闭）全为过度设计。回归最简模型：**同步落库 + cache-aside**。

**写入语义：同步落库，API 返回即持久化（无 eventual consistency 窗口）**
```
POST /api/user/llm-config（owner 提交供应商 + 模型配置）
  → service.upsert(req)
      ├─ BaseUrlValidator.validate(req.baseUrl)        ← SSRF 防护（§13），非法 → 400
      ├─ ApiKeyCipher.encrypt(req.apiKey)              ← 加密（§3）
      └─ DB upsert（同步，事务，ON CONFLICT 幂等）      ← 落库完成才继续
  → registry.invalidateUser(userId)                    ← 清旧快照，下次请求 lazy 重建
  → API 返回成功（DB 已落）

下次 chat 请求 → registry.getUserChain(cap, userId)
  → userSnapshots miss → lazy: LlmConfigSource.userChain(userId, cap) → factory.buildSnapshot（new provider 无 cache）
  → 缓存 → 命中 ✅
```

### 6.1 一致性（同步落库 + cache-aside，大幅简化）

- **无 eventual consistency 窗口**：同步落库，API 返回 = DB 已持久化。进程崩溃不影响已返回的配置（方案 a 的"崩溃窗口"trade-off 随 eager reload 一并消除）。
- **并发 upsert（无需 seq 守卫）**：同一用户并发保存（如多 tab）→ DB 事务 + 唯一索引 `uk_llm_config_user_model` 串行化 → 最后 commit 赢；两请求都 `invalidateUser` → 下次 lazy 构建读到 DB 最终状态。**内存与 DB 不再分裂**（无独立 eager reload 步骤），故 P0-4 seq 守卫连同 `seq` 列一并删除。
- **空链回落（P0-1）天然满足**：删光配置 → invalidate → 下次 miss → `LlmConfigSource.userChain` 见 DB 无行 → fallback yml（§5.4）。无需 reloadUser 空链特判。
- **无异步执行器**：落库与 lazy 构建均在请求线程，无 fire-and-forget 池、无 `CallerRunsPolicy`、无优雅关闭问题、无落库失败 dead-letter（落库失败直接 API 报错给用户，用户重试）。异步 close 旧 client（invalidate/淘汰触发）仍用专用小池，不复用 `infrastructure/concurrent`（[[concurrent-package-is-forkjoin]]）。
- **加密时机**：明文 key 仅在 encrypt（落库前）与 SPI 取用瞬态解密时出现；DB 只存密文。

---

## 7. fallback 语义（纯 per-user，无系统级 DB 层）

> **模型简化**（核对 Mimo P1.5 连锁 + 用户洞察）：admin 也是用户（如 V3 seed `userId=1`），**不存在 `user_id IS NULL` 的系统级 DB 行**。我之前两层（系统级 DB + 用户级 DB）设计是过度设计——制造了"admin DB 行如何生效"的伪 gap。统一为纯 per-user 后该 gap 自动消失，取消 seed 也自然（DB 只存用户 BYOK，系统默认永远是 yml）。

| 层 | 来源 | 说明 |
|---|---|---|
| **系统默认** | yml（`app.llm`） | 现有 `Registrar`→`buildSnapshot`→`snapshotRef` 链路**完全不动**；全员共享底座 |
| **用户 BYOK** | DB `llm_config`（`user_id` NOT NULL） | 用户/admin 提交（eager reload + 异步落库），覆盖该用户的系统默认 |

**解析**：`getUserChain(userId, cap)` → userSnapshots 缓存 → DB 用户级 → 空 fallback 系统级 snapshot(yml)。
**系统默认变更**：改 yml（运维）→ 重启 / `registry.refresh()`；不通过 DB（无系统级 DB 层）。
**admin 与普通用户差异**：仅 CRUD 权限范围（admin 可操作任意 `userId`），配置结构相同（都是 per-user 行）。

---

## 8. userId 透传（方案 A：显式参数）

调用方从 `getDefault(cap)` 切到 `getUserDefault(cap, userId)`。
- 显式参数从 controller → service → registry 一路传，类型安全、可测。
- 后台任务（无用户上下文，如文档 ETL 的 embedding）传 `null` → 走系统级。
- 待识别：`LlmClientRegistry` 的 14 个 d=1 调用方中，哪些是请求路径（需 userId）、哪些是后台（保持 null）。implement.md Step 11 前用 `impact` 精确列出。

---

## 9. 影响面与风险

| 改动 | 类型 | 风险 |
|---|---|---|
| 表/迁移/Entity/Mapper/Service/Controller/`ApiKeyCipher`/`LlmProviderCache`/`LlmConfigSource` | 全新 | LOW |
| `LlmClientFactory` 增重载 | 加法 | LOW |
| `LlmClientRegistry` 增 per-user 快照 + `invalidateUser` | 加法（旧签名不动） | MEDIUM（内存/异步 close 管理） |
| 请求路径调用方切 `getUser*`（透传 userId） | 改调用 | MEDIUM（14 调用方中识别请求路径子集） |
| `GenericOpenAiProviderRegistrar` | 不动 | — |

> 现已知 `LlmClientRegistry` upstream = MEDIUM（无 HIGH/CRITICAL）。实施前对 `LlmClientFactory.buildSnapshot`、`LlmClientRegistry` 各补一次 `impact`，符合 CLAUDE.md。

---

## 10. 兼容性与回滚

- **向后兼容**：所有现有 `getDefault/getChain/get(candidateId)` 签名保留；系统级调用方零改动。yml 配置继续可用（fallback）。
- **功能开关**：`app.llm.byok.enabled`（默认 true）。false 时 Registry 不启用 per-user 快照，全部走系统级（等同于现状）——便于灰度与紧急回滚。
- **回滚**：
  - 代码级：关 `app.llm.byok.enabled` 即恢复纯 yml 行为。
  - DB 级：`llm_config` 表仅被新代码读，drop 表不影响旧链路（迁移可 revert）。
- **密钥丢失**：master-key 丢失 = 用户级密文不可解密 = BYOK 配置失效（回落系统级）。需在运维文档强调 key 备份，启动校验 fail-fast。

## 11. Scope：本期 CHAT-only（核对 Mimo P0.1 + 用户决策）

**成立确认**（源码验证）：EMBEDDING/RERANKING 客户端在启动时固化为 Spring Bean，运行时无法按 user 替换：
- `LlmAutoConfiguration.java:70` `registry.getDefault(LlmCapability.EMBEDDING)` → `@Primary EmbeddingModel` bean
- `RagConfig.java:83` `llmClientRegistry.getDefault(LlmCapability.RERANKING, RerankCapable.class)` → 注入 `RerankDocumentPostProcessor`（单例，被 `RerankTool`/`RagAdvisorFactory`/`EvaluationRunner` 持有）

→ 用户配 BYOK embedding/reranking → API 返回成功但请求仍走系统级 = **silent failure**（比报错更危险）。

**本期处理**：BYOK per-user 切换**仅 CHAT**。
- **切 `getUser*`**（Step 11）：`ChatServiceImpl`(:94/:121/:186)、`RewriteClientResolver`(:56)、`AgentModeStrategy`、`IntentClassifier`、`ChatRequestSpecFactory`（透传 userId）
- **不切**：`LlmAutoConfiguration:70`(embedding)、`RagConfig:83`(reranking) —— 保持系统级
- `llm_config` 表**结构预留** EMBEDDING/RERANKING 行，但 **API 层本期拒绝**（对抗审查 P1-8）：`/api/user/llm-config` 与 `/api/admin/llm-config` 的 upsert 对 `capability_type ∈ {EMBEDDING, RERANKING}` 返回 `422 Unsupported`（`GlobalResponse` 错误码标注"本期仅支持 CHAT BYOK"）。否则"存而不读"对用户是 API 层 silent failure，比 SPI 层 silent failure 更靠前、更危险（用户以为生效、实际永不消费）。CHAT 行为同改造前。
- **chat 路径不回归**（核对 Mimo P0.1）：embedding 请求（文档入库 `LlmAutoConfiguration:70`）+ rerank 请求（检索 `RagConfig:83`）仍走系统级默认，无 BYOK 透传。

**后期扩展**（Out of Scope）：embedding/reranking BYOK 需把上述 bean 改为持有 `LlmClientRegistry` 动态 resolve（类 `ChatServiceImpl`），或 request-scoped proxy。

---

## 12. 权限模型：owner-only 写 + admin 只读（对抗审查 P1-9 — 用户二次收紧）

> 核心原则（用户明确）：**模型配置仅 owner 本人可更改，其他任何角色（含 admin）都不可更改**。比"admin 可管非密钥字段"更严——admin 完全不参与 BYOK 写入，从根上消除"他人篡改用户配置"的合规与安全风险。

### 12.1 权限矩阵

| 角色 | list/get（读） | upsert/delete/禁用/失效（写） |
|---|---|---|
| **owner 本人** | ✅ 仅自己的 | ✅ 仅自己的（**唯一写入路径** `/api/user/llm-config`，user_id 从 SecurityContext 取，**禁 query param 传 userId 越权**） |
| **admin** | ✅ 任意 userId（运维排查/审计，`GET /api/admin/llm-config?userId=X`） | ❌ **全部 403/405** |
| **其他用户** | ❌ 403 | ❌ 403 |

- admin 端点**只读**：仅 `GET`，无 POST/PUT/DELETE；保留只读用于运维排障（"某用户为何 chat 报错"）与合规审计（用户已拍板保留，不去除）。

### 12.2 DTO 简化（无 admin 写 DTO）

| 接口 | DTO | 含 api_key？ |
|---|---|---|
| `/api/user/llm-config`（owner 唯一写入） | `UpsertLlmConfigRequest` | ✅ 含（owner 提交，Jackson `FAIL_ON_UNKNOWN_PROPERTIES=true` 防注入） |
| `/api/admin/llm-config`（只读） | 无请求体，返回 `LlmConfigVO` | —（VO 脱敏 `sk-***<last4>`） |

- 不再有 `AdminUpdateLlmConfigRequest`（admin 不写）。key 泄露/作废：**owner 自己**重新提交，无需 admin 介入。

### 12.3 审计

| 层 | 做法 |
|---|---|
| 配置变更 | `created_by/updated_by` 由 `MyBatisPlusMetaHandler` 自动填，owner 自操作留痕 |
| admin 只读访问（可选） | admin GET 跨用户配置记访问日志（who/whose/when），合规审计 |

> §12.1 把"他人篡改用户 BYOK"风险从"需事后审计"降级为"权限上根本不可达"；明文 key 仅 owner 提交时流经自己控制的请求，admin/他人全程无写、无明文。

---

## 13. baseUrl SSRF 防护（对抗审查 P1-10）

> **威胁模型**：BYOK 让任意已登录用户提交任意 `base_url`，服务端用该 URL 发 LLM 请求。恶意用户可填内网地址（`http://169.254.169.254` 云 metadata、`http://10.0.0.5:6379` Redis、`http://127.0.0.1:9090` 内网服务）→ **SSRF** 探测/攻击内网、窃取云 IAM 凭证。这是 BYOK 引入的最大安全面，必须收敛。

### 13.1 防护层

| 层 | 措施 | 详情 |
|---|---|---|
| **协议白名单** | 仅 `http`/`https` | 拒 `file`/`gopher`/`ftp`/`dict`/`jar`/`netdoc` 等（`gopher` 可构造任意 TCP，尤危险） |
| **端口白名单** | 默认仅 80/443（可配 `app.llm.byok.allowed-ports`） | 拒 0、22、25、3306、5432、6379、9200、11211、27017 等内网服务端口；未指定端口按协议默认 |
| **内网 IP 黑名单** | resolve host **所有** A/AAAA 记录，任一命中即拒 | `127.0.0.0/8`、`10.0.0.0/8`、`172.16.0.0/12`、`192.168.0.0/16`、`169.254.0.0/16`（含云 metadata `169.254.169.254`）、`100.64.0.0/10`（CGN）、`0.0.0.0/8`、`::1`、`fc00::/7`、`fe80::/10`、**IPv4-mapped IPv6** `::ffff:0:0/96`（防 IPv6 包装绕过） |
| **禁重定向** | LLM HTTP 客户端 `followRedirects=false` | 防"合法域名校验通过 → 302 跳内网"绕过；在 `HttpClientFactory.buildRestClient` 配置 |
| **形态/编码绕过** | 归一化后再校验 | 先 URL 解码 → 拒 `localhost`/`*.local`/`*.internal`、IP 字面量内网段、十进制/八进制/十六进制 IP（`http://2130706433/` = 127.0.0.1）、末尾点（`evil.com.`） |

### 13.2 实现与调用点

- 组件：`infrastructure/llm/config/BaseUrlValidator`（`@Component`），`validate(String baseUrl) → void`；非法抛 `IllegalArgumentException`，`ControllerAdvice` 映射 HTTP 400。
- **调用点 = `LlmModelConfigService.upsert` 入口**（提交时 fail-fast，非法 URL 不落库、不构建 provider）。
- 黑名单/端口配置化：`app.llm.byok.base-url.*`（运维可放宽/收紧）。
- **DNS rebinding 残余风险 + 接口预留（对抗审查 R5）**：校验时解析公网、连接时解析内网的 TOCTOU，本期 BYOK 威胁模型下非主要向量（恶意用户填内网地址会被静态校验拦）。但 `BaseUrlValidator` 内部**经 `DnsResolver` 接口解析**（默认 `DefaultDnsResolver` 包 `InetAddress.getAllByName`），**预留注入点**——未来注入"resolve→校验 IP→连接同 IP"实现即可在连接阶段二次校验，无需改 validator 签名。该加固为 follow-up（见 prd Out of Scope）。

### 13.3 与现有 HttpClient 的衔接

- `HttpClientFactory.buildRestClient`（design §5.1）是 per-candidate 新建 `RestClient`+`HttpClient` 的工厂——**在此设置 `followRedirects=false`**，使所有 BYOK 请求（含用户改 baseUrl 后重建的 client）统一不跟随重定向。
- `BaseUrlValidator`（提交时校验）+ `followRedirects=false`（运行时兜底）两层叠加。

> §13 与 §12 协同：权限层防"他人篡改配置"，SSRF 层防"owner 自己提交恶意 baseUrl 探内网"。两者覆盖 BYOK 全部新增攻击面。

---

## 14. 用户生命周期联动（对抗审查 R2 — 孤儿资源清理）

> **问题**：用户被 disable/delete 时，`llm_config` 行、`userSnapshots` 缓存、`circuitBreakerRegistry` 熔断器实例均残留——封禁用户的加密密文仍可被 admin GET（§12），缓存/熔断器资源不主动释放，海量封禁场景下孤儿累积。

### 14.1 领域事件解耦（不引入 llm→user 直接依赖）

- 用户管理模块（`SysUserService`）在 disable/delete 时发 Spring 事件：
  - `UserDisabledEvent(userId)` → `LlmClientRegistry @EventListener` 调 `invalidateUser(userId)`（清缓存 + 熔断器，不等 TTL）；**llm_config 保留**（用户可恢复）
  - `UserDeletedEvent(userId)` → `invalidateUser(userId)` **+** `LlmModelConfigMapper.markDeletedByUser(userId)`（`llm_config` 逻辑删除 `deleted=1`，审计行保留但查询不命中）
- 用 `ApplicationEventPublisher` + `@EventListener`，避免 llm 模块反向依赖 user 模块。

### 14.2 认证层前提

- disabled 用户的 token 须在**认证层失效**（标准做法），到不了 chat 路径的 `getUserChain`。事件 invalidate 是加速资源释放（不等 access TTL），不是访问控制——访问控制由认证层兜底。
- implement 时核对现有 auth（JWT/Session）是否检查用户状态；若未检查，是 user 模块既有缺口，记录为前置依赖（不在本 task 内修，但 §14.1 的缓存清理仍要做）。
