# Integrate Flashcard Review into the JavaFX Interface

**Goal:** Connect the reviewed, persistent Flashcard session to Koko's JavaFX
management UI while preserving its scheduling, persistence, guards, and
summary behavior.

**Scope:** Five user/assistant exchanges covering one focused implementation,
manual-test handoff, and two behavior clarifications.

**Key prompts:**

- “Integrate the reviewed Flashcard session into Koko's JavaFX interface.” This
  defined the main feature and constrained the work to the existing headless
  session/service APIs.
- “Keep one active review ... and allow retry or Stop after save failure.” This
  required UUID-bound actions, management locking, and no controller-side
  persistence duplication.
- “Let me know step by step what to test on my end.” This shifted verification
  to a developer GUI checklist rather than unsupported automated GUI tests.
- “Wouldn't it make more sense ... to review the deck as a whole?” This prompted
  an explanation of global progress and spaced-repetition trade-offs.

**What was done:** Added `ReviewController`, `ReviewView.fxml`, review CSS,
management entry buttons, shared FXMLLoader construction, and a classpath
resource test. Deck review creates a frozen due-card session; selected-card
review bypasses due status for one global card. The UI renders prompt, reveal,
answer, stop, retryable save error, progress, and terminal summary states.
Management lists refresh by UUID after return.

**Decisions and trade-offs:** Kept scheduling and outcomes in
`FlashcardSession`/`KokoService`; the controller only renders snapshots and
forwards displayed IDs. Kept due-based deck review plus selected-card override
instead of adding a new “review all” mode. Kept the existing `Open` button
unchanged for scope preservation, although follow-up discussion established
that it is currently redundant and only updates guidance.
