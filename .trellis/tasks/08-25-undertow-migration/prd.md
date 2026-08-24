# Undertow 迁移替代 Tomcat

## Goal

将 Web 容器从 Tomcat（`spring-boot-starter-web` 传递引入）切换为 Undertow
（`spring-boot-starter-undertow`，Boot 3.5.14 BOM 管理版本 2.3.24.Final）。
收益：更低内存占用、SSE 长连接场景更轻的线程模型。

## 前置调研结论（2026-08-25）

- 全库无 Tomcat 专属代码/配置；`server.*` 配置（port / forward-headers-strategy /
  shutdown: graceful）全部容器无关。
- 四条 SSE 链路全部走 Servlet 异步（`SseEmitter`），无 WebSocket；MCP 仅作客户端。
- 测试无 `@SpringBootTest`（不起真实容器），单测对容器替换无感。
- 容器敏感点：`JwtAuthenticationFilter` 的 ASYNC/ERROR dispatch 快照恢复逻辑
  （SSE 收尾时 async dispatch 重走过滤链，语义由容器实现）。

## 变更范围

仅 `pom.xml`：

1. `spring-boot-starter-web` 增加 exclusion `spring-boot-starter-tomcat`
   （保留现有 logging exclusion）。
2. 新增依赖 `spring-boot-starter-undertow`（版本由 Boot BOM 管理）。

不改任何 Java 代码、不改 application.yml。

## 验证计划（按 API 链路分组）

验证环境：本地 dev profile（端口 10808），复用运行中的 docker compose 基础设施
（postgres / redis / minio，均已 healthy）。

### P0 容器敏感面（必须实测通过）

1. **启动与依赖面**
   - 启动日志出现 `Undertow started on port 10808`（dev profile）。
   - `mvn dependency:tree` 确认 classpath 无 `tomcat-embed-*`，有 `undertow-*`。
2. **认证链路（Spring Security 过滤链 + Redis）**
   - `GET /api/auth/captcha`：匿名可访问，返回 captchaId + answer
     （dev 配置 `captcha.expose-answer: true`）；Redis 限流依赖 getRemoteAddr()。
   - 注册 → 登录：`POST /api/auth/register` → `POST /api/auth/login`，
     拿到 Set-Cookie（access/refresh token）。
   - 携带 cookie 访问受保护接口（如 `GET /api/models`）返回 200。
   - 无 token 访问受保护接口返回 401（JSON 格式，非容器默认错误页）。
3. **SSE 流式链路（4 条，核心风险区）**
   - `POST /api/chat/stream`：认证 + 连接建立 + chunk 逐帧实时写出
     （记录首帧延迟，确认无缓冲堆积）+ 正常完成收尾。
   - `POST /api/chat/stream/cancel`：软取消路径可调用。
   - `GET /api/documents/events`：文档状态长连接可建立、客户端断开不报错。
   - `GET /api/evaluation/runs/{runId}/events`、
     `GET /api/evaluation/datasets/generate/{jobId}/events`：
     不存在的 ID 走 bridgeTerminated 立即完成路径。
   - **SSE 收尾后检查应用日志**：无 "Access Denied"、无匿名降级告警
     （验证 JwtAuthenticationFilter 的 ASYNC dispatch 快照恢复在 Undertow 上成立）。
4. **错误响应格式**
   - 404 路由、400 参数校验 → 返回 Spring `GlobalExceptionHandler` 的 JSON，
     非 Undertow 默认错误页。

### P1 常规请求面（抽样）

5. 普通 JSON API：`GET /api/models`、`GET /api/auth/me`、`GET /api/usage/records`。
6. 字节流 + Range：`GET /api/documents/{id}/download` 发 Range 请求，
   期望 206 Partial Content（应用层 ResolveRanges 写出）。
7. Multipart 上传：`POST /api/documents/upload`（容器 multipart 解析）。
8. Actuator：`GET /actuator/health`（UP）、`GET /actuator/prometheus`（200）。

### P2 回归确认

9. `mvn test` 全量单测通过。
10. 优雅停机：SIGTERM → in-flight 请求/SSE tail 在 30s 内收尾后退出
    （`server.shutdown: graceful` + `spring.lifecycle.timeout-per-shutdown-phase: 30s`，
    Spring 层实现，预期容器无关）。

