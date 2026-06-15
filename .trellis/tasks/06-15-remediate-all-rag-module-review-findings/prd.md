# Remediate all RAG module review findings

## Goal

Full remediation of the 28 findings from the RAG module security + correctness review
(`docs/reviews/2026-06-15-rag-module-review.md`), executed as 5 priority-ordered waves (W1–W5).
Unblocks the RAG ingestion path for untrusted uploads and clears correctness/architecture debt.

## What I already know

- Review source: `docs/reviews/2026-06-15-rag-module-review.md` (28 findings; 2 ECC agent passes + manual MD5 verification)
- Stack: Spring Boot 3.5.14 / Java 21 / Spring AI 1.1.6 / MyBatis-Plus 3.5.16 / Redis (Redisson 3.52.0) / MinIO / Agentic RAG
- 6 threat classes (XXE / SSRF / deserialization / zip-slip / ReDoS / encoding) already verified **NOT exploitable** — out of scope this cycle

## Structure (decided 2026-06-15)

Umbrella task decomposed into 5 child tasks, executed W1 → W5:

| Wave | Child task | Priority | Findings |
|---|---|---|---|
| W1 Security hardening | `06-15-rag-w1-security-hardening` | **P0** | R2-H1, R2-H2, R2-Dep, R2-M3 |
| W2 Correctness quick wins | `06-15-rag-w2-correctness-quickwins` | P1 | R1-H1, R1-H4, R1-M7, R2-L3, R1-L1, U1 |
| W3 Unbounded reads | `06-15-rag-w3-unbounded-reads` | P1 | R1-H2, R1-M6, R2-M1 |
| W4 Resource / atomicity | `06-15-rag-w4-resource-atomicity` | P2 | R1-H3, R1-M5, R1-M4 |
| W5 Auth + long tail | `06-15-rag-w5-auth-longtail` | P2 | R1-M1, R1-M2, R1-M3, R1-M8, R1-M9, R1-L2..L5, R2-L1, R2-L2 |

Each wave = its own branch off `agentic-rag-dev` + one PR.

## Global decisions (ADR-lite)

- **U1 — commons-codec**: use the Spring Boot 3.5.14 **BOM-managed** version; do **NOT** pin 1.22.0. Replace the 3 raw `MessageDigest` sites with `DigestUtils.md5Hex(InputStream/byte[])`, delete the hand-rolled hex helpers.
- **R2-Dep — commons-compress**: pin ≥1.27.x in `<dependencyManagement>` + verify the resolved version via `mvn dependency:tree` during W1.
- **Quality bar**: every finding fix ships with an added/updated test (unit; integration for upload/ETL paths); ECC `/ecc:verify` (build/lint/test/security) green before the wave's PR merges.
- **GitNexus** (mandated by CLAUDE.md): `gitnexus_impact` before editing each symbol; `gitnexus_detect_changes` before each wave's commit.

## Requirements

- All 28 findings remediated across W1–W5.
- W1 unblocks untrusted-upload exposure (security blockers resolved).
- No regressions: existing RAG upload / parse / retrieve tests stay green.
- Each wave ships as its own reviewable PR.

## Acceptance Criteria

- [ ] W1 merged: chunk-upload runs `detectMimeType` on the merged object and routes on detected MIME; OOXML has explicit `ZipSecureFile` thresholds + paragraph/slide caps; `commons-compress` pinned & verified; plain-text parser self-bounds reads.
- [ ] W2 merged: `complete()` / `getById()` throw instead of returning null; MIME config trimmed; `DocxDocumentParser` typed exception; splitter reused; MD5 via `DigestUtils`.
- [ ] W3 merged: list endpoints paginated; dedup loads async; OpenDataLoader size-prechecks.
- [ ] W4 merged: batch upload atomic/compensating; `MinioStreamResource.close()` + try-with-resources; orphan cleaner robust path parse.
- [ ] W5 merged: team-doc authorization enforced; session parse defense; rerank semantics documented; `error_message` length aligned; long-tail LOWs.
- [ ] `mvn dependency:tree` shows `commons-compress` ≥1.27.x and `commons-codec` at the BOM version.
- [ ] `/ecc:verify` + `gitnexus_detect_changes` clean for every wave.

## Definition of Done

- Tests added/updated per finding; CI green.
- No HIGH/CRITICAL finding from the review remains open.
- `docs/reviews/2026-06-15-rag-module-review.md` gets a closing status note once all waves land.

## Out of Scope

- The 6 verified-not-exploitable threat classes (XXE / SSRF / deserialization / zip-slip / ReDoS / encoding) — no change needed.
- Dynamic / fuzz testing of parsers (static review only this cycle).
- Refactors beyond the review's scope (retrieval/agent package, chat module, etc.).

## Technical Notes

- Review doc: `docs/reviews/2026-06-15-rag-module-review.md`
- GitNexus indexed as `smart-rag` — use impact/context tools per CLAUDE.md.
- Wave-specific numeric decisions (office `maxFileSize`, paragraph/slide caps, page sizes, auth strictness) are resolved in each child task's own brainstorm.
- Sequencing: W1 first (P0, unblocks untrusted exposure), then W2–W5 in priority order; later waves may proceed in parallel once W1 lands.
