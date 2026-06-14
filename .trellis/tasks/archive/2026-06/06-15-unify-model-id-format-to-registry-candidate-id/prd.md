# PRD: 统一模型 ID 格式为 Registry 候选 ID（强制 breaking change）

> Task: `06-15-unify-model-id-format-to-registry-candidate-id`
> Status: planning
> Owner: instant
> Created: 2026-06-15
> Predecessors:
> - `06-14-decouple-spring-ai-chatclient-builder-injection`
> - `06-14-fix-chatmodeladapter-default-options-toolcallingchatoptions`
> - `06-15-fix-intent-model-id-format-application-yml`

---

## 1. 背景

项目内存在两套模型 ID 格式：
- **registry 候选 ID 格式**（`deepseek-v4-flash`，不带前缀）—— 实际被 `LlmClientRegistry` 识别
- **`provider/model` 复合格式**（`deepseek/deepseek-v4-flash`）—— 文档/历史遗留，但实际**无任何 parser 解析它**

历史上有 4 个消费者，**全部都直接当 candidate ID 用**（无 parser），但部分注释/文档误导用户用复合格式：

| 消费者 | 文档说法 | 实际行为 |
|---|---|---|
| `IntentClassifier.intentModel` | （未说明） | `llmRegistry.get(id)` 直查 ✅ 已修 |
| `RagRetrievalProperties.queryRewriteModel` | ❌ 注释说"复合格式 provider/model" | `resolver.resolve(id)` → `llmRegistry.get(id)` 直查，注释错误 |
| `ChatServiceImpl.requestedCandidateId`（来自 `request.model`） | API 文档说"复合格式" | `return model;` 直传，**与 `client.candidateId()` 永远不相等 → `isFallback` 永远 true**（潜在 bug） |
| `application.yml app.chat.fallback.default-chain/chains` | （无说明） | **死配置**，无 Java 类读取 |

**死配置** `app.chat.fallback.default-chain`（line 18-31）grep 全代码库无 `ChatFallbackProperties` 类。

## 2. 目标

用户决策：**完整统一为 registry 候选 ID 格式 + 删除死配置**（breaking change）。

1. 消除所有 `provider/model` 复合格式的引用，统一用 registry 候选 ID（`deepseek-v4-flash` 不带前缀）
2. 删除死配置 `app.chat.fallback.default-chain` + `chains`
3. 修正误导性注释（`RagRetrievalProperties.java:25`）
4. 修正 API 文档（`docs/API-DOCS.md` 8 处）
5. 加 fail-fast 校验：`ChatServiceImpl.resolveCandidateId` 收到带 `/` 的 model 直接抛错（避免静默 `isFallback=true` bug）
6. 文档化 breaking change（前端调用方需同步）

## 3. 非目标

- ❌ **不动 `application.yml app.chat.candidates` block**（line 32-45）：这是动态模型选择器配置（`DynamicModelSelector + ProbeStreamHandler`），是另一套机制（probe 健康检查），不是死配置
- ❌ **不动 `application.yml app.chat.fallback.enabled` / `max-retries`**（line 15-17）：保留作为通用 fallback 开关（虽然目前无 Java 读取，但概念上属于配置项，不在本期范围）
- ❌ **不动 `application-dev.yml`**：在 `.gitignore`，不进 git；但 PRD §5.4 提醒用户本地同步改
- ❌ **不实现 `provider/model` parser 兼容层**：用户明确选择"强制 registry ID"，不留兼容
- ❌ **不改 `compositeId` / `modelId` 字段名**：API 字段名保持不变，只是值的格式从复合 ID 改为 registry ID（避免更大 breaking change）

---

## 4. 改动清单

### 4.1 `src/main/resources/application.yml`

**删除 line 18-31**（`fallback.default-chain` + `fallback.chains`）：

