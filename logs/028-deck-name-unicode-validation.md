# Deck Name Unicode Validation

**Goal:** Fix Koko’s confirmed defect where an internal JSON deck name containing an unpaired Unicode surrogate loaded successfully but caused a later save to fail. Preserve valid Unicode, including supplementary characters, and keep the change focused at the shared deck-name boundary.

**Scope:** About five user-facing exchanges before this log request, covering one focused implementation task, regression testing, verification, and commit-message preparation.

**Key prompts:**

- “Implement a focused fix for Koko’s deck-name Unicode validation.” This set the defect, repository, and requirement to inspect the actual current implementation before changing it.
- “Implement the smallest sufficient Unicode validation at the shared deck-name boundary, covering creation, rename, and restoration.” This constrained the design to the existing domain boundary rather than a new validation framework.
- “Add relevant domain, service, and storage regressions ...” This required distinct checks for pre-mutation rejection, retained references, byte preservation, supplementary-character round trips, and existing import behavior.
- “Use Java 25.0.3.fx-zulu and the Gradle wrapper. Run focused tests, then `./gradlew clean check shadowJar` and `git diff --check`.” This defined the required verification and prohibited GUI/runtime-data testing.

**What was done:** Read `AGENTS.md` and `README.md`, confirmed the starting worktree was clean at the preceding JSON-type-validation commit, and inspected the actual model, service, storage, Transfer, and tests. Added unpaired-surrogate detection to `Deck.normalizeName`, which is shared by construction, rename, and restoration. Removed redundant deck-name encoder checks from `KokoService` and `DeckTransfer`. Added focused tests in `DeckTest`, `KokoServiceTest`, and `JsonStorageTest`.

**Decisions and trade-offs:** Used a small UTF-16 pair scan at the existing domain boundary. Kept trimming, case-insensitive uniqueness, spaces, valid supplementary characters, import preparation/editing, suffix handling, native overwrite confirmation, schemas, and the prior JSON type fix unchanged. Rejected a generic validation framework and unrelated cleanup.
