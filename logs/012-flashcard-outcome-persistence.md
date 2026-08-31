# Flashcard Outcome Persistence Session

**Goal:** Implement persistent recording of `CORRECT` and `INCORRECT` Flashcard outcomes in Koko. The operation had to update only Flashcard progress and remain safe when scheduling or persistence failed.

**Scope:** About ten substantive exchanges covering one focused implementation task, its tests and verification, followed by commit-message clarification.

**Key prompts:**

- “Implement only persistent Flashcard outcome recording in Koko.” This set the feature boundary and excluded session queues, JavaFX changes, and typing behavior.
- “A failed save or scheduler error must leave both current service data and previously exposed objects unchanged.” This required a detached candidate transaction rather than reusing the existing live-object mutation helper.
- “Use the existing scheduler and the service's injected Clock.” This preserved the established scheduling policy while requiring a fresh actual review date for every submission.
- “Add focused JUnit tests with production changes.” This required coverage for outcomes, dates, isolation, invalid inputs, failures, retries, and JSON round-tripping.

**What was done:** Read `AGENTS.md`, `README.md`, Git history/status, the service, domain models, scheduler, storage, and tests. Added `KokoService.recordFlashcardOutcome(UUID, ReviewOutcome)`. It validates inputs, samples `LocalDate.now(clock)`, copies the complete data graph, schedules and replaces only the candidate card's `FLASHCARD` progress, saves once, and publishes the candidate only after saving succeeds. Added service tests and a `JsonStorage` `@TempDir` round-trip test. The test storage double now deep-copies saved data and counts attempted and successful saves separately.

**Decisions and trade-offs:** Reused `MasteryScheduler`, `VocabularyCard.updateProgress`, and existing copying facilities. Kept the older management transaction helper unchanged to preserve its existing contracts. Rejected UI/session integration and schema changes as outside this increment.
