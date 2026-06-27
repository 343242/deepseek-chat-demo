# Implement — BYOK 模型配置 CRUD 与 LLM SPI 动态化

> 关联：[prd.md](./prd.md)、[design.md](./design.md)。
> 每步前置：改现有符号前跑 `impact({target, direction:"upstream"})`，HIGH/CRITICAL 先停下汇报。

## 执行顺序（依赖链）

### 阶段 A — CRUD 闭环（可独立验证）

- [x] **Step 1 · Flyway 迁移** ✅ V16__llm_config.sql 已写（表 + 3 索引 + R4 谓词警告；apply 验证待 dev 启动）
  - 新建 `src/main/resources/db/migration/V16__llm_config.sql`（schema 见 design §2.1，含索引 + COMMENT）
  - 验证：`./mvnw -q -pl . flyway:info` 或启动应用看迁移应用成功；`SELECT * FROM llm_config LIMIT 0`
  - gate：迁移在 dev 库 apply 无错

- [x] **Step 2 · 加密 `ApiKeyCipher`** ✅ 代码完成（ApiKeyCipher + LlmCryptoProperties + LlmByokProperties + dev yml + ApiKeyCipherTest 11 用例全绿，全量 190 测试无回归）；dev yml `byok.enabled` 默认 false（避免未配 master-key 阻断 dev 启动）。**canary 自检（P2-10）✅ 已补**：LlmCryptoCanaryRunner（@EventListener ApplicationReadyEvent，enabled+available 才跑，mapper.selectOneAny 取存量行解密，失败 WARN 不抛不阻断启动）+ 5 测试
  - `infrastructure/llm/crypto/ApiKeyCipher.java`（`@Component`，AES/GCM/NoPadding，12B IV）
  - 配置类 `LlmCryptoProperties`（`app.llm.crypto.master-key`，base64 32B），启动校验：**仅 `app.llm.byok.enabled=true` 时 fail-fast**（P0-3）；`enabled=false` 跳过，保证回滚路径可达
  - **canary 自检（P2-10）**：启动时若 `llm_config` 有存量行，取一行解密自检，失败 → WARN（不 fail-fast，让运维感知误改但不阻断）
  - `application-dev.yml` 增 `app.llm.crypto.master-key: ${LLM_MASTER_KEY:}`（env 注入，不入库）
  - 验证：单测 `ApiKeyCipherTest`（encrypt→decrypt 往返、不同 IV、`enabled=true` 缺/非法 key 启动失败、`enabled=false` 缺 key 启动成功、canary 失败仅 WARN）

- [x] **Step 3 · Entity + Mapper** ✅ 代码完成（LlmModelConfig entity + LlmModelConfigMapper + XML：selectEnabled/selectAll/upsert[ON CONFLICT WHERE deleted=0 R4]/clearOtherDefaults/markDeletedByUser + @MapperScan 加 modelconfig.mapper，impact SmartRagApplication=LOW/0 调用方；test-compile 通过）。**偏差**：created_by/updated_by 不走 MetaHandler（其仅填时间戳），改由 Step 4 service 从 UserContextProvider 取 owner userId 填充（效果同为 owner 留痕）。SQL 正确性靠真实 PG 冒烟（遵循 VectorStoreMapperTest 惯例）
  - `modelconfig/entity/LlmModelConfig.java`（`@TableName("llm_config")`、`@TableId(IdType.INPUT)`、`@TableLogic deleted`、`createdAt/updatedAt` FieldFill 对齐 `MyBatisPlusMetaHandler`）
  - `modelconfig/mapper/LlmModelConfigMapper.java`（`extends BaseMapper<LlmModelConfig>`）+ XML
  - XML 自定义：`selectEnabled(userId, cap)`、`selectAll(userId, cap)`（R1 三态）、`markDeletedByUser(userId)`（R2）、`upsert(...)`（**`ON CONFLICT (user_id, capability_type, provider_code, model_name) WHERE deleted = 0 DO UPDATE ...`——谓词必须匹配部分索引 `uk_llm_config_user_model`，对抗审查 R4；禁用 MyBatis-Plus 默认 `insertOrUpdate`**）；**无 `selectSystem`**（design §7）；**无 `seq` 列**（砍 eager reload）
  - 验证增：软删（deleted=1）后重建同 (user, cap, provider, model) → 新行 INSERT、旧行保留（R4）；并发双 upsert → 唯一索引拦
  - 验证：`./mvnw -q test-compile` 通过

