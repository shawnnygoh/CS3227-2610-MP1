# Transactional JSON Deck Transfer

**Goal:** Implement Koko’s headless, portable JSON deck import/export increment. The work had to preserve global card identity, independent learning progress, existing review behavior, and transactional persistence without adding UI controls.

**Scope:** One focused implementation task across one user prompt and the resulting inspection, coding, testing, and handoff exchanges.

**Key prompts:**

- “Implement only the headless portable JSON Transfer increment for Koko.” This constrained the work to production behavior and tests for transfer, excluding the later UI increment.
- “Read AGENTS.md, README.md, Git status/history, relevant production code and tests, the Gradle configuration, and the CI workflow before editing.” This required establishing the clean prerequisite baseline and understanding existing domain and persistence rules first.
- “Build a detached candidate ... call storage.save(candidate) once, and publish only after it succeeds.” This defined the failure-atomic import design and prevented chaining existing save-per-operation service methods.
- “Reject ... unknown properties, duplicate JSON properties, trailing documents, malformed JSON/UTF-8.” This drove strict Jackson/tree parsing, explicit UTF-8 decoding, and portable-schema validation.
- “Do not ... launch the GUI, stage, commit, push, or begin UI integration.” This kept the session headless and review-ready.

**What was done:** Confirmed the clean starting commit as `0d0bb44`, then added `koko.transfer.DeckTransfer`, `DeckTransferException`, and portable `PortableDeck`/`PortableCard` DTOs. Added `KokoService.importDeck(Path)` and `exportDeck(UUID, Path)`. Extracted shared domain name, content-validation, and identity helpers; imports copy the complete state, resolve existing cards globally, add only new cards, save once, and publish after success. Exports serialize only the selected deck and use `CREATE_NEW` with incomplete-file cleanup. Added focused transfer/service tests plus domain helper tests.

**Decisions and trade-offs:** Used one checked transfer exception for recoverable file, encoding, format, serialization, and destination failures, while preserving `StorageException` for persistence. Used strict JSON tree validation instead of internal `JsonStorage` DTOs. Rejected UI changes, schema changes, progress transfer, multi-deck files, and public mutation-method chaining.
