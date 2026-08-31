# Domain Model and JUnit Session

**Goal:** Add Koko's first UI-independent domain increment and JUnit 5 support. Establish globally owned vocabulary cards, reusable deck memberships, independent learning progress, and focused automated tests.

**Scope:** One focused implementation task with several code-quality, sequencing, and commit-message follow-ups; no unrelated feature work.

**Key prompts:**

- “Implement Koko’s first UI-independent domain increment and add JUnit 5 support.” This defined the model invariants, dependency restrictions, tests, and verification gate for the increment.
- “How can I verify that the implementation is correct and follows good software engineering practices?” This prompted a review against CS2103/T guidance for OOP, patterns, exceptions, readability, and test design.
- “Can you proceed with all the suggestions you think are important?” This authorized fixing failure atomicity and strengthening exception, lookup, boundary, and non-mutation tests.
- “Make the javadocs clearer for the parameters we mentioned above.” This focused a final documentation improvement before commit.

**What was done:** Recorded starting commit `71eb3b4`, inspected the repository and supplied planning documents, and added JUnit Jupiter 5.14.1 with the matching platform launcher. Created `Mode`, immutable validated `ModeProgress`, `VocabularyCard`, `Deck`, and `KokoData` under `koko.model`. Added tests for text validation and NFC normalization, identity, progress, uniqueness, membership, deletion, ordering, read-only collections, editing, and referential integrity. Updated card parameter Javadocs for clarity.

**Decisions and trade-offs:** Used composition and package-controlled mutation instead of speculative patterns. `KokoData` coordinates cross-object invariants; decks store UUID references; progress records are immutable; creation dates are explicit. Did not add `ReviewProgress`, UI behavior, persistence, CI, or packaging.