- [x] **Step 4 · Service（同步落库版，阶段 A）+ BaseUrlValidator** ✅ 代码完成
  - BaseUrlValidator + DnsResolver + DefaultDnsResolver（SSRF AC25-28 全覆盖：协议/端口/形态编码/内网 IPv4+IPv6+IPv4-mapped，46 测试绿；DnsResolver 预留 R5 follow-up）
  - LlmModelConfigService + Impl + UpsertLlmConfigRequest DTO（upsert/delete/resolveUserChain/selectAll/decryptKey/maskKey；P1-8 仅 CHAT → UNSUPPORTED_OPERATION；owner-only delete；is_default 清旧快速路径；maskKey 解密失败兜底 ****；userId 显式参数方案 A，不调 registry.invalidateUser 留 Step 11）
  - LlmByokProperties 加 allowedPorts（端口白名单可配）
  - 16 service 测试绿（AC3 owner-only / AC21 非 CHAT / AC5 脱敏 / is_default 清旧）
  - 偏差：created_by/updated_by 由 service 填（非 MetaHandler，其仅填时间戳）；invalidate 接入在 Step 11
  - `modelconfig/service/LlmModelConfigService` + `impl/LlmModelConfigServiceImpl`
  - **权限（design §12）**：DB CRUD **owner-only 写**（仅本人 user_id）；admin 不经 service 写（admin controller 只读）。`upsert/delete` 入口校验 `userId == SecurityContext 当前用户`
  - **baseUrl SSRF 防护（design §13）**：`infrastructure/llm/config/BaseUrlValidator`（`@Component`），`upsert` 入口调 `validate(baseUrl)`——协议白名单（http/https）、端口白名单（默认 80/443 可配）、host 解析所有 A/AAAA 查内网黑名单（含云 metadata `169.254.169.254`、IPv4-mapped IPv6）、归一化解码防编码绕过、禁 `localhost`/`*.local`/`*.internal`/十进制 IP；非法抛 `IllegalArgumentException`（ControllerAdvice → 400）。**host 解析经 `DnsResolver` 接口**（默认 `DefaultDnsResolver` 包 `InetAddress.getAllByName`），预留注入点供未来 DNS rebinding 连接级加固（R5 follow-up）
  - `resolveUserChain(userId, cap)`（status=1）+ `selectAll(userId, cap)`（deleted=0 不论 status，R1 三态区分）+ `decryptKey(entity)` + `maskKey(entity)`（回显 `sk-***<last4>`）
  - `is_default` 唯一性：DB 部分唯一索引 `uk_llm_config_default` 兜底（对抗审查 P0-2，并发安全），Service 写前清旧默认作快速路径；并发双写由索引拦截
  - 本步为**纯 DB 同步版**，保证阶段 A 可验证 AC3-AC6；**eager reload + 异步落库**在 Step 11 集成（依赖 Registry 改造）
  - 验证：`LlmModelConfigServiceImplTest`（CRUD owner-only、resolveUserChain 用户级>yml 回落、默认互斥）+ `BaseUrlValidatorTest`（协议/端口/IP 黑名单/编码绕过/`169.254.169.254`/IPv4-mapped IPv6/十进制 IP）

