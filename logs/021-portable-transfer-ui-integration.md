# Portable Transfer UI Integration

**Goal:** Integrate the existing headless portable JSON Transfer feature into JavaFX, limited to this UI increment and preserving service-owned behavior and review controls.

**Scope:** One focused implementation task across one user prompt and the resulting inspection, coding, testing, and handoff exchanges; no unrelated work.

**Key prompts:**

- “Integrate Koko’s existing headless Transfer feature into JavaFX. Complete only this UI increment, then stop for developer review.” This fixed the scope and prohibited unrelated service or domain changes.
- “Read AGENTS.md and README.md first. Inspect current Git status, HEAD, history, both diffs, and untracked files before editing.” This required verifying prerequisites and protecting existing work.
- “Use JavaFX FileChooser with a JSON extension filter” and return immediately on cancellation. This defined the owned chooser flow and its no-op cancellation behavior. The prompt also required proportionate structural checks in `ResourceWiringTest` without starting JavaFX.

**What was done:** Confirmed the clean starting tree and prerequisite commits: `d4dfa87` (`Add portable JSON deck transfer`), `55f2633` (`Publish management changes only after saving`), and `089da79` (`Reject unsafe non-atomic storage replacement`), based on `0d0bb44`. Added a Transfer `MenuButton` beside Help with import and selected-deck export items. `MainController` now owns JSON open/save choosers, passes the application window as owner, guards startup/review/selection state, calls the service APIs, refreshes and selects imported decks by UUID, preserves review mode, and shows distinct owned errors for invalid data, unreadable input, conflicts, save failures, and export failures. Help and structural FXML tests were updated. CSS was unchanged because current styling was sufficient.

**Decisions and trade-offs:** Kept parsing, normalization, validation, conflict detection, and persistence in the existing service/portable layer. Export passes a selected UUID and relies on `CREATE_NEW`, never deriving a path from the deck name. Exception causes were inspected to distinguish invalid data from unreadable input without expanding the service API. GUI acceptance remained with the developer.
