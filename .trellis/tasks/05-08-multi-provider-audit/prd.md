# PRD: Multi-Provider Architecture Audit

## 审计范围

对 deepseek-chat-demo 项目的多模型厂商聚合功能进行全面审计。

### 核心文件

**接口与基础设施:**
- `src/main/java/com/demo/deepseekchat/chat/provider/ModelProvider.java` — 策略接口
- `src/main/java/com/demo/deepseekchat/chat/provider/ProviderRegistry.java` — 服务定位器
- `src/main/java/com/demo/deepseekchat/chat/provider/ModelRouter.java` — 路由解析
- `src/main/java/com/demo/deepseekchat/chat/dto/ProviderModelInfo.java` — 聚合 DTO

**Provider 实现 (4个):**
- `src/main/java/com/demo/deepseekchat/chat/provider/DeepSeekModelProvider.java`
- `src/main/java/com/demo/deepseekchat/chat/provider/ZhipuModelProvider.java`
- `src/main/java/com/demo/deepseekchat/chat/provider/MiniMaxModelProvider.java`
- `src/main/java/com/demo/deepseekchat/chat/provider/MoonshotModelProvider.java`

**适配层:**
- `src/main/java/com/demo/deepseekchat/chat/service/ModelRegistryRefresher.java`
- `src/main/java/com/demo/deepseekchat/chat/service/ChatService.java` (buildRequestSpec 方法)
- `src/main/java/com/demo/deepseekchat/chat/service/ModelService.java`
- `src/main/java/com/demo/deepseekchat/chat/controller/ChatController.java`

**配置:**
- `src/main/java/com/demo/deepseekchat/config/DeepSeekAutoConfiguration.java`
- `src/main/java/com/demo/deepseekchat/DeepseekChatApplication.java`
- `src/main/resources/application-dev.yml`

**测试:**
- `src/test/java/com/demo/deepseekchat/chat/provider/*.java`

### 审计维度

1. **设计原则**: SOLID 是否遵守？OCP 能否真正做到"加厂商零修改"？
2. **设计模式**: 策略/工厂/模板方法是否正确运用？是否有滥用或缺失？
3. **OOP**: 封装是否彻底？ChatOptions 类型差异是否泄漏到上层？多态是否正确？
4. **代码质量**: 可读性、可维护性、命名规范、Javadoc 完整性
5. **潜在 Bug**: 空指针、并发安全、资源泄漏、异常处理
6. **向后兼容**: 纯 modelId 请求是否仍能正常工作？
7. **僵尸代码**: ChatClientFactory 是否还需要？是否有其他死代码？
8. **配置一致性**: API Key 配置路径是否统一？
9. **测试覆盖**: 契约是否完整？边界情况是否覆盖？
10. **性能**: ModelService.findProviderForModel() 是否有性能隐患？

### 已知自审问题

- ChatClientFactory 是僵尸代码（无人引用但仍注册为 Bean）
- ModelService.findProviderForModel() 每次遍历所有 Provider 的 fetchModels() 有性能隐患
- Moonshot 配置路径使用环境变量而非 spring.ai 前缀，与其他 Provider 不一致
- ModelRouter 默认 provider 硬编码 "deepseek"

### 输出要求

按严重程度分类：P0 (必须修复) / P1 (应该修复) / P2 (建议改进)
每个问题给出：文件、行号、描述、修复建议
