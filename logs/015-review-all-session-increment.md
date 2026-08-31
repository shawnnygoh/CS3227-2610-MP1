# Review All Session Increment

**Goal:** Replace the redundant deck Open action with Review all, while preserving due-only and selected-card review behavior, shared scheduling, frozen queues, and retry-safe persistence. Review the resulting changes and prepare them for commit.

**Scope:** Roughly five user/assistant exchanges covering one focused feature and verification task, followed by commit-message and development-log follow-ups.

**Key prompts:**

- “Implement one cohesive Koko increment: replace Open with Review all.” This defined the feature, its exact UI label, session semantics, and strict implementation boundaries.
- “Implement the behavior and corresponding tests together as one increment.” This required extending the headless session tests alongside production code, especially for ordering, freezing, scheduling, and save failures.
- “I've made some changes. Can you have a look at them and let me know if we're ready for commit?” This changed the later work from implementation to review of the added Help view, styling, resource wiring, and parameterized tests.

**What was done:** Read project guidance, README, history, source, tests, resources, build configuration, and workflow. Added an all-card deck factory to `FlashcardSession`, which snapshots unique IDs in membership order and reuses the existing lifecycle and service persistence path. Replaced the MainWindow Open button and handler with Review all, added guards, neutralized empty-queue feedback, and updated Help/guidance text. The session tests were expanded for future and empty decks, frozen queues, repeated/shared progress, early outcomes, typing isolation, stop behavior, and failed-save retry. The later review also checked the new `HelpView.fxml`, CSS, resource test, and parameterized coverage.

**Decisions and trade-offs:** Kept queue selection in the session layer rather than JavaFX controllers. Reused `recordFlashcardOutcome` and the existing scheduler instead of adding practice-mode or session-type infrastructure. Kept the existing due ordering and selected-card entry points unchanged.
