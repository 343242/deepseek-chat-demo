# PRD: 代码审查问题修复

## 背景

对 chat-demo 项目的 RAG 和 Chat 模块进行了全面代码审查，发现 2 个 P0 阻塞性问题和 10 个 P1 重大问题。本任务集中修复所有 P0 和 P1 级别问题。

## P0 — Blocking（2 项）

### P0-1: DocumentController 内 try-catch
- **文件**: `src/main/java/com/demo/chat/rag/controller/DocumentController.java`
- **问题**: Controller 内 try-catch IllegalArgumentException，返回 400 空 body
- **修复**: 删除 try-catch，Service 层直接抛 BusinessException，由 GlobalExceptionHandler 统一处理

### P0-2: Service 层抛 IllegalArgumentException
- **文件**: `src/main/java/com/demo/chat/rag/service/impl/DocumentApplicationServiceImpl.java`
- **问题**: 6 处 `throw new IllegalArgumentException(...)` 违反异常规范
- **修复**: 全部替换为 `throw new BusinessException(...)`

## P1 — Major（10 项）

### P1-1: RagDocument.status 是裸 String
- **文件**: `entity/RagDocument.java`, `etl/EtlStatus.java`
- **修复**: `EtlStatus` 从常量类改为真正的 `enum`，`RagDocument.status` 类型改为 `EtlStatus`，MyBatis-Plus 用 `@EnumValue` 映射

### P1-2: DocumentDTO / DocumentUploadResponse 是可变类
- **文件**: `dto/DocumentDTO.java`, `dto/DocumentUploadResponse.java`
- **修复**: 改为 Java record，消除 50+ 行样板代码

### P1-3: DocumentApplicationServiceImpl 违反 SRP
- **文件**: `service/impl/DocumentApplicationServiceImpl.java`
- **修复**: 提取 `DocumentValidator`（校验 + MIME 探测）和 `DocumentLifecycleService`（存储 + 向量清理编排）

### P1-4: HybridDocumentRetriever 手动解析 JSON
- **文件**: `retrieval/HybridDocumentRetriever.java`
- **修复**: 用 Jackson ObjectMapper 替代 split/indexOf 手工解析

### P1-5: RagAdvisorFactory 重复创建 RewriteQueryTransformer
- **文件**: `config/RagAdvisorFactory.java`
- **修复**: 注入 RagConfig 中已有的 RewriteQueryTransformer Bean，删除本地重复创建

### P1-6: FastTrackStrategy JSON 拼接风险
- **文件**: `etl/FastTrackStrategy.java`
- **修复**: 用 Jackson 或 Map → JSON 安全构建 metadata

### P1-7: DeepSeekModelProvider 每次创建新 DeepSeekApi
- **文件**: `chat/provider/DeepSeekModelProvider.java`
- **修复**: 将 `DeepSeekApi` 缓存为字段，构造时初始化一次

### P1-8: ChatController.chatStreamGet() 未传递 enableThinking
- **文件**: `chat/controller/ChatController.java`
- **修复**: 增加 `@RequestParam(defaultValue = "false") boolean enableThinking` 参数

### P1-9: ModelRegistryRefresher 反向索引不完整
- **文件**: `chat/service/ModelRegistryRefresher.java`
- **修复**: `newIndex` 同时索引 `compositeKey → providerId`

### P1-10: SensitiveWordFilterService 未注册 Spring Bean
- **文件**: `chat/content/SensitiveWordFilterService.java`
- **修复**: 确认是否有配置类注册；若无，添加 `@Service` 注解

## 约束

- 编译通过 + 全部测试通过
- 遵循项目 Trellis 规范（编程式事务、DTO record、BusinessException 等）
- 每个 P0/P1 修复后立即验证编译
- 最终 git commit + push 到 `rag-dev` 分支
