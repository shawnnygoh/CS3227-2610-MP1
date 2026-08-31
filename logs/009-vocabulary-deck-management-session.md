# Vocabulary and Deck Management Session

**Goal:** Connect Koko's global vocabulary and deck model to a persistent JavaFX workflow. Deliver validated card editing, reusable deck cards, clear feedback, and a clean three-commit history.

**Scope:** About twenty exchanges on one feature's implementation, review, correction, and verification.

**Key prompts:**

- “Add vocabulary and deck management to Koko’s JavaFX interface.” This defined the service, persistence, JavaFX interface, tests, and management journey.
- “Can you implement ... Character validation rules, deck deletion ... smaller window sizes and Help placement?” This added concrete usability and domain corrections.
- “Address the review findings ... normalization ... dialog failure ... schema-version-1 data ... and commit-splitting plan.” This required regression coverage, rollback tests, compatibility decisions, and boundaries.
- “Strengthen the normalization duplicate tests ... Javadocs ... isolated checkout ... GUI verification.” This drove the final tests, documentation style, and build evidence.

**What was done:** Added `KokoService` with injectable `Storage` and `Clock`, one-save mutations, and rollback on persistence failure. Added deck deletion to `KokoData`. Replaced the JavaFX shell with startup loading, an FXML/CSS management view, and a controller for cards, decks, confirmations, Help, empty states, and retryable dialogs. Added NFC normalization, field rules, validation, restoration checks, and tests. Replaced user-facing “membership” wording with “cards.”

**Decisions and trade-offs:** Cards remain globally owned and may appear in multiple decks; removing a card from a deck does not delete it globally, while global deletion removes every deck entry. Strict character rules also apply to schema-version-1 restoration; no migration was added. Commit boundaries were service, interface, then character policy, with the interface depending on the service.
