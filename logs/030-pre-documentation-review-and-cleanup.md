# Pre-documentation Review and Cleanup

**Goal:** Review Koko's code and tests against repository conventions, the project brief, development plan, and course software-engineering guidance. Fix important issues and simplify unnecessary code before manual testing and documentation.

**Scope:** Roughly 20 exchanges covering a connected review, incremental fixes, compatibility cleanup, and documentation planning.

**Key prompts:**

- “The review should include both code and tests and also look out for dead or unnecessary code.” This requested a checklist-based assessment of correctness and maintainability.
- “Okay can you fix the bugs and do the optional cleanup? Split them up into well-scoped commits.” This turned findings into separate, reviewable changes.
- “Assume that there are no older app versions yet since we have not released it yet.” This challenged unnecessary compatibility work before the first release.

**What was done:** Produced a review checklist/report. Updated `VocabularyCard` validation to reject embedded controls, with storage/import regressions and clearer errors. Deferred typing focus until rendering, with guards against stale callbacks. Removed unused `TypingSession` getters and redundant `ReviewQueue` deduplication; changed mode-independence tests to compare values. Added JAR packaging to CI. Reduced `ModeProgress` and stored progress to mastery/due date, updating scheduling, persistence, help, and tests. Removed only `KokoService.importDeck(Path)`; tests now use preparation followed by importing a document under a confirmed name. Supplied a future documentation prompt recommending separate UserGuide and DeveloperGuide commits.

**Decisions and trade-offs:** Kept session counters, strict file validation, atomic saving, and distinct export-overwrite safeguards. Rejected migration for unreleased development files; obsolete fields cause rejection without rewriting files. Preserved the UI's existing import workflow.
