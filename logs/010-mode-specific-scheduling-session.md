# Mode-Specific Mastery Scheduling Session

**Goal:** Implement Koko's pure, mode-specific SRS-lite scheduling increment. The scheduler needed to produce immutable progress snapshots while leaving card state, persistence, and review UI integration for a later increment.

**Scope:** About six substantive interactions covering one focused task: inspection, implementation, testing, and verification.

**Key prompts:**

- “Implement Koko's pure, mode-specific SRS-lite scheduling increment.” This set the feature boundary and kept the work focused on scheduling logic rather than a complete review workflow.
- “Scheduling accepts one ModeProgress, an outcome, and an explicit LocalDate review date, returning a new immutable progress snapshot.” This specified the small functional API and made overdue-date behavior deterministic.
- “Keep this increment pure: do not mutate the input or either card progress record.” This deliberately separated calculation from the later service, persistence, and UI application step.
- “Add deterministic tests covering every outcome at mastery 0–5, all intervals, counters, cap/floor behavior, overdue reviews, due-date boundaries, month/year/leap-day transitions, null arguments, and input immutability.” This defined the regression boundary for the implementation.
- “Using Java 25, run: GRADLE_USER_HOME=/tmp/koko-gradle ./gradlew clean check shadowJar; git diff --check.” This required reproducible build, quality, packaging, and whitespace verification before handoff.

**What was done:** Read `AGENTS.md`, inspected the existing immutable `ModeProgress`, independent card mode records, service, and tests, and confirmed the initial worktree was clean. Added `ReviewOutcome`, `ReviewScheduler`, and `MasteryScheduler` under `koko.service`. Correct reviews increase mastery with a cap of five and use 1/3/7/14/30-day intervals based on resulting mastery; incorrect and skipped reviews schedule the following day. Every outcome records the actual review date. Added `MasterySchedulerTest` with mastery-range, counter, boundary-date, transition, null, immutability, overdue, and mode-isolation coverage.

**Decisions and trade-offs:** Reused `ModeProgress` as the immutable value object and made the scheduler accept one progress record rather than a card or mode, so it cannot accidentally update the other mode. Kept service mutation, persistence, and UI review-session behavior out of scope as explicitly requested.