- [x] **Step 5 · Controller（owner 唯一写 / admin 只读，design §12）** ✅ 代码完成
  - UserLlmConfigController `/api/user/llm-config`（owner 唯一写入：GET list / POST upsert / DELETE；userId 从 UserContextProvider 取，禁 query param 越权；@PreAuthorize isAuthenticated）
  - AdminLlmConfigController `/api/admin/llm-config`（仅 GET 只读任意 userId；@PreAuthorize user:manage；无 POST/PUT/DELETE）
  - LlmConfigVO record（脱敏 apiKeyMasked，无明文 key）+ UpsertLlmConfigRequest DTO
  - 6 controller 测试绿（owner userId 透传、脱敏、admin 只读）
  - 非 CHAT 由 service 抛 UNSUPPORTED_OPERATION（AC21）；越权由 service.delete 校验 FORBIDDEN（AC22）
  - `modelconfig/controller/UserLlmConfigController` → `/api/user/llm-config`（**唯一写入入口**：owner 本人 GET/POST/PUT/DELETE 自己的配置，user_id 从 SecurityContext 取，**禁 query param 传 userId 越权**）
  - `modelconfig/controller/AdminLlmConfigController` → `/api/admin/llm-config?userId=X`（`@PreAuthorize` admin 角色）**仅 `GET` 只读**（list/get 任意 userId，运维/审计）；**无 POST/PUT/DELETE**（admin 不写）
  - DTO：`UpsertLlmConfigRequest`（owner 写，含 api_key，Jackson `FAIL_ON_UNKNOWN_PROPERTIES=true` 防注入）；`LlmConfigVO`（所有读取脱敏 `sk-***<last4>`）；**无 `AdminUpdateLlmConfigRequest`**
  - **API 拒绝非 CHAT BYOK（P1-8）**：upsert `capability_type ∈ {EMBEDDING,RERANKING}` → 422 Unsupported
  - 响应统一 `GlobalResponse.ok(...)`（对齐 `UserController`）
  - 验证：`./mvnw -q test`（controller 切片：owner 写自己 ✅、owner 写他人 403、admin GET 任意 ✅、admin POST/PUT/DELETE 403/405、非 CHAT 返回 422、越权拒绝、脱敏）；手测 curl 路径 + 权限

- [x] **Step 6 · （取消 seed，核对 Mimo P1.5）** ✅ 核对完成：无 ApplicationRunner seed；系统级 = yml（现有 Registrar 链路）；无系统级 DB 层（design §7）；空 DB 启动系统 chat 走 yml fallback（待阶段 B 验证）
  - 不引入 `ApplicationRunner` seed（避免与 Flyway 惯例冲突 + seed 冗余，见 design §7）
  - 系统级配置来源：**yml 默认底座（现有 Registrar 链路）**；无系统级 DB 层（design §7），系统默认变更 = 改 yml（运维）
  - 验证：空 DB 启动 → 系统 chat 走 yml fallback 正常；admin CRUD 管理**用户** BYOK（非密钥字段，design §12）

> **阶段 A review gate**：CRUD 全绿、fallback 正确、SPI 未动。可独立合并/验证。

---

### 阶段 B — SPI 动态化（依赖阶段 A）

- [x] **Step 7 · （取消 ProviderCache，核对 Mimo P2.8 更优解）** ✅ ResolvedCandidate record（candidate+providerCode+baseUrl+apiKey+endpoints）；核对 HttpClientFactory 连接复用在工厂层，provider 无状态，缓存无意义
  - 不引入 `LlmProviderCache`（核对 `HttpClientFactory`：连接复用在工厂层，provider 无状态，缓存无意义）
  - `LlmClientFactory` 新增 `ResolvedCandidate` record + `buildSnapshot(List<ResolvedCandidate>)` 重载（内部直接 `new GenericOpenAiProvider`，无 cache 无锁）
  - 验证：单测（buildSnapshot 构建用户链、candidateId 命名空间隔离 `u:{userId}:{modelCode}`、熔断器按 id 隔离不污染系统级）

- [x] **Step 8 · `LlmConfigSource`** ✅ 三态（无行/全disabled+counter/有enabled）+ entity→ResolvedCandidate（解密key+命名空间candidateId `u:{userId}:{model}`）+ endpoints 防御解析；LlmMetrics.recordByokFallback（AC29），6 测试
  - `infrastructure/llm/config/LlmConfigSource.java`（design §5.4）
  - `userChain(userId, cap)` **三态（R1）**：无行→fallback yml；全 disabled→fallback yml + WARN + `llm.byok.fallback{reason=all_disabled}` 计数；有 enabled→BYOK 链；`toCandidates(rows, cap)`；**无 `systemChain`**（design §7）
  - 验证：单测（无行 fallback、**全 disabled fallback + counter**、有 enabled 用 BYOK）

