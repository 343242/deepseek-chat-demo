# BYOK 模型配置 CRUD 与 LLM SPI 动态化

## Goal

新增 `llm_config` 单表 CRUD（用户级 BYOK owner-only 写；系统默认=yml，无系统级 DB 层），`AES/GCM/NoPadding` 加密 `api_key`，baseUrl SSRF 防护，DB > yml fallback；改造 LLM SPI 两处（`LlmClientFactory` 构建重载 / `LlmClientRegistry` per-user 快照 cache-aside + invalidate，provider 运行时直接 new 无 cache）支持 CHAT 配置生效（**同步落库 + 下次请求 lazy 构建，砍 eager reload**）。

## Background

当前 LLM 配置完全由 `application-*.yml`(`app.llm`) 驱动，启动时 `GenericOpenAiProviderRegistrar`(BDRPP) 一次性注册 provider Bean、`LlmClientFactory.buildSnapshot()` 一次性构建全局快照。运行时无法新增/修改配置，也无用户维度。

用户需要 BYOK（Bring Your Own Key）能力：每个用户可自带模型 provider 与 api_key，且**配置后下次请求生效**（同步落库 + lazy 构建，无需重启）。系统仍保留 yml 作为系统级默认与 fallback 底座。

## Requirements

### 功能性
- **F1 单表 CRUD**：一张 `llm_config` 表同时承载系统级（`user_id IS NULL`）与用户级（`user_id = <uid>`）配置
- **F2 用户自服务（CHAT）**：用户可对自己的 **chat** 配置 BYOK 增删改查，仅本人可见（embedding/reranking BYOK 见 Out of Scope；表可存、SPI 本期不消费）
- **F3 owner-only 写 + admin 只读**：模型配置**仅 owner 本人可更改**，其他任何人（含 admin）不可更改；admin 仅 `GET` 只读查看任意 userId 配置（运维/审计）。**无 admin 代管写入**（design §12，用户二次收紧）
- **F4 BYOK 整行 fallback（即时生效仅 CHAT）**：用户级有配置 → 用用户级整链；无配置 → 回落 yml 系统默认。本期仅 CHAT 接入 SPI 即时生效
- **F5 用户级 > yml**：用户有 BYOK 配置优先；无配置 fallback yml 系统默认（无系统级 DB 层、无 seed）
- **F6 下次请求生效（同步落库 + lazy）**：用户提交配置 → 同步落库（加密 + DB）+ invalidate 旧快照 → API 返回即持久化；下一次 chat 请求 cache miss 时 lazy 从 DB 构建并缓存。前端"保存→切页面→发消息"天然有延迟，无需即时生效（**砍 eager reload**，用户决策）
- **F7 api_key 加密**：`AES/GCM/NoPadding`，每行独立 IV；CRUD 回显脱敏 `sk-***<last4>`

### 非功能性
- **N1 向后兼容**：现有 `getDefault/getChain/get(candidateId)` 调用方零改动；yml 配置继续可用
- **N2 不破坏现状**：现有 Registry 测试全绿；新增为加法，不改旧签名
- **N3 安全**：master-key 从 env 注入，不入库不入 git；启动校验仅 `enabled=true` 时 fail-fast（P0-3）；**baseUrl SSRF 防护**（协议/端口白名单 + 内网 IP 黑名单含云 metadata + 禁重定向，design §13）；**写入权限 owner-only**（design §12）
- **N4 可回滚**：`app.llm.byok.enabled=false` 即恢复纯 yml 行为

## Constraints

- 单表设计（不分 Provider/Model 多表）
- entity 不得与现有 `infrastructure/llm/config/LlmConfig` record 同名 → 用 `LlmModelConfig`
- 改 `LlmClientFactory` / `LlmClientRegistry` 前必须 `impact` 分析（CLAUDE.md），HIGH/CRITICAL 需先汇报
- 风格对齐项目现有 entity/mapper/controller（`SysUser`、`UserController`、`SysUserRoleMapper.xml`）
- 用户级配置不进 Spring 容器（不注册 Bean），运行时构造

## Out of Scope

- **EMBEDDING/RERANKING 的 BYOK 即时生效**（启动固化 bean 改造，见 design §11；`llm_config` 表可存，后期扩展）
- **seed 机制 + 系统级 DB 层**（取消：DB 仅存用户 BYOK，系统默认 = yml，见 design §7）
- master-key 旋转/版本化（密文不加版本标记，仅文档标注后续）
- 配置变更的热事件广播（多实例间同步）——本期单实例 invalidate 即可
- 前端管理界面（仅交付 REST API）
- yml 明文密钥治理（单独议题，不在本 task；但新代码不再引入明文）
- **DNS rebinding 连接级加固**（R5 follow-up）：`BaseUrlValidator` 已留 `DnsResolver` 注入点；连接阶段二次 IP 校验在 `HttpClientFactory` 注入自定义 `DnsResolver`（Apache HttpClient 5）实现，本期不做

