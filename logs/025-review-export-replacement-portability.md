# Review and Windows Export Replacement

**Goal:** Review and simplify native-confirmed export replacement, then resolve Windows CI failures while preserving the intended save-dialog experience.

**Scope:** Roughly a dozen substantive exchanges covering one increment, its review, simplification, portability fixes, and commit wording.

**Key prompts:**

- “Perform a final read-only review of the current Koko increment.” Required checking safety and scope without altering work.
- “Can you implement the necessary fixes, then provide the git commands for this commit?” Authorized corrections without authorizing commits.
- “Why have we added so many lines in this commit, and is everything actually necessary?” Challenged complexity against the linked NUS software-engineering textbook.
- “Can you figure out what the issue is and fix it?” Requested diagnosis of the failing GitHub Actions run.
- “What if I want the same behavior on all platforms” Established consistent native-confirmed replacement as the priority.

**What was done:** Reviewed history, diffs, prerequisites, code, tests, and Help. Fixed linked-parent path handling, captured destination state before serialization, and tied confirmation to the final destination. Simplified redundant path arguments and converted failure loops into five named parameterized cases. Consulted the textbook and Java documentation. Diagnosed Windows failures, corrected cross-drive and symbolic-link test assumptions, and added missing-identity replacement tests.

**Decisions and trade-offs:** Retained atomic sibling-temp replacement, cancellation, storage protection, and existing filename behavior. The final policy permits replacement without file keys, using available metadata for change detection. A different file with matching size and timestamp can therefore escape detection. Rejected additional confirmation dialogs, non-atomic fallbacks, and a broader writing framework.
