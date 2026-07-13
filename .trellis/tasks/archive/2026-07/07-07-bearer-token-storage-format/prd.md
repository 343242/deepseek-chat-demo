# Bearer Token 加密存储格式优化 — cipher+iv 双列替代 String 简化版

## Goal

修正 `McpClientFactory.decryptBearerToken` 当前的简化实现：cipher 字节流 UTF-8 编码 + IV 固定空 byte[0]，不符合 `SecretCipher.CipherText(byte[], byte[])` 契约。改为标准的 cipher + iv 双字段存储（或单字段 base64 拼接）。

## User Value

- **正确性**：当前简化版丢失 IV（固定空），违反 GCM 安全性（同明文同 key 产生同密文）
- **可维护性**：cipher/iv 双字段与 LLM BYOK 的 `api_key_cipher` + `api_key_iv` 模式对齐

## Confirmed Facts

1. **当前实现**（`McpClientFactory.decryptBearerToken`）：
   ```java
   return secretCipher.decrypt(bearerTokenStored.getBytes(UTF_8), new byte[0]);  // IV 固定空
   ```
2. **SecretCipher 契约**：`encrypt(String) → CipherText(byte[] cipher, byte[] iv)`，IV 12B 随机
3. **DB 表**：`mcp_server_config.bearer_token_encrypted` 单 TEXT 字段（V17）
4. **对照**：LLM BYOK 用 `api_key_cipher` + `api_key_iv` 双 byte[] 列（V16）

## Requirements

### R1: 存储 schema 调整
- **方案 A（推荐）**：单 TEXT 字段存 base64 拼接 `<base64(cipher)>:<base64(iv)>`，无表结构改动
- **方案 B**：拆为 `bearer_token_cipher` + `bearer_token_iv` 双 BYTEA 列（V18 迁移）
- **决策**：方案 A（无迁移，向后兼容）

### R2: McpClientFactory 改造
- `decryptBearerToken(String stored)`：拆 `:` → base64 decode → `SecretCipher.decrypt(cipher, iv)`
- `McpAdminService.encryptToken(String plain)`：`SecretCipher.encrypt(plain)` → `base64(cipher) + ":" + base64(iv)`

### R3: 兼容性
- 旧的固定空 IV 数据无法解密（密文格式不兼容）——本期 MCP admin 刚上线，无生产数据，直接清空 `bearer_token_encrypted` 字段或要求 ADMIN 重新设置 token

## Acceptance Criteria

- [ ] `McpClientFactory.decryptBearerToken` 用标准 `SecretCipher.decrypt(cipher, iv)`
- [ ] `McpAdminService.encryptToken` 输出 `<base64(cipher)>:<base64(iv)>` 格式
- [ ] 单元测试：encrypt → decrypt round-trip
- [ ] 单元测试：旧格式（纯 cipher UTF-8）解密失败时不抛 fatal（log warn + 返回 null）

## Out of Scope

1. V18 迁移（方案 A 不需要）
2. 数据迁移工具（无生产数据）

## Notes

- base branch: `agentic-rag-dev`
- 改动量：~3 文件（McpClientFactory / McpAdminService + 测试）

## Requirements

- TBD

## Acceptance Criteria

- [ ] TBD

## Notes

- Keep `prd.md` focused on requirements, constraints, and acceptance criteria.
- Lightweight tasks can remain PRD-only.
- For complex tasks, add `design.md` for technical design and `implement.md` for execution planning before `task.py start`.