## Acceptance Criteria

- [x] pom.xml 完成迁移改动，无其他文件变更（git status 仅 pom.xml + 本任务目录）
- [x] P0 全部实测通过（详见下方验证记录）
- [x] P1 抽样实测通过
- [x] `mvn test` 全绿（1834 tests, 0 failures, 4m00s）
- [x] P2 优雅停机验证通过（SIGTERM → 30s phase 超时精确生效 → 干净退出）

## 验证记录（2026-08-25 实测）

### P0

1. **启动面**：Undertow 2.3.24.Final 启动日志确认；dependency:tree 无
   `tomcat-embed-core`（保留 `tomcat-embed-el`——undertow starter 的标准 EL 供给）、
   无 logback 回流；启动 9.1s，`/actuator/health` UP。
2. **认证链路**：captcha（Redis 限流 + answer 返回）→ 注册 code 0 → 登录 Set-Cookie
   → `/api/auth/me` 200（roles/permissions 正确）；无 token → 401 JSON。
3. **SSE**：
   - `POST /api/chat/stream`：200 + text/event-stream，首帧 1574ms（含 LLM 上游），
     80 帧增量到达（帧间隔 0~98ms 自然分布，无 >3s 堆积）→ 无缓冲问题。
   - `GET /api/documents/events`：长连接保持 + `:hb` 心跳帧正常。
   - evaluation/dataset 事件流：不存在 ID 走应用层路径
     （404 为显式 `ResponseEntity.notFound().build()`；dataset 为 code 200001 错误信封）。
   - `POST /api/chat/stream/cancel`：幂等路径 `cancelled:false`。
   - **日志 0 WARN / 0 ERROR / 无 Access Denied**——SseEmitter 收尾 ASYNC dispatch
     的认证快照恢复在 Undertow 上成立（JwtAuthenticationFilter 兼容性确认）。
4. **错误格式**：未匹配路由异常为 Spring 标准
   `NoResourceFoundException`（Boot 3.2+，与容器无关），应用 catch-all 映射 code 200002；
   参数校验 → code 100002 信封。均为应用层既有行为。

### P1

5. JSON API：`/api/models`（4 模型）、`/api/auth/me`、404/401 信封格式 ✓。
6. Range：`GET /api/documents/{id}/download` + `Range: bytes=0-59` → **206**、
   恰 60 字节；完整下载 200 且 md5 与源文件一致。
7. Multipart：`POST /api/documents/upload`（text/markdown）→ code 0，
   文档进入 PROCESSING，ETL 流水线（Redis stream → FastTrack → chunks=2）正常运行。
8. Actuator：health UP、prometheus 200。

### P2

10. **优雅停机**：in-flight SSE 下 SIGTERM → 消费者 7.4s 内停止 →
    web 层等待 SSE 满 `timeout-per-shutdown-phase: 30s`（05:45:39.156 → 05:46:09.157，
    毫秒级精确）→ 强制断开 → bean 销毁（Hikari/OkHttp/Registry）→ 退出；
    SSE 客户端收到干净关闭（停在心跳帧，无错误字节）。与 application.yml
    「覆盖典型 SSE 长会话 tail；超时强制关闭」的注释语义一致。

### 已知噪音（与容器无关）

- `scripts/api-test.sh` 40 项失败系脚本与现 API 契约漂移（旧扁平 JSON + HTTP 状态码
  断言 vs 现 GlobalResponse 信封 + HTTP 恒 200 约定），非容器回归；建议后续按
  `.data.*` 解析修正脚本。
- Undertow 启动时 `UT026010: Buffer pool was not set on WebSocketDeploymentInfo`
  警告：无 WebSocket 使用，无害；如需消除可注册 WebServerFactoryCustomizer。
- 测试副作用：DB 中残留 `undertow_*` 测试用户与 docId=4 测试文档（dev 环境）。

## Notes

- 若 SSE 收尾出现 ASYNC dispatch 差异（日志 Access Denied），回滚迁移并在
  prd 记录根因。