- [x] **Step 9 · `LlmClientFactory` 构建重载** ✅ buildSnapshot(List&lt;ResolvedCandidate&gt;) 重载（provider 直接 new 无 cache）+ 抽 buildChain/createRawClient(provider,candidate) 共用；impact=LOW/0 进程
  - 前置：`impact({target:"LlmClientFactory.buildSnapshot", direction:"upstream"})`
  - 抽出 `buildSnapshot(List<ModelCandidate>, Function<ModelCandidate,LlmProvider>)` 通用方法（保留旧 `buildSnapshot()` 调它）
  - 验证：`./mvnw -q test`（现有 `LlmClientFactoryTest` / `RegistrySnapshotTest` 全绿，证明未破坏现状）

- [x] **Step 10 · `LlmClientRegistry` per-user 快照（cache-aside，砍 eager reload）** ✅ userSnapshots(Caffeine 有界+removalListener)+getUser*/getUserDefault+invalidateUser+asyncClose 专用 ThreadPoolExecutor(core2/max4/queue100/CallerRunsPolicy，不复用 fork-join)+熔断器 evict(P1-6)+disabledSet 归一化 stripUserPrefix(P1-5)+@PreDestroy 排空；impact=MEDIUM/14 调用方零改动（旧 API 保留），8 测试
  - 前置：`impact({target:"LlmClientRegistry", direction:"upstream"})`（已知 MEDIUM，14 调用方）
  - 前置代码确认（P1-5）：`context({name:"RegistrySnapshot"})` 确认 `disabledSet` 存 candidateId 还是 modelCode → 落实合并归一化逻辑
  - 增 `userSnapshots`（Caffeine 有界）+ `getUserDefault/getUserChain`（**cache miss 时 lazy 从 DB 构建**，无行 delegate yml——主路径）+ `invalidateUser(userId)`（**配置变更后清旧快照**）；**无 `reloadUser`**（eager 已砍，§6）
  - 异步 close 旧 client（专用小池：core=2/max=4/队列100/`CallerRunsPolicy`，**不复用 `infrastructure/concurrent`**，R3）；**invalidate/淘汰时同步 `circuitBreakerRegistry.remove(candidateId)`（P1-6 防熔断器泄漏）**
  - **asyncClose 监控（R3）**：catch close 异常 → `llm.byok.close.errors` counter + WARN；`@PreDestroy destroy()` 遍历 `userSnapshots.values()` 并行 close + `awaitTermination(30s)`
  - **disabledSet 归一化（P1-5）**：`getUserChain` 合并系统级 disabledSet 时按 modelCode 归一化匹配（剥 `u:{userId}:` 前缀），保证系统级紧急禁用对 BYOK 用户生效
  - `app.llm.byok.enabled` 开关：false 时 `getUser*` 直接 delegate 到系统快照
  - 验证：单测（**cache miss lazy 构建并缓存**、**invalidate 后下次 miss 重建**、**空链（DB 无行）delegate yml**、invalidate 清理 + 熔断器 remove、disabledSet 归一化、异步 close、开关关闭行为）

