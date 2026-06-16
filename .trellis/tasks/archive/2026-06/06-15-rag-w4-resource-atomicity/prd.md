# RAG review W4: resource / atomicity

Parent: `06-15-remediate-all-rag-module-review-findings` · Priority: **P2** — leak/orphan-state prevention.

## Goal

Close the 3 resource-leak / non-atomic findings: batch-upload partial-failure handling, MinIO stream close on exception, and robust orphan-cleaner path parsing.

## Findings (from `docs/reviews/2026-06-15-rag-module-review.md`)

- **R1-H3** `PersonalUploadStrategy.uploadBatch` (~:113-132) is non-atomic across MinIO upload → DB persist → ETL dispatch; a mid-loop failure leaves already-persisted rows in `UPLOADED` forever (no dispatch, no cleanup).
- **R1-M5** `MinioFileStorageService.MinioStreamResource` (~:109-127) doesn't override `close()` → a parser throwing mid-read leaks the MinIO HTTP connection.
- **R1-M4** `OrphanChunkCleaner` (~:164-173) does `objectName.split("/")` assuming a fixed depth (`parts[2]`); a path-structure change mis-parses and can delete a live chunk.

## Decisions (locked 2026-06-16)

- **R1-H3** — per-file resilience, not a global transaction (MinIO can't roll back): wrap each file's upload→persist in try/catch, record per-file success/failure, CONTINUE the batch, and ensure all successfully-persisted candidates are dispatched (even if a later file failed). Return a batch result (per-file status) so the client knows what landed. This is more user-friendly than failing the whole batch and avoids the `UPLOADED`-forever dead state.
- **R1-M5** — override `close()` in `MinioStreamResource` to close the wrapped `GetObjectResponse`; AND make `DocumentExtractor.extract` consume the `Resource` in try-with-resources so parser exceptions can't leak the connection.
- **R1-M4** — replace the index-based split with a regex `^chunks/[^/]+/([0-9a-f-]{36})/part-\d+$` to extract the `uploadId` robustly; if an object name doesn't match, skip it (never delete based on a mis-parse).

## Acceptance Criteria

- [ ] `uploadBatch`: a batch where file N fails still dispatches ETL for files 1..N-1 (already persisted) and returns a per-file result; no row is left `UPLOADED` without a dispatch path. Test covers partial failure.
- [ ] `MinioStreamResource.close()` closes the stream; `DocumentExtractor.extract` uses try-with-resources; a parser-throw test proves no connection leak (verify via try-with-resources semantics / a close-verification test).
- [ ] `OrphanChunkCleaner` extracts uploadId via regex; a non-matching object name is skipped (not deleted); test covers both match and non-match.
- [ ] `/ecc:verify` + `gitnexus_detect_changes` clean.

## Implementation Plan
1. `PersonalUploadStrategy.uploadBatch`: per-file try/catch + collect candidates + dispatch all persisted; batch result return type.
2. `MinioFileStorageService.MinioStreamResource`: override `close()`; `DocumentExtractor.extract`: try-with-resources.
3. `OrphanChunkCleaner`: regex extraction + skip-on-non-match.
4. Tests per fix; `/ecc:verify` + `gitnexus_detect_changes`; commit.

## Definition of Done
Tests added/updated per fix; CI green; no resource/atomicity finding (R1-H3/M5/M4) open.

## Out of Scope
W5 (auth + long tail). Note: this file (`PersonalUploadStrategy`) was edited in W2 (U1) and W1 — read CURRENT state.
