# PRD: Fix All Code Review Issues in infrastructure/llm

## Context
Code review of `src/main/java/com/smart/rag/infrastructure/llm/` (63 files) identified 83 issues.
Full report: `../06-13-code-review-infrastructure-llm-module-63-files/review-report.md`

## Scope
Fix all P0 (5), P1 (21), P2 (34), P3 (23) issues in priority order.

## P0 Fixes (Must Fix)
1. `ProbeHandler.java` — remove double timeout, fix unconditional raw stream subscription
2. `HttpClientErrorHandler.java` — eliminate sneaky-throw, wrap IOException in RemoteException
3. `GenericChatClient.java` — unify HTTP client tech, fix resource leak
4. `LlmAutoConfiguration.java` — convert to constructor injection

## P1 Fixes (Should Fix)
- Record DTOs: add compact constructor validation (ChatRequest, RerankRequest, RerankResult, LlmResponse)
- AbstractModelCandidate: fix validate() to use ClientException + Chinese messages
- Client constructors: add Objects.requireNonNull guards
- RegistrySnapshot: add compact constructor for immutability
- Resilient clients: extract executeResilient() template method
- Add missing logging (Generic clients init, HttpClientErrorHandler, LlmClientFactory)
- Missing @ConditionalOnMissingBean, @AutoConfigureAfter
- Unsafe cast in GenericOpenAiProviderRegistrar
- Duplicate import in ChatModelAdapter
- ChatCapable.asChatModel() DIP violation
- LlmMetrics gauge leak
- Collectors.toUnmodifiableMap merge functions
- deepThinkingModel validation

## P2 Fixes
- Timeout values externalized
- DRY in rerank parsing (extract to AbstractRerankClient)
- EmbeddingType ignored in embedBatch
- Various boundary/null checks

## P3 Fixes
- Naming consistency, toString on POJOs, documentation

## Constraints
- Follow spec: `.trellis/spec/backend/` (code-review-checklist, quality-guidelines, error-handling)
- Forbidden: @Autowired field injection, IllegalArgumentException, System.out
- Use ClientException/ServiceException/RemoteException, Chinese error messages
- Constructor injection only
