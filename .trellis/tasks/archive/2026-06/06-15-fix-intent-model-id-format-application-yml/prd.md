# PRD: 修复 application.yml intent-model ID 格式

> Task: `06-15-fix-intent-model-id-format-application-yml`
> Status: planning
> Owner: instant
> Created: 2026-06-15

---

## 1. 背景

`Agent` 模式请求时报错：
```
WARN IntentClassifier - Intent classification failed (attempt 0/1/2): Intent classification LLM call failed
WARN IntentClassifier - Intent classification failed after 2 retries, falling back to DEEP_RETRIEVAL
INFO AgentModeStrategy - Agent intent classified: intent=DEEP_RETRIEVAL, confidence=0.0
```

## 2. 根因

`application.yml:58`：
```yaml
intent-model: ${AGENT_INTENT_MODEL:deepseek/deepseek-v4-flash}
```

实际 registry 注册的候选 ID（`application-dev.yml:123`）：
```yaml
- id: deepseek-v4-flash          # 不带 provider/ 前缀
  provider: deepseek
  model: deepseek-v4-flash
```

`IntentClassifier.resolveChatClient()` line 165 调用：
```java
ChatCapable chatCapable = llmRegistry.get(intentCandidateId, ChatCapable.class);
// intentCandidateId = "deepseek/deepseek-v4-flash"
```

→ registry 找不到 → 抛 `RemoteException("No client registered for candidate: deepseek/deepseek-v4-flash")`
→ 被 `doClassify` 包成 `ServiceException`
→ 被 `classify` 静默 fallback 到 DEEP_RETRIEVAL（confidence=0.0）
→ fallback 后虽然挂了 9 个 DEEP_RETRIEVAL 工具，但 system prompt 引导 LLM 行为受 confidence 影响，且每次都跑这条降级路径开销大

## 3. 为什么 fallback chain 的 provider/model 格式能工作但 intent-model 不能

`RagRetrievalProperties.java:25` 注释明确说"复合格式 `provider/model`"——说明项目里**有 ID 解析层**处理这种格式（query rewrite 用），且 `app.chat.fallback.default-chain` 也走类似路径。

但 `IntentClassifier` 直接调 `llmRegistry.get(intentModel, ...)`，**没有解析层**——intent-model 必须用 registry 候选 ID 原始格式（`deepseek-v4-flash` 不带前缀）。

## 4. 目标与非目标

### 4.1 目标

修改 `application.yml:58` 的 intent-model 默认值，使其匹配 registry 候选 ID 格式：
- **before**: `deepseek/deepseek-v4-flash`
- **after**: `deepseek-v4-flash`

### 4.2 非目标

- ❌ **不改 `app.chat.fallback.default-chain` / `chains`**（line 18-31）——这些是 `provider/model` 复合格式，由独立的解析层处理，能正常工作；动了会破坏 fallback 链
- ❌ **不改 `RagRetrievalProperties.queryRewriteModel` 相关配置**——同上，已经走复合格式解析层
- ❌ **不改 `IntentClassifier` 代码**——本次只修配置；若想未来支持复合格式（统一 ID 体系），单独任务跟进
- ❌ **不改 `application-dev.yml`**——dev yml 在 `.gitignore` 里，不进 git，本期不动

---

## 5. 设计方案

### 5.1 修改清单

唯一改动：`src/main/resources/application.yml:58`

```diff
   agent:
     enabled: ${AGENT_ENABLED:true}
-    intent-model: ${AGENT_INTENT_MODEL:deepseek/deepseek-v4-flash}
+    intent-model: ${AGENT_INTENT_MODEL:deepseek-v4-flash}
```

### 5.2 兼容性

| 场景 | 影响 |
|---|---|
| 开发者未设 `AGENT_INTENT_MODEL` 环境变量 | 用新默认值 `deepseek-v4-flash`，registry 能找到 → IntentClassifier 正常工作 |
| 开发者已设 `AGENT_INTENT_MODEL=deepseek/deepseek-v4-flash` | 仍然报错——需开发者同步更新环境变量为本任务新默认值 |
| 生产环境（`application-prod.yml`） | prod yml 不含 intent-model 配置（继承 base）；若环境变量已设新格式则正常，旧格式仍报错 |

