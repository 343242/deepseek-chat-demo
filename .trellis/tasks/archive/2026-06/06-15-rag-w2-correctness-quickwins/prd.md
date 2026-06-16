# RAG review W2: correctness quick wins

Parent: `06-15-remediate-all-rag-module-review-findings` · Priority: **P1** — low-risk, high-ROI correctness fixes.

## Goal

Resolve the 5 correctness/quality findings that are small, safe, and high-value. These are mostly "return null → throw" + idiomatic cleanups.

## Findings (from `docs/reviews/2026-06-15-rag-module-review.md`)

- **R1-H1** `complete()` returns `null` docId wrapped as HTTP 200 (silent failure)
- **R1-H4** `getById`/`verifyAccess` return `null` for both not-found and forbidden → 200-with-null
- **R1-M7** `DocumentValidator.getAllowedMimeTypes().split(",")` has no `trim` → config with spaces silently rejects uploads
- **R1-L1** `TokenChunkStrategy`/`ParentChildChunkStrategy` rebuild `TokenTextSplitter` on every `chunk()` call
- **U1** MD5 via raw `java.security.MessageDigest` at 3 sites + 2 hand-rolled hex encoders → unprofessional; replace with `commons-codec` `DigestUtils`

## Scope notes (important)

- **R2-L3 is already DONE** — landed as a bonus in W1 (`DocxDocumentParser` now throws `DocumentParseException`). **Exclude from W2.**
- **R1-H4 (W2) = status-code fix only**: make `verifyAccess` distinguish 404 (not-found) vs 403 (forbidden) and throw the right exception. The **owner-role authorization enforcement (R1-M1) stays in W5** — do NOT add owner/isAdmin checks here.
- **U1 (W2) = mechanical DigestUtils replacement only**, preserving `computeMd5`'s current null-on-failure behavior. Changing that failure behavior is **R1-L2 (W5)** — leave it. (So W2 touches `computeMd5` to swap the hashing lib, but keeps returning null on failure; W5 will revisit.)

## Decisions (locked 2026-06-15)

- **R1-H1** — `ChunkUploadServiceImpl.complete()`: when the post-merge lookup yields `doc == null`, throw `ServiceException(ETL_FAILED, "合并后文档未找到")` instead of returning `null`. Prefer having `performMerge` return the persisted `docId` directly rather than relying on a re-query (if low-risk); otherwise throw on the null lookup. Follow `.trellis/spec/backend/error-handling.md`.
- **R1-H4** — `DocumentApplicationServiceImpl.verifyAccess`: distinguish the two null cases — throw `ServiceException(DOCUMENT_NOT_FOUND)` (→ 404) when the doc doesn't exist, and `ClientException(FORBIDDEN)` (→ 403) when access is denied. `getById`/controller then propagate normally (no more 200-with-null). Do NOT add owner-role logic (that's W5/R1-M1).
- **R1-M7** — `DocumentValidator.getAllowedMimeTypes()`: `Arrays.stream(split).map(String::trim).filter(s -> !s.isEmpty()).collect(toSet())`. Cache as before.
- **R1-L1** — build the `TokenTextSplitter` once per bean (field/constructor), reuse in `chunk()`. Both `TokenChunkStrategy` and `ParentChildChunkStrategy`.
- **U1** — declare `commons-codec` as an explicit `<dependency>` **without a `<version>`** (Spring Boot 3.5.14 BOM manages it — confirmed on classpath at 1.18.0). Replace the 3 raw `MessageDigest` sites with `DigestUtils.md5Hex(InputStream)` / `md5Hex(byte[])`:
  - `ChunkUploadServiceImpl` ~:627 (`hexFormat(md.digest())` on a stream) and ~:694 (`md5Hex(byte[])`)
  - `PersonalUploadStrategy` ~:196 (`String.format("%02x")` loop)
  Delete the now-dead `hexFormat()` and `md5Hex(byte[])` helpers. Preserve identical MD5 output. Keep `computeMd5`'s null-on-failure return (R1-L2 is W5).

## Acceptance Criteria

- [ ] `complete()` no longer returns `null`-as-200; missing doc throws `ServiceException`. Test covers it.
- [ ] `getById` returns 404 for not-found, 403 for forbidden (not 200-null). Tests cover both branches.
- [ ] `DocumentValidator` accepts a MIME whitelist config containing spaces (e.g. `"application/pdf, text/plain"`). Test covers it.
- [ ] `TokenChunkStrategy`/`ParentChildChunkStrategy` construct the splitter once. Test or code inspection confirms no per-call rebuild.
- [ ] All 3 MD5 sites use `DigestUtils`; `hexFormat`/`md5Hex(byte[])` helpers removed; MD5 output unchanged (existing upload/dedup tests stay green).
- [ ] `commons-codec` declared in `pom.xml` (no version → BOM-managed); `mvn dependency:tree` confirms 1.18.0.
- [ ] `/ecc:verify` + `gitnexus_detect_changes` clean.

## Implementation Plan (ordered)

1. `pom.xml`: add `commons-codec` explicit dependency (no version).
2. `ChunkUploadServiceImpl`: R1-H1 (throw on null) + U1 (DigestUtils, delete helpers).
3. `PersonalUploadStrategy`: U1 (DigestUtils in `computeMd5`).
4. `DocumentValidator`: R1-M7 (trim) — note: this file was edited in W1; ensure the trim change composes cleanly.
5. `TokenChunkStrategy` / `ParentChildChunkStrategy`: R1-L1 (splitter reuse).
6. `DocumentApplicationServiceImpl` (`verifyAccess`/`getById`) + controller: R1-H4.
7. Tests for each fix.
8. `/ecc:verify` + `gitnexus_detect_changes` + commit (coordinator handles commit).

## Definition of Done

- Tests added/updated per fix; CI green; no correctness finding from R1 (H1/H4/M7/L1) + U1 remains open.

## Out of Scope

- R1-M1 (team-doc owner authorization) → W5.
- R1-L2 (computeMd5 null-on-failure behavior) → W5.
- R2-L3 → already done in W1.
