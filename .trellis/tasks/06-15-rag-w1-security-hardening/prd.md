# RAG review W1: security hardening

Parent: `06-15-remediate-all-rag-module-review-findings` · Priority: **P0** — unblocks untrusted-upload exposure.

## Goal

Resolve the 4 security findings that block exposing the RAG ingestion path to untrusted uploads.

## Findings (from `docs/reviews/2026-06-15-rag-module-review.md`)

- **R2-H1** Chunk-upload bypasses magic-byte MIME verification → confused deputy
- **R2-H2** OOXML parsers lack explicit zip-bomb defenses (no `ZipSecureFile` pin, no paragraph/slide caps)
- **R2-Dep** `commons-compress` version drift (CVE-2024-25710/26308 mitigation unverified)
- **R2-M3** Plain-text / encoding parsers unbounded read (`contentLength()=-1`)

## Decisions (locked 2026-06-15)

- **R2-H1** — in `ChunkUploadServiceImpl.performMerge`, after `composeObject`, detect MIME on the merged object; route the parser and persist on the **detected** MIME (not the session-stored declared value). Refactor `DocumentValidator.detectMimeType` into a reusable `detectMimeType(InputStream)`.
- **R2-H2** — pin `ZipSecureFile` thresholds in a `@PostConstruct` (`setMinInflateRatio(0.01)`, `setMaxEntrySize`, `setMaxTextSize`); add `MAX_PARAGRAPHS = 50_000` (`DocxDocumentParser`) and `MAX_SLIDES = 5_000` (`PptDocumentParser`) count caps that throw on exceed. `maxFileSize` stays 50MB.
- **R2-Dep** — add `commons-compress` ≥1.27.x to `<dependencyManagement>`; verify with `mvn dependency:tree -Dincludes=org.apache.commons:commons-compress`.
- **R2-M3** — wrap input in `BoundedInputStream(maxFileSize)` in `PlainTextDocumentParser` and `EncodingDetector`; stop relying on `contentLength()`.

## Acceptance Criteria

- [ ] Chunk-upload path: a declared-MIME-mismatch upload is rejected/routed by **detected** MIME; integration test covers it.
- [ ] A zip-bomb-ish docx/pptx (huge empty-paragraph count) throws within the cap, not OOM; test covers it.
- [ ] `mvn dependency:tree` shows `commons-compress` ≥1.27.x.
- [ ] Plain-text parser reading > `maxFileSize` throws, not OOM; test covers it.
- [ ] `/ecc:verify` green; `gitnexus_detect_changes` shows only expected symbols/flows touched.

## Implementation Plan (ordered)

1. `pom.xml`: `commons-compress` ≥1.27.x in `<dependencyManagement>` → `mvn dependency:tree` verify.
2. `DocumentValidator`: extract reusable `detectMimeType(InputStream)`.
3. `ChunkUploadServiceImpl.performMerge`: detect MIME on merged object → route + persist on detected MIME.
4. New `ZipSecurityConfig` (`@PostConstruct`): pin `ZipSecureFile` thresholds.
5. `DocxDocumentParser` / `PptDocumentParser`: `MAX_PARAGRAPHS` / `MAX_SLIDES` caps.
6. `PlainTextDocumentParser` / `EncodingDetector`: `BoundedInputStream`.
7. Tests for each fix (unit + integration for the upload path).
8. `/ecc:verify` + `gitnexus_detect_changes` + commit.

## Definition of Done

- Tests added per fix; CI green; no HIGH finding from R2 remains open.

## Out of Scope

- R2-M1 (OpenDataLoader size precheck) → W3.
- R2-L1 / R2-L2 / R2-L3 (chunk `byte[]` / pptx table dims / docx exception type) → W2 / W5.
