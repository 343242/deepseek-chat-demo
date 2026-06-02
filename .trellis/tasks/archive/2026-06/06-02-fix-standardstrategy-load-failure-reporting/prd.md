# Update Stale Unit Tests

## Goal

Align the failing unit tests with the current implementation contracts so the test suite stops reporting stale expectations as regressions.

## What I Already Know

- `ModelParamsServiceImpl` wraps `saveOrUpdate` and `delete` in `TransactionTemplate.execute`.
- `VectorStoreMapper.bm25Search` now binds 5 SQL parameters because it reuses the CTE query value.
- `MmrDocumentPostProcessor` currently returns selected documents without writing `mmrSelected` metadata.
- `CaptchaServiceTest` fails in this environment because AWT tries to connect to X11 instead of running headless.
- Existing unrelated untracked docs files must not be included.

## Requirements

- Update tests to reflect the current service/mapper/post-processor contracts.
- Configure test execution to run captcha image generation in headless mode.
- Do not change production behavior unless tests reveal a true implementation defect.

## Acceptance Criteria

- [ ] `ModelParamsServiceImplTest` passes.
- [ ] `VectorStoreMapperTest` passes.
- [ ] `MmrDocumentPostProcessorTest` passes.
- [ ] `CaptchaServiceTest` passes in the current headless environment.
- [ ] Targeted tests and compile checks pass.

## Out of Scope

- Refactoring production services.
- Fixing unrelated full-suite failures outside these four groups.
