# Native Export Replacement Session

**Goal:** Implement native-confirmed export replacement as a separate Koko increment. Preserve the existing filename and portable-transfer behavior while allowing a native-confirmed overwrite to replace only the confirmed final destination safely.

**Scope:** One focused task over roughly a dozen substantive exchanges, with inspection, implementation, testing, and review.

**Key prompts:**

- “Implement native-confirmed export replacement as a separate Koko increment.” This defined the feature boundary and separated it from the completed Transfer naming increment.
- “Keep create-new behavior as the default for unconfirmed/new destinations.” This preserved no-overwrite behavior unless the native chooser supplied confirmation.
- “Create, write, and close an operation-owned sibling temporary file. Replace the existing export only after that succeeds.” This required atomic, failure-preserving replacement.
- “Protect Koko’s actual configured internal storage target and aliases at the service boundary.” This required relative/absolute paths, links, case aliases, and hard links without duplicating the storage pathname.
- “Stop for review. Report changes, observed automated results, remaining developer checks, and provider limitations. Do not commit.” This explicitly ended the implementation at review handoff.

**What was done:** Added `DeckTransfer.ConfirmedDestination`, atomic sibling-temp replacement, target identity/snapshot checks, nonregular/symlink rejection, and recoverable cleanup. Added configured-path exposure and service-level internal-storage alias protection. Updated `MainController`, Help text, and transfer/service/storage tests for replacement, mismatch, retries, appearing files, target changes, symlinks, and aliases.

**Decisions and trade-offs:** Used the existing `File`/`null` chooser contract rather than inventing an overwrite flag or platform detector. Actual-file identity uses `Files.isSameFile`; detectable target changes are rechecked before atomic replacement. Unsupported atomic replacement fails recoverably. `JsonStorage`’s save algorithm, schemas, GUI test framework, backups, and non-atomic fallbacks were left unchanged.