```diff
     fallback:
       enabled: ${CHAT_FALLBACK_ENABLED:true}
       max-retries: ${CHAT_FALLBACK_MAX_RETRIES:3}
-      default-chain:
-        - deepseek/deepseek-v4-flash
-        - zhipu/glm-4.7-flash
-        - minimax/MiniMax-M2.1
-      chains:
-        deepseek/deepseek-v4-flash:
-          - zhipu/glm-4.7-flash
-          - minimax/MiniMax-M2.1
-        zhipu/glm-4.7-flash:
-          - deepseek/deepseek-v4-flash
-          - minimax/MiniMax-M2.1
-        minimax/MiniMax-M2.1:
-          - deepseek/deepseek-v4-flash
-          - zhipu/glm-4.7-flash
     candidates:
       ...
```

### 4.2 `src/main/java/com/smart/rag/rag/config/RagRetrievalProperties.java`

**修正 line 25 注释**：

```diff
-        /** 查询改写使用的模型（复合格式 provider/model，如 deepseek/deepseek-chat），null 使用全局默认 */
+        /** 查询改写使用的模型 ID（必须为 registry 候选 ID，如 deepseek-v4-flash），null 使用全局默认 */
         String queryRewriteModel,
```

### 4.3 `src/main/java/com/smart/rag/chat/service/impl/ChatServiceImpl.java`

**加 fail-fast 校验** line 164-170：

```java
private String resolveCandidateId(ChatRequest request) {
    String model = request.model();
    if (model != null && !model.isBlank()) {
        if (model.contains("/")) {
            throw new IllegalArgumentException(
                "Invalid model format: '" + model + "'. Expected registry candidate ID (e.g. 'deepseek-v4-flash'), "
                + "not provider/model compound format. See docs/API-DOCS.md for valid candidate IDs.");
        }
        return model;
    }
    return llmRegistry.getDefault(LlmCapability.CHAT).candidateId();
}
```

### 4.4 `src/main/java/com/smart/rag/chat/dto/ChatRequest.java`

**加字段注释**（如果当前没有 `@Schema` 注释）：

```java
/**
 * 模型候选 ID（registry candidate ID），如 deepseek-v4-flash。
 * 不接受 provider/model 复合格式（如 deepseek/deepseek-v4-flash）。
 */
String model,
```

### 4.5 `docs/API-DOCS.md` 8 处替换

| 行 | 当前 | 改为 |
|---|---|---|
| 281 | `"compositeId": "deepseek/deepseek-v4-flash"` | `"compositeId": "deepseek-v4-flash"` |
| 297 | `"compositeId": "minimax/MiniMax-M2.1"` | `"compositeId": "minimax-M2.1"` ⚠️ 待确认 registry 实际 ID |
| 323 | `"model": "deepseek/deepseek-v4-flash"` | `"model": "deepseek-v4-flash"` |
| 349 | `> - **复合格式** \`${providerId}/{modelId}\`：精确路由，如 \`zhipu/glm-5.1\`、\`minimax/MiniMax-M2.1\`` | `> - **候选 ID** \`${candidateId}\`：精确路由，如 \`deepseek-v4-flash\`、\`zhipu-glm-4-7-flash\`` |
| 435, 452, 492, 524 | `"modelId": "deepseek/deepseek-v4-flash"` | `"modelId": "deepseek-v4-flash"` |

⚠️ **关键澄清点**：API 文档当前 `compositeId` / `modelId` 字段名暗示"复合"语义。本任务**不改字段名**（只改值），但需要在 PRD §8 决策中说明：保留字段名是为了 API 兼容性，但值的格式已变。如果有 API 设计层面的歧义，单独任务跟进。

### 4.6 待确认事项（PRD §8）

1. **registry 候选 ID 的实际值**：API 文档里的 `minimax/MiniMax-M2.1` / `zhipu/glm-4.7-flash` 在 registry 里注册的实际 ID 是什么？需要从 `application-dev.yml` 的 candidates 块读取（PRD §5.1）。本 PRD 假设是 `minimax-M2.1` / `zhipu-glm-4.7-flash`，但实际值要确认。
2. **`compositeId` 字段名是否改名**：本 PRD 不改，但用户可能想改。

---

## 5. 调查与准备