### 5.3 部署期提醒

- 本地 `.env` / IDE Run Configuration / 部署环境变量若有 `AGENT_INTENT_MODEL` 旧值，需同步更新
- 检查 README / 部署文档是否引用了 `deepseek/deepseek-v4-flash` 作为示例

---

## 6. 验证策略

### 6.1 启动验证

- [ ] 应用正常启动
- [ ] 启动日志中 IntentClassifier 不再抛 `RemoteException`（如果开了 DEBUG）

### 6.2 功能验证（P0）

复现原报错请求：
```json
{
  "model": "deepseek/deepseek-v4-flash",
  "enableThinking": false,
  "message": "你好,我想知道说明书中如何对电脑进行充电",
  "ragEnabled": true,
  "conversationId": "...",
  "mode": "AGENT"
}
```

预期：
- [ ] 不再有 `Intent classification failed after 2 retries` 日志
- [ ] `Agent intent classified` 显示真实分类结果（如 `intent=RETRIEVAL` 或 `DEEP_RETRIEVAL`），`confidence > 0`
- [ ] 工具按意图正确暴露（RETRIEVAL 挂 5 个 / DEEP_RETRIEVAL 挂 9 个）
- [ ] LLM 调用检索工具，触发 RAG 检索（如果意图判定需要检索）

### 6.3 兼容性验证

- [ ] 不设 `AGENT_INTENT_MODEL` 环境变量时走新默认值，IntentClassifier 正常
- [ ] 设置 `AGENT_INTENT_MODEL=qwen-plus` 时用 qwen-plus 候选做意图分类，正常
- [ ] 老格式 `AGENT_INTENT_MODEL=deepseek/deepseek-v4-flash` 仍然报错（确认 fallback chain 没受影响——这条不变）

### 6.4 GitNexus 验证

- [ ] `gitnexus_detect_changes()` 显示 1 个文件改动，无符号受影响（yml 不是 Java 符号）

---

## 7. 风险与缓解

| 风险 | 概率 | 影响 | 缓解 |
|---|---|---|---|
| 开发者本地环境变量 `AGENT_INTENT_MODEL` 还是旧格式 | 中 | 中（启动后 agent 仍报错） | PRD §5.3 部署期提醒；README 检查 |
| `intent-model` 默认值 `deepseek-v4-flash` 在某些环境（如 stable / prod）注册的候选 ID 不同 | 低 | 中 | 各 profile yml 需独立验证；本任务只改 base application.yml，dev/stable 各自的 candidates 决定可用 ID |
| 未来有人加 `provider/model` 解析层到 IntentClassifier | 低 | 低（破坏单一来源真相） | 在 `.trellis/spec/backend/llm-spi.md` 补一条规范——但本任务不引入 spec 更新（改动太小不值得） |

---

## 8. 决策记录（2026-06-15）

1. **窄义修复**：只改 intent-model 一处默认值，不动 fallback chain（避免破坏正在工作的 ID 解析层）
2. **不引入 ID 格式统一任务**：fallback chain 与 intent-model 走不同的 ID 解析路径是设计缺陷（应统一），但本任务只做最小修复；统一任务另开
3. **不修改 IntentClassifier 代码**：本次只修配置，最小风险

---

## 9. 实施步骤（jsonl 雏形）

1. 修改 `src/main/resources/application.yml:58`：`deepseek/deepseek-v4-flash` → `deepseek-v4-flash`
2. （可选）检查 `README.md` 是否引用旧格式，同步更新
3. `mvn compile`（应该不受影响，yml 不是 Java）
4. 启动应用，按 §6.2 跑回归
5. `gitnexus_detect_changes()` 验证影响面
6. 提交（单 commit，message 描述意图分类失败的根因）

---

## 10. 参考链接

- IntentClassifier 源码：`src/main/java/com/smart/rag/agent/intent/IntentClassifier.java:165`
- LlmClientRegistry.get API：`src/main/java/com/smart/rag/infrastructure/llm/registry/LlmClientRegistry.java`
- dev 候选 ID 注册：`src/main/resources/application-dev.yml:115-140`（注意：dev yml 在 `.gitignore`，不在 git）
