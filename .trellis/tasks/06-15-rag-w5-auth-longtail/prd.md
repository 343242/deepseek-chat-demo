# RAG review W5: auth + long tail

Parent: `06-15-remediate-all-rag-module-review-findings` · Priority: **P2** — authorization gap + defense-in-depth + cleanup.

## Goal

Close the remaining findings: the team-document authorization gap (R1-M1), the defensive-programming + semantic long tail, and accumulated LOW cleanups from the W1–W3 review passes.

## Findings — original W5 bucket (from `docs/reviews/2026-06-15-rag-module-review.md`)

- **R1-M1** `DocumentApplicationServiceImpl.verifyAccess` (~:194-215): team documents have NO owner/role check — any team member can delete/retry another member's document. (W2's R1-H4 fixed the 404-vs-403 status codes but intentionally deferred the owner-enforcement to here.)
- **R1-L2** `PersonalUploadStrategy.computeMd5` returns `null` on failure → that file can never be quick-upload-deduped (silent degradation). (Now uses `DigestUtils` from W2; only the failure behavior remains.)
- **R1-M2** `ChunkUploadServiceImpl` session `Long.parseLong` (~:341,386,404) — no `NumberFormatException` defense.
- **R1-M3** `EtlDispatchServiceImpl` (~:95,107) — watchdog lock-renewal starvation can auto-release the lock → concurrent ETL; document that the lock is best-effort and vector-store idempotency is the true boundary.
- **R1-M8** `RerankDocumentPostProcessor` (~:41-42) — empty-doc / blank-query / rerank-failure return semantics are inconsistent; document the degradation contract.
- **R1-M9** `EtlStatusManager` (~:83,113) — `truncate(msg, 2000)` assumes `error_message` column ≥2000; verify DDL + align.
- **R1-L3** `ChunkUploadServiceImpl.sanitizeFilename` (~:617) leaves `..` (safe as object key) — add an explanatory comment.
- **R1-L4** `QueryNormalizer` (~:51-53) DEBUG-logs the raw query (potential PII) — document/redact.
- **R1-L5** `MmrDocumentPostProcessor` (~:79,184) silently truncates at `MAX_PAIRWISE_DOCS=50` — surface in metadata / info log.
- **R2-L1** `ChunkUploadController` (~:65) loads each chunk as `byte[]` — stream to MinIO instead.
- **R2-L2** `PptDocumentParser` (~:249-282) table `rows*cols` unbounded — add `MAX_TABLE_ROWS/COLS`.

## ⚠️ One genuine decision to confirm at W5 brainstorm (not pre-decided)

**R1-M1 authorization strictness** — needs product input. Options:
1. **Owner-only for mutations** — only the uploader (or admin) can delete/retry a team doc; other members read-only. (Stricter; may break existing team workflows that expect shared management.)
2. **Role-based** — team admin/owner can manage all; members manage their own. (More flexible; needs role data.)
3. **Document current behavior + tighten only delete** — keep member-shared management but restrict destructive `delete` to owner/admin; retry stays shared.
Recommend option 1 or 3 depending on the team model. **Surface this to the user before implementing.**

## Accumulated LOW cleanups (from W1–W3 independent reviews — capture here so they aren't lost)

- **L-C1** (W1 review): `DocxDocumentParser` `MAX_PARAGRAPHS` counts paragraphs but not runs/tables — a single paragraph with pathological runs or many tables can still OOM under the cap. Add a run/element budget or document the gap.
- **delete() dead boolean** (W2 review): `DocumentApplicationServiceImpl.delete()` now always returns `true` (not-found/forbidden throw first) → consider changing the interface to `void` (optional).
- **W2 test dead imports** (W2): `W2CorrectnessQuickWinsTest` unused `SecurityUtils`, `Executor` imports.
- **W3 test warnings** (W3): `W3UnboundedReadsTest` unused imports (`SecurityUtils`, `Nullable`), unnecessary `@SuppressWarnings`, deprecated `setPages(long)`, raw-type `selectPage`. Cosmetic.
- **PageRequest drift** (W3 LOW-2): list endpoints clamp to 100 inline while `PageRequest` clamps to 500 — consider a shared `[1,100]` helper to avoid drift.
- **searchCount comment** (W3 LOW-3): `DocumentDedupService` batched load uses `new Page<>(1,n,false)` — add a one-line comment that `false` skips COUNT intentionally.
- **warmedUp observability** (W3 LOW-4): when `bloomFilter==null`, `warmedUp` stays false forever (correct, but misleading for ops) — set `warmedUp=true` there or add a `bloomDisabled` flag.

## Acceptance Criteria
- [ ] R1-M1 implemented per the chosen authorization option (after brainstorm); test covers owner-vs-non-owner mutation.
- [ ] R1-L2: `computeMd5` failure → at least ERROR-level log (or fail the upload); no silent permanent dedup loss.
- [ ] R1-M2: `parseSessionLong` helper catches `NumberFormatException` → `ServiceException`.
- [ ] R1-M3: comment documents the lock-is-best-effort + idempotency boundary (or vector-store idempotency verified).
- [ ] R1-M8: rerank degradation contract documented (consistent empty/passthrough semantics).
- [ ] R1-M9: `error_message` DDL verified; truncate constant aligned (or read from config).
- [ ] R1-L3/L4/L5: comments / redaction / metadata-surfacing applied.
- [ ] R2-L1: chunk streamed to MinIO (no `byte[]` body) OR documented why infeasible.
- [ ] R2-L2: `MAX_TABLE_ROWS/COLS` cap on PPTX tables.
- [ ] Accumulated LOWs: L-C1 + chosen cleanups applied; remaining deferred items explicitly listed.
- [ ] `/ecc:verify` + `gitnexus_detect_changes` clean.

## Definition of Done
Tests added/updated where behavior changes; CI green; the review's remaining findings closed or explicitly deferred-with-rationale.

## Out of Scope
Anything already closed in W1–W4. Note: many files here were edited in earlier waves — read CURRENT state before editing.

## Pre-flight (every wave)
- Run `npx gitnexus analyze` (index goes stale after each wave's commits).
- GateGuard is OFF this environment via `ECC_GATEGUARD=off` (ECC plugin) — keep it off for low-friction edits; re-enable if desired.
- Follow the proven W1–W3 loop: delegate implementation to an executor (clean context) → independent `code-reviewer` pass (it caught real gaps each wave: H-T1, R1-M7 init-path, @EnableAsync) → fix blockers → commit W-scope only (exclude `messaging-bus.md` + the `align-messaging-bus` task) → mark completed via task.json + chore commit → push.