### 5.1 调查 registry 候选 ID 全集

读 `application-dev.yml` 的 `app.llm.providers.{provider}.chat.candidates[].id`，列出所有候选 ID 作为 API 文档替换的依据。例：

```bash
grep -A1 'id:' src/main/resources/application-dev.yml | grep 'id:' | awk -F'"' '{print $2}' | sort -u
```

### 5.2 调查 `app.chat.fallback.enabled` / `max-retries` 的读取者

虽然这两个字段当前没有 Java 读取（grep 无结果），但需要确认：
- 是否有未来计划的代码引用？
- 是否在 README / 其他文档中提到？

如果完全没有引用，可考虑一并删除（但本 PRD 保守起见保留）。

### 5.3 调查 `application-stable.yml` / `application-prod.yml` 的 candidates

确认其他 profile 的候选 ID 格式是否一致（不带前缀），避免 stable/prod 也有同样问题。

### 5.4 提醒用户：本地 `application-dev.yml` 同步改

dev yml 在 gitignore，不在 git。用户本地文件如果有 `query-rewrite-model: deepseek/deepseek-v4-flash` 之类的配置，需要同步改为不带前缀的格式。

---

## 6. 验证策略

### 6.1 启动验证

- [ ] 应用正常启动（删除死配置后无 binding 错误）
- [ ] 启动日志中 registry 加载所有候选正常

### 6.2 功能验证（P0）

**回归原报错请求**（使用新格式）：
```json
{
  "model": "deepseek-v4-flash",        // ← 改成 registry ID
  "enableThinking": false,
  "message": "你好",
  "ragEnabled": false,
  "mode": "SIMPLE"
}
```

预期：
- [ ] 不报错
- [ ] `isFallback = false`（验证潜在 bug 修复）
- [ ] 响应中 `candidateId` 是用户传的 `deepseek-v4-flash`

**Fail-fast 验证**（用旧格式）：
```json
{
  "model": "deepseek/deepseek-v4-flash",   // ← 旧格式
  ...
}
```

预期：
- [ ] 立即抛 `IllegalArgumentException`，错误信息明确告知"使用 registry candidate ID"
- [ ] 走 `GlobalExceptionHandler.handleIllegalArgument` → 返回 400

**Agent 模式 + RAG 验证**：
```json
{
  "model": "deepseek-v4-flash",
  "message": "说明书如何对电脑充电",
  "ragEnabled": true,
  "mode": "AGENT"
}
```

预期：
- [ ] IntentClassifier 用 `intent-model: deepseek-v4-flash` 正常分类
- [ ] 不再 fallback 到 DEEP_RETRIEVAL confidence=0.0（如果分类模型工作正常）

### 6.3 单测验证（P1）

新增 `ChatServiceImplResolveCandidateIdTest`：
- [ ] `resolveCandidateId(request with model="deepseek-v4-flash")` → `"deepseek-v4-flash"`
- [ ] `resolveCandidateId(request with model="deepseek/deepseek-v4-flash")` → 抛 `IllegalArgumentException`
- [ ] `resolveCandidateId(request with model=null)` → 返回 registry 默认候选 ID
- [ ] `resolveCandidateId(request with model="")` → 同上

### 6.4 GitNexus 验证

- [ ] `gitnexus_impact({target: "resolveCandidateId", direction: "upstream"})` 确认调用方（ChatServiceImpl.chat / chatStream）无 break
- [ ] `gitnexus_detect_changes()` 验证影响面

### 6.5 文档验证

- [ ] `grep -rn 'deepseek/deepseek-v4-flash\|zhipu/glm\|minimax/MiniMax' docs/ README.md` → 0 匹配
- [ ] `grep -rn '复合格式.*provider/model' src/main/java/ docs/` → 0 匹配

---

## 7. 风险与缓解