## Acceptance Criteria

- [ ] **AC1** `V16__llm_config.sql` 在 dev 库 apply 成功，表结构/索引/注释符合 design §2.1
- [ ] **AC2** `ApiKeyCipher` 单测：encrypt→decrypt 往返、IV 唯一性、master-key 缺失/非法启动失败
- [ ] **AC3** 用户 CRUD（owner-only）：owner 仅能 CRUD 自己的配置（user_id=自己）；admin 仅 `GET` 只读（无 POST/PUT/DELETE）；非 owner 的写操作 → 403
- [ ] **AC4** `resolveUserChain`：用户有 DB 行 → 返回用户级；无 → fallback yml 系统默认；按 priority 排序
- [ ] **AC5** api_key 回显脱敏；明文仅 SPI 取用时瞬态解密；DB 中为密文
- [ ] **AC6**（无系统级 DB 层）用户无 BYOK → 请求 fallback yml 系统默认正常；改默认 = 改 yml（运维）
- [ ] **AC7** `buildSnapshot` 重载：per-candidate `new GenericOpenAiProvider`（无 cache 无锁，§5.1）；candidateId 命名空间 `u:{userId}:{modelCode}` 隔离熔断器不污染系统级
- [ ] **AC8** `LlmClientRegistry`：`invalidateUser(userId)` 后下次请求重建快照；旧 client 异步 close
- [ ] **AC9** **下次请求生效（同步落库 + lazy）**：用户提交 BYOK → API 同步落库（加密 + DB）+ invalidate → 返回成功（DB 已落）；下一次 chat 请求 cache miss → lazy 从 DB 构建命中新 key；前端切换延迟内自然生效，无需即时
- [ ] **AC10** 向后兼容：现有 `LlmClientFactory`/`Registry` 相关测试全绿；`app.llm.byok.enabled=false` 时行为等同改造前
- [ ] **AC11** commit 前 `detect_changes({scope:"compare", base_ref:"main"})` 仅影响预期符号
- [ ] **AC12** `./mvnw -q test` 全绿
- [ ] **AC13** **不回归**：EMBEDDING/RERANKING 仍走系统级默认（`LlmAutoConfiguration:70`/`RagConfig:83` 未动），embedding/rerank 请求行为同改造前
- [ ] **AC14** **metrics cardinality 不爆炸**（对抗审查 R1）：用户级 metrics 标签用 `modelKey={provider}:{modelCode}`，N 用户不产生 N×M 时间序列；`candidateId` 命名空间仅用于熔断器/snapshot key
- [ ] **AC15** **userSnapshots 有界**（对抗审查 R2）：Caffeine `maximumSize`+`expireAfterAccess`+`removalListener`（淘汰 close client）；海量用户不 OOM/连接耗尽
- [ ] **AC16** **空链回落**（P0-1）：用户删光所有 chat 配置 → invalidate → 下次 chat 请求 miss → DB 无行 → fallback yml 系统默认（不报"无可用模型"）
- [ ] **AC17** **is_default 并发互斥**（P0-2）：并发双请求给同 (user_id, cap) 设默认 → DB 部分唯一索引 `uk_llm_config_default` 拦截，至多一行 default
- [ ] **AC18** **回滚路径可达**（P0-3）：`app.llm.byok.enabled=false` 且 master-key 缺失 → 启动成功（跳过 key 校验），恢复纯 yml 行为
- [ ] **AC19** **并发 upsert 一致性**（P0-4 简化）：同用户并发保存 → DB 事务 + 唯一索引串行化，最后 commit 赢；两请求都 invalidate → 下次 lazy 构建读最终状态；**无 seq 守卫**（同步落库无内存/DB 分裂）；落库失败直接 API 报错（同步，无需异步可观测 P1-7）
- [ ] **AC21** **API 拒绝非 CHAT BYOK**（P1-8）：upsert `capability_type ∈ {EMBEDDING, RERANKING}` → 422；embedding/rerank 请求行为同改造前（走系统级）
- [ ] **AC22** **owner-only 写**（P1-9 收紧）：模型配置仅 owner 本人可 upsert/delete；admin/其他用户对非己配置的写操作 → 403；admin 仅 `GET` 只读（返回脱敏 VO，无明文 key）；`updated_by` 自动留痕
- [ ] **AC23** **熔断器与 disabledSet**（P1-5/P1-6）：用户快照淘汰/失效时 `circuitBreakerRegistry.remove(candidateId)`（无泄漏）；系统级 disabledSet 按 modelCode 归一化合并到 BYOK 用户（紧急禁用生效）
- [ ] **AC25** **baseUrl 协议白名单**（P1-10）：upsert `base_url` 非 `http`/`https`（如 `file`/`gopher`/`ftp`/`dict`）→ 400 拒绝
- [ ] **AC26** **baseUrl 内网 IP 拦截**（P1-10）：`base_url` host 解析（所有 A/AAAA 记录）落入 `127.0.0.0/8`、`10.0.0.0/8`、`172.16.0.0/12`、`192.168.0.0/16`、`169.254.0.0/16`（含云 metadata）、`100.64.0.0/10`、`0.0.0.0/8`、`::1`、`fc00::/7`、`fe80::/10`、IPv4-mapped IPv6 → 400 拒绝
- [ ] **AC27** **baseUrl 端口 + 重定向**（P1-10）：非白名单端口（默认仅 80/443）→ 400；LLM HTTP 客户端 `followRedirects=false`（防 302 跳内网绕过校验）
- [ ] **AC28** **baseUrl 编码/形态绕过**（P1-10）：URL 编码（`%30%2e` 等）、`localhost`/`*.local`/`*.internal`、IP 字面量内网段、十进制/八进制 IP（`2130706433`）→ 校验前先归一化解码，全部拒绝
- [ ] **AC29** **全 disabled fallback**（R1）：用户有 BYOK 配置但全部 `status=0` → chat fallback yml + WARN 日志 + `llm.byok.fallback{reason=all_disabled}` 计数；区别于"从未配置"（无行，fallback 无 warn/计数）
- [ ] **AC30** **用户生命周期联动**（R2）：用户 delete → `llm_config` 逻辑删除（`deleted=1`，admin GET 仍可审计）+ `invalidateUser`（缓存+熔断器清）；用户 disable → `invalidateUser`（llm_config 保留）；Spring 事件驱动，不轮询
- [ ] **AC31** **asyncClose 监控**（R3）：close 异常 → `llm.byok.close.errors` counter + WARN（不抛）；池 `CallerRunsPolicy`；`@PreDestroy awaitTermination(30s)` 排空 close 任务
- [ ] **AC32** **部分索引 upsert**（R4）：upsert 用 `ON CONFLICT (...) WHERE deleted=0`（谓词匹配 `uk_llm_config_user_model`）；软删（deleted=1）后重建同 model → 新行 INSERT（旧行保留）；禁用 MyBatis-Plus 默认 `insertOrUpdate`