- [ ] **Step 11 · 同步落库 + invalidate + CHAT 调用方切 `getUser*`（CHAT-only，砍 eager reload）**
  - **Service 改造（同步落库 + cache-aside，design §6）**：`upsert/delete` 流程
    - `BaseUrlValidator.validate`（§13 SSRF）→ `ApiKeyCipher.encrypt`（§3）→ **同步 DB upsert/delete**（事务，`ON CONFLICT` 幂等，失败直接 API 报错）
    - 落库成功后 `registry.invalidateUser(userId)`（清旧快照，下次请求 lazy 重建）
    - **无异步执行器、无 seq、无 eager reload**（全砍，§6）
  - **CHAT 调用方切 `getUser*`**（透传 userId）：`ChatServiceImpl`(:94/:121/:186)、`RewriteClientResolver`(:56)、`AgentModeStrategy`、`IntentClassifier`、`ChatRequestSpecFactory`——**Step 11 前用 `impact` 逐个确认是请求路径（有 userId 上下文）还是后台（传 null 走系统级），P2-11 gate**
  - **不切**（保持系统级）：`LlmAutoConfiguration:70`(embedding)、`RagConfig:83`(reranking)
  - gate：每改一个调用点跑对应模块测试
  - 验证：`./mvnw -q test` 全绿；端到端「提交 chat BYOK（同步落库）→ invalidate → 下次 chat 请求 cache miss → lazy 命中新 key」；**删光配置 → invalidate → 下次 miss → delegate yml（P0-1）**；**全 disabled → fallback yml + counter（R1）**；embedding/rerank 行为不回归

- [ ] **Step 11b · 用户生命周期联动（R2，领域事件解耦）**
  - `SysUserService` disable/delete 时发 `UserDisabledEvent`/`UserDeletedEvent`（Spring `ApplicationEventPublisher`）
  - `LlmClientRegistry @EventListener`：disable → `invalidateUser(userId)`（清缓存+熔断器，llm_config 保留）；delete → `invalidateUser` + `mapper.markDeletedByUser(userId)`（llm_config `deleted=1`，审计保留）
  - **前置核对**：确认认证层对 disabled 用户 token 失效（标准做法）；若未检查用户状态，记录为 user 模块既有缺口（前置依赖，不在本 task，但 §14.1 缓存清理仍要做）
  - 验证：单测（delete 事件 → llm_config deleted=1 + 缓存空；disable 事件 → 缓存空 + llm_config 保留）

- [ ] **Step 12 · 端到端验证 + 文档**
  - 场景：用户配 BYOK（POST /api/user/llm-config）→ 立即 chat → 命中新 key（日志 provider key hash 变化，**不等 DB 落库**）
  - 场景：删除用户配置 → invalidate → 下次请求 lazy 重建（剩余链空则 delegate yml）
  - 场景：admin 改系统级默认 → 影响无 BYOK 用户
  - 场景（同步落库确定性）：提交配置 → API 返回 = DB 已落 → 重启后配置仍在（无 eventual consistency 窗口，方案 b 取代方案 a）
  - 场景（不回归，核对 Mimo P0.1）：embedding 请求（文档入库）+ rerank 请求（检索）仍走系统级默认，行为同改造前
  - 更新 spec：`.trellis/spec/` 下 LLM 相关文档（若需）
  - 验证：`./mvnw -q verify`（或全量 `test`）

---

## Rollback Points

| 节点 | 回滚方式 |
|---|---|
| 阶段 A 完成 | 仅 revert CRUD 代码；`V16` 迁移可保留（表空不影响旧链路）或 revert |
| Step 9-10 | `app.llm.byok.enabled=false` → Registry 全走系统级，等同现状 |
| Step 11 | 调用方逐点切换，单点 revert 不影响全局 |
| 密钥事故 | master-key 丢失 → BYOK 配置失效自动回落系统级；恢复 key 后可解密 |

## 验证命令汇总

```bash
# 编译
./mvnw -q -DskipTests compile
# 全量测试
./mvnw -q test
# 单模块/单测
./mvnw -q test -Dtest=ApiKeyCipherTest
# 启动检查迁移
./mvnw -q spring-boot:run -Dspring-boot.run.profiles=dev   # 看 Flyway / Registry / Seeder 日志
```

## 关键约束（来自 CLAUDE.md / 项目）

- 改 `LlmClientFactory` / `LlmClientRegistry` / 调用方前必跑 `impact`，HIGH/CRITICAL 停下汇报
- commit 前跑 `detect_changes({scope:"compare", base_ref:"main"})` 核对影响范围
- entity/mapper/controller 风格对齐 `SysUser` / `UserController` / `SysUserRoleMapper.xml`
- 新代码读起来像周围代码：注释密度、命名、`GlobalResponse` 包装、`@PreAuthorize` 权限
