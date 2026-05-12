# P5: 集成改造

## 目标
将 EtlDispatchService 接入 DocumentApplicationService，新增批量上传端点。

## 实现清单

### 1. EtlDispatchService
- 路径: `com.demo.chat.rag.service.EtlDispatchService`
- 接口方法:
  - `EtlDispatchResult dispatch(List<EtlCandidate> candidates)` — 批量调度
  - `int executeSingle(Long documentId, String bucket, String objectKey, String fileName, String mimeType)` — 单文档同步（保持向后兼容）
- 实现类 `EtlDispatchServiceImpl`：
  - 注入 EtlRouteStrategyFactory
  - dispatch: resolve strategy → strategy.execute()
  - executeSingle: 包装为单元素 list 走 dispatch

### 2. DocumentApplicationService 改造
- 现有 `upload(MultipartFile)` 保持不变，内部改为调用 `EtlDispatchService.executeSingle()`
- 新增 `uploadBatch(List<MultipartFile>)` 批量上传
  - 校验所有文件（MIME + 大小）
  - 全部存入 MinIO
  - 构建 List<EtlCandidate>
  - 调用 `EtlDispatchService.dispatch()`
  - 返回 `List<DocumentUploadResponse>`

### 3. DocumentController 新增端点
- `POST /api/documents/upload/batch` — 批量上传
  - 接收 `MultipartFile[]` files
  - 委托 DocumentApplicationService.uploadBatch()

### 4. ChatRequest 兼容
- 单文档上传 API 不变（`POST /api/documents/upload`）
- 批量上传是新增端点，不破坏现有 API

## 验收
- [x] 单文档上传行为不变
- [x] 批量上传走 dispatch 路由
- [x] 新增 API 端点可用
- [x] 编译通过
