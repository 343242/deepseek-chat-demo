# PRD: Fix LLM SPI P0 Batch 1 — Startup & Type Safety

## 背景

代码审查报告 `docs/reviews/2026-06-12-llm-unified-spi-code-review.md` 中识别出 11 个 P0 问题。经核查确认 10 个真实存在，P0-11 为误报。本任务修复第一批：启动阻塞和类型安全问题。

## 修复范围

### P0-5: ProviderConfig.isAvailable() 拒绝无 apiKey 的 provider

**文件**: `src/main/java/com/smart/rag/infrastructure/llm/config/ProviderConfig.java:47-50`

**现状**: `apiKey != null && !apiKey.isBlank()` 要求 apiKey 必须非空，ollama 等本地模型被静默跳过。

**修复**: 改为 `(apiKey == null || !apiKey.isBlank())`

```java
public boolean isAvailable() {
    return url != null && !url.isBlank()
        && (apiKey == null || !apiKey.isBlank());
}
```

---

### P0-7: ChatRequest.fromDefaults() 整数 YAML 值触发 ClassCastException

**文件**: `src/main/java/com/smart/rag/infrastructure/llm/ChatRequest.java:34-39`

**现状**: 直接强转 `(Double) defaults.get("temperature")` 等。Spring Boot 绑定 `temperature: 1` 为 Integer，强转失败。

**修复**: 添加 `toDouble()` 和 `toInt()` 辅助方法，使用 `Number` 中转：

```java
private static Double toDouble(Map<String, Object> m, String key) {
    Object v = m.get(key);
    return v instanceof Number n ? n.doubleValue() : null;
}

private static Integer toInt(Map<String, Object> m, String key) {
    Object v = m.get(key);
    return v instanceof Number n ? n.intValue() : null;
}
```

`fromDefaults()` 改为调用这些方法。

---

### P0-6: 无启动配置验证 — default-model 引用不存在的候选时才报错

**文件**: `src/main/java/com/smart/rag/infrastructure/llm/registry/LlmClientFactory.java:122-126`

**现状**: `group.getDefaultModel()` 直接存入 `defaultClients` map，不验证是否存在于 `clientsById`。

**修复**: 在 `buildSnapshot()` 中，设置 defaultClients 之前验证：

```java
String defaultId = group.getDefaultModel();
if (defaultId != null) {
    if (!clientsById.containsKey(defaultId)) {
        throw new IllegalStateException(
            cap + ".default-model '" + defaultId + "' references unknown candidate");
    }
    defaultClients.put(cap, defaultId);
}
```

---

## 不在范围内

- P0-1 ~ P0-4（重试语义/熔断器）→ 第二批
- P0-8 ~ P0-10（资源泄漏/ToolCalling）→ 第二批
- P1/P2 问题 → 第三批

## 验收标准

1. `ProviderConfig.isAvailable()` 在 `apiKey == null` 且 `url` 有效时返回 `true`
2. `ChatRequest.fromDefaults()` 对 YAML 整数值（如 `temperature: 1`）和浮点值均能正确解析，不抛 ClassCastException
3. `LlmClientFactory.buildSnapshot()` 在 default-model 引用不存在的候选时启动即报错（fail-fast）
4. 项目编译通过（`mvn compile -q`）
5. 无新增 warning