| 风险 | 概率 | 影响 | 缓解 |
|---|---|---|---|
| 前端调用方使用旧 `provider/model` 格式 → 启动后所有请求 400 | 高 | 高 | fail-fast 错误信息明确；通知前端团队；PR 描述里列 breaking change |
| `application-stable.yml` / `application-prod.yml` 也有 `provider/model` 格式 | 中 | 中 | §5.3 调查确认；如有则一并改 |
| `compositeId` / `modelId` 字段名与新值语义不一致（字段名说"复合"但值是单段） | 中 | 低 | 字段名暂不改（§3 非目标）；可单独任务跟进 |
| 删除死配置 `default-chain` 后某天有人要接通 fallback 链找不到参考 | 低 | 低 | git 历史保留；任务 commit message 说明删除原因 |
| `application-dev.yml` 本地未同步改导致本地启动失败 | 中 | 中 | §5.4 提醒；fail-fast 错误信息明确 |

---

## 8. 决策记录（2026-06-15）

1. **完整统一为 registry 候选 ID**（用户选 breaking change 路径），不留 `provider/model` 兼容层
2. **删除死配置**（`default-chain` + `chains`），保留 `fallback.enabled` + `max-retries`（保守起见）
3. **fail-fast 校验**放在 `resolveCandidateId`：收到带 `/` 的 model 立即抛错，避免静默 `isFallback=true` bug
4. **API 字段名 `compositeId` / `modelId` 不改**：值改了但字段名保留，避免更大 breaking change；字段名调整可后续单独任务跟进
5. **不动 `application.yml app.chat.candidates` block**：是 probe 健康检查的另一套机制，非死配置
6. **不动 dev yml**：在 gitignore，但 PRD §5.4 提醒用户同步改

---

## 9. 实施步骤（jsonl 雏形）

1. 调查 `application-dev.yml` registry 候选 ID 全集（PRD §5.1）
2. 调查其他 profile（stable/prod）是否有 `provider/model` 格式（PRD §5.3）
3. 修改 `application.yml`：删除 line 18-31 死配置
4. 修改 `RagRetrievalProperties.java:25`：注释更新
5. 修改 `ChatServiceImpl.resolveCandidateId`：加 fail-fast 校验
6. 修改 `ChatRequest.java`：加字段注释（如已存在则更新）
7. 修改 `docs/API-DOCS.md` 8 处：替换为 registry 候选 ID 格式
8. 新增 `ChatServiceImplResolveCandidateIdTest`：4 个断言点（PRD §6.3）
9. `mvn compile` + `mvn test`
10. 启动应用，按 §6.2 跑回归（含 fail-fast 验证）
11. `gitnexus_detect_changes` + `gitnexus_impact` 验证
12. 文档检查（§6.5 grep 0 匹配）
13. 更新 spec：在 `.trellis/spec/backend/llm-spi.md` 加一节"模型 ID 格式契约"
14. Commit + Push + Archive

---

## 10. Breaking Change 通告（Commit Message 草稿）

```
BREAKING CHANGE: 模型 ID 统一为 registry 候选 ID 格式

移除所有 provider/model 复合格式支持。所有 yml 配置 / API 请求 /
API 响应中的模型 ID 必须使用 registry 候选 ID（如 deepseek-v4-flash），
不再接受 deepseek/deepseek-v4-flash 这种复合格式。

影响：
- API 请求 body `model` 字段：deepseek/deepseek-v4-flash → deepseek-v4-flash
- API 响应字段 `compositeId` / `modelId` 值同步改格式
- 配置项 `app.agent.intent-model` / `app.rag.query-rewrite-model` 同步
- 删除死配置 `app.chat.fallback.default-chain` / `chains`（无 Java 读取）

迁移：
- 前端调用方：把 model 字段的 provider/ 前缀去掉
- 服务端配置：把 yml 里的 provider/ 前缀去掉
- 如果传入旧格式，服务端 fail-fast 返回 400 + 明确错误信息

修复：
- IntentClassifier 找不到候选导致 fallback 到 DEEP_RETRIEVAL（已修，上个任务）
- request.model 带 provider/ 前缀导致 isFallback 永远 true（本次修复）
- RagRetrievalProperties 注释错误（本次修复）
- application.yml 死配置（本次清理）
```
