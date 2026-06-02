# Phase 2: 策略模式 + 回归测试

> 父任务：05-13-team-collaboration
> 设计文档：`docs/TEAM-FEATURE-PRD.md` §4.3.1

## 目标

将现有上传逻辑封装到 `PersonalUploadStrategy`，引入策略模式框架，**回归测试验证个人上传不受影响**。

## 前置条件

- Phase 1 完成（EtlCandidate 扩展 + ErrorCode + 枚举类）

## 交付物

### 1. UploadStrategy 接口

路径：`com.demo.chat.team.upload.UploadStrategy`

```java
public interface UploadStrategy {
    DocumentUploadResponse upload(MultipartFile file, @Nullable Long teamId, Long userId);
    List<DocumentUploadResponse> uploadBatch(List<MultipartFile> files, @Nullable Long teamId, Long userId);
}
```

### 2. PersonalUploadStrategy（封装现有逻辑）

路径：`com.demo.chat.team.upload.PersonalUploadStrategy`

将 `DocumentApplicationServiceImpl.upload()` / `uploadBatch()` 的**全部逻辑**搬到此类，包括：
- `DocumentValidator` 校验
- MinIO 存储（`FileStorageService`）
- `persistDocument()` 私有方法 → 提取为独立方法
- ETL 触发（`EtlDispatchService`）
- 返回 `DocumentUploadResponse`

依赖注入：`FileStorageService`、`EtlDispatchService`、`RagDocumentMapper`、`MinioProperties`、`DocumentValidator`

**关键：** teamId 参数传入但忽略（个人上传不关心 teamId）。

### 3. UploadStrategyFactory

路径：`com.demo.chat.team.upload.UploadStrategyFactory`

```java
@Component
public class UploadStrategyFactory {
    private final PersonalUploadStrategy personalUploadStrategy;
    private final TeamUploadStrategy teamUploadStrategy; // Phase 3 实现

    public UploadStrategy route(@Nullable Long teamId) {
        return teamId != null ? teamUploadStrategy : personalUploadStrategy;
    }
}
```

Phase 2 阶段 `TeamUploadStrategy` 暂不实现，Factory 先抛异常：
```java
if (teamId != null) throw new BusinessException(ErrorCode.NOT_TEAM_MEMBER, "团队功能尚未实现");
```

### 4. DocumentApplicationServiceImpl 重构

将 `upload()` / `uploadBatch()` 改为纯委托：
```java
public DocumentUploadResponse upload(MultipartFile file) {
    return uploadStrategyFactory.route(null).upload(file, null, SecurityUtils.getCurrentUserId());
}
```

**从类中删除**原有的上传实现代码（`persistDocument` 等私有方法），避免死代码。

### 5. 回归测试

**测试脚本**：`set -uo pipefail`

| 测试场景 | 方法 |
|---------|------|
| 单文件上传（PDF） | POST `/api/documents/upload` |
| 批量上传（2 个文件） | POST `/api/documents/upload/batch` |
| 上传空文件 → 50001 | POST `/api/documents/upload` |
| 上传超大文件 → 50003 | POST `/api/documents/upload` |
| 上传不支持的 MIME → 50004 | POST `/api/documents/upload` |
| 上传后文档列表包含新文档 | GET `/api/documents` |
| 删除文档 | DELETE `/api/documents/{id}` |
| 重试失败文档 | POST `/api/documents/{id}/retry` |
| 所有现有 JUnit 测试通过 | `./mvnw test` |

## 验收标准

- [ ] `UploadStrategy` 接口存在
- [ ] `PersonalUploadStrategy` 封装了完整上传逻辑
- [ ] `UploadStrategyFactory` 路由逻辑正确
- [ ] `DocumentApplicationServiceImpl.upload()` 改为委托
- [ ] **所有现有 JUnit 测试通过**
- [ ] 手动 API 回归测试全部通过
- [ ] teamId 有值时返回占位错误

## 不做的事

- 不实现 `TeamUploadStrategy`
- 不改分片上传（Phase 3）
- 不改 RAG 检索
