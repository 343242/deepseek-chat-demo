# Smart-RAG 性能测试指导文档

> 使用 Apifox 对 Smart-RAG 项目进行 API 性能/压力测试

## 目录

- [1. 概述](#1-概述)
- [2. 环境准备](#2-环境准备)
- [3. Apifox 性能测试基础](#3-apifox-性能测试基础)
- [4. 测试场景设计](#4-测试场景设计)
- [5. 分场景测试方案](#5-分场景测试方案)
- [6. 性能指标与基线](#6-性能指标与基线)
- [7. 结果分析与调优](#7-结果分析与调优)
- [8. 导出 JMeter 脚本（高级场景）](#8-导出-jmeter-脚本高级场景)

---

## 1. 概述

### 1.1 目的

通过 Apifox 内置的性能测试功能，模拟多用户并发访问，评估 Smart-RAG 各 API 模块在高负载下的：

- **吞吐量**（TPS / QPS）
- **响应时间**（平均 / P90 / 最大值）
- **错误率**
- **稳定性**（长时间运行是否出现内存泄漏、连接池耗尽等）

### 1.2 项目 API 概览

Smart-RAG 共有 **16 个 Controller、73 个 API 端点**，按模块划分：

| 模块 | 端点数 | 关键压测目标 |
|------|--------|-------------|
| Auth（认证） | 8 | 登录/刷新 Token |
| Chat（对话） | 7 | 同步聊天、SSE 流式聊天 |
| Conversation（会话） | 6 | 会话列表、消息查询 |
| Document（文档） | 8 | 文档上传、列表查询 |
| ChunkUpload（分片上传） | 5 | 大文件分片上传并发 |
| Team（团队） | 6 | 团队 CRUD |
| TeamMember（团队成员） | 6 | 成员管理 |
| TeamApproval（审批） | 3 | 审批流 |
| Usage（用量） | 3 | 统计查询 |
| Evaluation（评测） | 9 | 评测任务（需 `evaluation` profile） |

### 1.3 Apifox 性能测试特点

- **本地发起**：压力从运行 Apifox 的电脑发出（非 Apifox 云服务器），受本机硬件和网络限制
- **基于测试场景**：复用已有的自动化测试场景，无需额外编写脚本
- **支持导出 JMeter**：复杂场景可导出 `.jmx` 文件在 JMeter 中执行分布式压测
- **实时可视化**：运行时提供 TPS、响应时间、错误率的实时曲线图

---

## 2. 环境准备

### 2.1 安装 Apifox

下载最新版 Apifox 桌面客户端（性能测试功能需要桌面端）：

- 官网：https://apifox.com
- 要求版本：**v2.5.0+**（支持性能测试功能）

### 2.2 启动 Smart-RAG 后端

```bash
# 开发环境
mvn spring-boot:run -Dspring-boot.run.profiles=dev

# 或使用 docker-compose
docker-compose up -d
```

确认服务正常运行：

```bash
curl http://localhost:8080/api/auth/captcha
```

### 2.3 确认依赖服务就绪

| 服务 | 用途 | 检查方式 |
|------|------|---------|
| PostgreSQL | 主数据库 | `docker exec -it smart-rag-db pg_isready` |
| Redis | 缓存/Session | `redis-cli ping` |
| MinIO | 文件存储 | 访问 `http://localhost:9000` |
| 百炼/Rerank | 向量检索 & 重排 | 发送测试请求验证 |

### 2.4 准备测试数据

压测前需准备：

1. **测试账号**：至少 2-3 个不同角色的用户（admin、普通用户）
2. **知识库文档**：上传若干文档，确保向量检索有数据可查
3. **会话数据**：创建若干历史会话，测试列表查询性能

---

## 3. Apifox 性能测试基础

### 3.1 三步流程

```
创建测试场景 → 配置性能参数 → 运行 & 分析
```

### 3.2 创建测试场景

1. 打开 Apifox，进入项目的 **「自动化测试」** 模块
2. 点击 **「新建测试场景」**
3. 添加需要压测的接口步骤（可从左侧接口列表拖入）
4. 为每个步骤配置：
   - 请求参数（URL、Header、Body）
   - 前置/后置脚本（如提取 Token）
   - 断言（校验响应状态码、字段值）

### 3.3 配置性能参数

在测试场景右上角点击 **「性能测试」** 图标，配置：

| 参数 | 说明 | 推荐起始值 |
|------|------|-----------|
| **并发用户数** | 同时发起请求的虚拟用户数 | 10-50 |
| **运行时间** | 测试持续时间 | 1-5 分钟 |
| **Ramp-Up 时间** | 用户数从 0 增长到目标值的过渡时间 | 10-30 秒 |

### 3.4 运行测试

点击 **「运行」** 启动性能测试。Apifox 会：

1. 按 Ramp-Up 策略逐步增加并发用户
2. 每个虚拟用户按顺序执行场景中的所有步骤
3. 循环执行直到运行时间结束

### 3.5 关键指标说明

| 指标 | 含义 | 参考阈值 |
|------|------|---------|
| **总请求数** | 测试期间发出的请求总量 | — |
| **TPS（每秒请求数）** | 系统每秒处理的请求数 | 越高越好 |
| **平均响应时间** | 所有请求的平均耗时 | < 500ms（非 LLM 接口） |
| **P90 响应时间** | 90% 请求在此时间内完成 | < 1000ms |
| **最大响应时间** | 最慢请求的耗时 | 关注异常值 |
| **失败率** | 请求失败的比例 | < 1% |

---

## 4. 测试场景设计

### 4.1 按模块划分场景

建议为每个业务模块创建独立的测试场景，便于定位性能瓶颈：

| 场景编号 | 场景名称 | 覆盖模块 | 优先级 |
|---------|---------|---------|--------|
| SC-01 | 用户认证 | Auth | P0 |
| SC-02 | RAG 对话 | Chat + Conversation | P0 |
| SC-03 | 文档管理 | Document + ChunkUpload | P1 |
| SC-04 | 团队协作 | Team + TeamMember + Approval | P1 |
| SC-05 | 混合负载 | 全模块混合 | P2 |

### 4.2 前置脚本：获取认证 Token

大多数 API 需要 JWT Token。在场景的第一个步骤前添加前置脚本：

```javascript
// 前置脚本 - 获取登录 Token
const response = await axios.post('http://localhost:8080/api/auth/login', {
  username: 'perf_test_user',
  password: 'test_password'
});

const token = response.data.data.accessToken;
apt.variables.set('auth_token', token);
```

在后续步骤的 Header 中引用：

```
Authorization: Bearer {{auth_token}}
```

### 4.3 数据参数化

使用 Apifox 的 **数据集** 功能进行参数化：

| 变量名 | 用途 | 示例值 |
|--------|------|--------|
| `{{base_url}}` | 服务地址 | `http://localhost:8080` |
| `{{auth_token}}` | JWT Token | 自动提取 |
| `{{conversation_id}}` | 会话 ID | 从创建接口提取 |
| `{{document_id}}` | 文档 ID | 从上传接口提取 |
| `{{team_id}}` | 团队 ID | 从创建接口提取 |

---

## 5. 分场景测试方案

### SC-01：用户认证压测

**目标**：验证登录/Token 刷新在高并发下的稳定性

**测试步骤**：

| 步骤 | 接口 | 方法 | 说明 |
|------|------|------|------|
| 1 | `/api/auth/login` | POST | 用户登录 |
| 2 | `/api/auth/me` | GET | 获取当前用户信息 |
| 3 | `/api/auth/refresh` | POST | 刷新 Token |
| 4 | `/api/auth/logout` | POST | 登出 |

**性能参数**：

| 参数 | 轻负载 | 中负载 | 重负载 |
|------|--------|--------|--------|
| 并发用户数 | 10 | 50 | 100 |
| 运行时间 | 1 min | 3 min | 5 min |
| Ramp-Up | 5s | 15s | 30s |

**关注点**：
- JWT 签发是否有性能瓶颈
- Redis Session 管理并发能力
- 数据库连接池是否耗尽

---

### SC-02：RAG 对话压测（核心场景）

**目标**：验证 RAG 问答链路（含向量检索 + LLM 调用）的并发能力

> **注意**：LLM 调用耗时较长（通常 2-10 秒），此场景的 TPS 预期远低于纯 CRUD 接口。

**测试步骤**：

| 步骤 | 接口 | 方法 | 说明 |
|------|------|------|------|
| 1 | `/api/conversations` | POST | 创建会话 |
| 2 | `/api/chat` | POST | 发送问题（同步） |
| 3 | `/api/conversations/{id}/messages` | GET | 获取消息列表 |
| 4 | `/api/chat/stream` | POST | 发送问题（SSE 流式） |

**请求示例（chat）**：

```json
{
  "message": "什么是 RAG？",
  "conversationId": "{{conversation_id}}",
  "mode": "RAG"
}
```

**性能参数**：

| 参数 | 轻负载 | 中负载 | 重负载 |
|------|--------|--------|--------|
| 并发用户数 | 5 | 15 | 30 |
| 运行时间 | 2 min | 5 min | 10 min |
| Ramp-Up | 10s | 30s | 60s |

**关注点**：
- 向量检索（百炼 API）响应时间
- LLM API 并发限制（百炼/DeepSeek 等厂商的 Rate Limit）
- SSE 连接数是否受服务器限制
- Agent ReAct 循环的多轮工具调用性能

**特殊处理**：
- LLM 接口响应时间长，需要适当增大 Apifox 的请求超时设置（建议 30-60s）
- SSE 流式接口在 Apifox 中按普通 POST 请求测试（关注首字节时间）

---

### SC-03：文档管理压测

**目标**：验证文档上传、列表查询的并发能力

**测试步骤**：

| 步骤 | 接口 | 方法 | 说明 |
|------|------|------|------|
| 1 | `/api/documents/upload` | POST | 上传文档（multipart） |
| 2 | `/api/documents` | GET | 文档列表 |
| 3 | `/api/documents/{id}` | GET | 文档详情 |
| 4 | `/api/documents/{id}/history` | GET | 处理历史 |

**性能参数**：

| 参数 | 轻负载 | 中负载 | 重负载 |
|------|--------|--------|--------|
| 并发用户数 | 5 | 20 | 50 |
| 运行时间 | 1 min | 3 min | 5 min |
| Ramp-Up | 5s | 15s | 30s |

**关注点**：
- MinIO 文件上传并发吞吐
- 大文件分片上传（ChunkUpload）的断点续传稳定性
- 文档处理（ETL Pipeline）队列积压

---

### SC-04：团队协作压测

**目标**：验证团队管理、成员操作、审批流的并发安全性

**测试步骤**：

| 步骤 | 接口 | 方法 | 说明 |
|------|------|------|------|
| 1 | `/api/teams` | POST | 创建团队 |
| 2 | `/api/teams/{teamId}/members/{userId}` | POST | 添加成员 |
| 3 | `/api/teams/{teamId}/members` | GET | 成员列表 |
| 4 | `/api/teams/{teamId}/approvals/pending` | GET | 待审批列表 |
| 5 | `/api/teams/{teamId}/approvals/{id}/review` | POST | 审批操作 |

**关注点**：
- 并发审批操作的数据一致性
- 团队权限校验性能（数据库查询 + 缓存）

---

### SC-05：混合负载测试

**目标**：模拟真实用户行为，多模块混合并发

**测试步骤**：按比例组合以上场景

| 用户行为 | 比例 | 对应步骤 |
|---------|------|---------|
| 浏览文档列表 | 40% | `GET /api/documents` |
| 进行 RAG 对话 | 30% | `POST /api/chat/stream` |
| 查看会话历史 | 20% | `GET /api/conversations/{id}/messages` |
| 管理团队 | 10% | 团队相关接口 |

**性能参数**：

| 参数 | 值 |
|------|---|
| 并发用户数 | 50-100 |
| 运行时间 | 10-30 min |
| Ramp-Up | 60s |

---

## 6. 性能指标与基线

### 6.1 建议的基线标准

> 以下基线适用于开发/测试环境（单实例部署），生产环境需根据实际硬件调整。

| 接口类型 | 平均响应时间 | P90 响应时间 | TPS | 失败率 |
|---------|-------------|-------------|-----|--------|
| 认证（登录/刷新） | < 200ms | < 500ms | > 100 | < 0.1% |
| CRUD 查询（列表/详情） | < 300ms | < 800ms | > 50 | < 0.1% |
| CRUD 写入（创建/更新） | < 500ms | < 1000ms | > 30 | < 0.5% |
| RAG 对话（同步） | < 5s | < 10s | > 5 | < 1% |
| RAG 对话（SSE 流式） | 首字节 < 2s | 首字节 < 5s | > 5 | < 1% |
| 文档上传 | < 3s/MB | < 5s/MB | > 10 | < 0.5% |

### 6.2 压测递增策略

采用阶梯式递增，逐步发现性能拐点：

```
10 用户 × 1min → 20 用户 × 2min → 50 用户 × 3min → 100 用户 × 5min
```

每一阶记录指标，绘制「并发数 vs TPS」和「并发数 vs 响应时间」曲线，找到：

- **最佳并发数**：TPS 最高且响应时间可接受的点
- **性能拐点**：TPS 开始下降或响应时间急剧上升的并发数

---

## 7. 结果分析与调优

### 7.1 分析 Apifox 测试报告

测试结束后，在 Apifox 中查看报告：

1. 点击 **「测试报告」** 标签页
2. 选择本次性能测试的报告
3. 重点关注：
   - **可视化面板**：鼠标悬停查看各时间点的指标
   - **失败请求分析**：点击「请求失败率」查看错误分类
   - **单接口性能**：下方表格查看每个接口的独立指标

### 7.2 常见性能瓶颈与排查

| 现象 | 可能原因 | 排查方式 |
|------|---------|---------|
| TPS 上不去 | 数据库连接池耗尽 | 检查 `application.yml` 中 `spring.datasource.hikari.maximum-pool-size` |
| 响应时间线性增长 | 存在同步锁或资源竞争 | 查看 JVM 线程 dump |
| 失败率突增 | LLM API Rate Limit | 检查百炼/DeepSeek 返回 429 状态码 |
| 内存持续增长 | 内存泄漏 | 监控 JVM 堆内存，使用 `jmap` 分析 |
| SSE 连接中断 | Tomcat 线程池不足 | 检查 `server.tomcat.threads.max` |
| 向量检索慢 | 索引未命中或数据量大 | 检查 pgvector 查询计划 `EXPLAIN ANALYZE` |

### 7.3 关键配置调优项

```yaml
# application.yml 中影响性能的关键配置

# Tomcat 线程池
server:
  tomcat:
    threads:
      max: 200        # 默认 200，高并发可调至 500
      min-spare: 10
    max-connections: 8192
    accept-count: 100

# 数据库连接池
spring:
  datasource:
    hikari:
      maximum-pool-size: 20   # 默认 10，建议 (CPU核数*2 + 磁盘数)
      minimum-idle: 5
      connection-timeout: 30000

# Agent 搜索线程池
app:
  agent:
    search-executor:
      core-pool-size: 4
      max-pool-size: 8
      queue-capacity: 100
```

---

## 8. 导出 JMeter 脚本（高级场景）

当 Apifox 内置性能测试无法满足需求时（如分布式压测、更复杂的断言/逻辑），可导出 JMeter 脚本：

### 8.1 导出步骤

1. 在 Apifox 测试场景中，点击右上角 **「更多」→「导出为 JMeter 脚本」**
2. 保存 `.jmx` 文件
3. 使用 JMeter 打开并按需修改

### 8.2 JMeter 适用场景

- **分布式压测**：多台机器同时发压，突破单机限制
- **复杂断言**：正则表达式提取、JSON Path 断言
- **思考时间**：模拟用户真实操作间隔
- **CSV 数据驱动**：大量测试数据参数化
- **定时器**：Constant Timer、Gaussian Random Timer

### 8.3 JMeter 推荐配置

| 配置项 | 推荐值 |
|--------|--------|
| Thread Group - Number of Threads | 100-500 |
| Ramp-Up Period | 60s |
| Loop Count | Forever + Duration Schedule |
| Duration | 600s |
| HTTP Request Defaults - Connect Timeout | 10000ms |
| HTTP Request Defaults - Response Timeout | 60000ms |

---

## 附录

### A. 参考链接

- [Apifox 性能测试官方文档](https://docs.apifox.com/performance-testing)
- [Apifox 性能测试全面指南（博客）](https://apifox.com/blog/performance-testing/)
- [2026 年 5 大主流性能测试工具推荐](https://apifox.com/apiskills/5-performance-testing-tools/)
- [什么是性能测试？概念与意义](https://apifox.com/apiskills/performance-testing/)

### B. 测试环境记录模板

| 项目 | 值 |
|------|---|
| 测试日期 | |
| Apifox 版本 | |
| 服务器配置（CPU/内存） | |
| JVM 版本 | |
| 数据库版本 | |
| 测试数据量 | |
| 网络环境 | |
| 备注 | |
