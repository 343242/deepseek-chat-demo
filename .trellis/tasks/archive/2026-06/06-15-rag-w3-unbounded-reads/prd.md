# RAG review W3: unbounded reads / pagination

Parent: `06-15-remediate-all-rag-module-review-findings` · Priority: **P1** — DoS/scalability hardening for list + startup-read paths.

## Goal

Close the 3 unbounded-read findings: paginated list endpoints, non-blocking BloomFilter initialization, and an explicit size pre-check before streaming an upload into a temp file.

## Findings (from `docs/reviews/2026-06-15-rag-module-review.md`)

- **R1-H2** `DocumentApplicationServiceImpl.listAll`/`listByTeam`/`getHistory` — bare `selectList`, no `Page`/`LIMIT`; a power user with thousands of docs OOMs/degrades.
- **R1-M6** `DocumentDedupService` constructor synchronously `selectList`s all non-deleted `fileMd5` into the BloomFilter — blocks/can fail app startup at scale.
- **R2-M1** `OpenDataLoaderPdfParser` writes the whole resource to a temp file via `transferTo` with no size guard before writing (only bounded by the upstream 50MB cap).

## Decisions (locked 2026-06-16)

- **R1-H2** — paginate `listAll` and `listByTeam` with MyBatis-Plus:
  - Controllers accept `@RequestParam(defaultValue="1") page` and `@RequestParam(defaultValue="20") size`, **size capped at 100** (clamp silently or reject >100 — prefer clamp with a debug log).
  - Use the project's existing pagination wrapper if one exists (grep for `IPage`/`Page`/`PageResult` usage first); otherwise MyBatis-Plus `Page<>` + `selectPage`. Return a paged response shape (list + total + page + size).
  - **`getHistory` (filtered by `documentGroupId`)**: assess whether it's genuinely unbounded at scale. If it is, paginate it too; if it's inherently bounded (small per-group result set), document why and leave it (do not add unnecessary pagination that churns the chat-history API). Report the decision + rationale.
- **R1-M6** — move the BloomFilter warm-up (`loadExistingFileMd5s`) off the constructor and onto `ApplicationReadyEvent` (async / non-blocking startup); keep the cold-start correctness (dedup falls back to DB until warm). Paginate the load if the row count is large (stream/batch rather than one `selectList`).
- **R2-M1** — in `OpenDataLoaderPdfParser`, before `transferTo`, assert the byte count ≤ `maxFileSize` (read the value from `DocumentProperties`); throw the parser's typed exception (`DocumentParseException`) on overflow. Keep the existing `finally` cleanup.

## Acceptance Criteria

- [ ] `listAll`/`listByTeam` return a paged result; `size>100` is clamped; tests cover paging + size clamp.
- [ ] `getHistory`: either paginated (if unbounded) with a test, OR documented-as-bounded with the rationale recorded (no behavior change) — executor decides + reports.
- [ ] App startup no longer blocks on the BloomFilter full-load; dedup still works (warm-up on `ApplicationReadyEvent`); test or startup-log evidence.
- [ ] `OpenDataLoaderPdfParser` rejects an oversized resource before `transferTo`; test covers it.
- [ ] `/ecc:verify` + `gitnexus_detect_changes` clean.

## Implementation Plan (ordered)

1. Inspect existing pagination conventions (grep `IPage`/`Page`/`PageResult`/`selectPage`); pick the project idiom.
2. `DocumentApplicationServiceImpl` + `DocumentController`: paginate `listAll`/`listByTeam` (+ assess `getHistory`).
3. `DocumentDedupService`: move warm-up to `ApplicationReadyEvent`, paginate/stream the MD5 load.
4. `OpenDataLoaderPdfParser`: size pre-check before `transferTo`.
5. Tests for each.
6. `/ecc:verify` + `gitnexus_detect_changes` + commit (coordinator handles commit).

## Definition of Done

- Tests added/updated per fix; CI green; no unbounded-read finding (R1-H2/M6, R2-M1) remains open.

## Out of Scope

- W2 items (done). W4 (R1-H3/M5/M4) and W5 (auth + long tail).