## 决策结论（Q1–Q6，已与用户对齐）

| # | 决策 | 结论 |
|---|---|---|
| Q1 | entity 命名 | `LlmModelConfig`（避免与 `LlmConfig` record 冲突） |
| Q2 | userId 透传 | 显式参数（方案 A），后台任务传 null 走系统级 |
| Q3 | BYOK 不可用是否回落系统级 | 是（`resolveChain` 已含此语义） |
| Q4 | candidateId 命名空间 | 用户级 `u:{userId}:{modelCode}`，避免熔断器 key 撞车 |
| Q5 | seed 策略 | 仅 DB 系统级为空时灌一次 yml，之后 DB 为准 |
| Q6 | master-key 注入 | `app.llm.crypto.master-key`，env 提供，启动 fail-fast 校验 |
| Q7 | 配置生效实现 | 方案 b：**同步落库 + lazy cache-aside**（砍 eager reload——前端切换天然延迟，即时生效无价值）；upsert 同步落库 + invalidate，下次请求 lazy 构建缓存 |
| Q8 | 写入权限模型 | **owner-only 写**：模型配置仅 owner 本人可更改，admin 只读（运维/审计），无 admin 代管写入（design §12） |
| Q9 | baseUrl SSRF 防护 | 协议/端口白名单 + 内网 IP 黑名单（含云 metadata 169.254.169.254）+ 禁重定向 + 编码归一化；提交时 `BaseUrlValidator` fail-fast（design §13） |

## Notes

- 技术设计见 [design.md](./design.md)；执行计划见 [implement.md](./implement.md)。
- 阶段 A（Step 1–6 CRUD 闭环）可独立验证/合并；阶段 B（Step 7–12 SPI 动态化）依赖阶段 A。
