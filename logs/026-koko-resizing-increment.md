# Koko Resizing Increment

**Goal:** Fix developer-reported resizing defects in Home, Flashcard review, and Typing review while preserving the 900×620 initial Scene and 760×560 Stage minimum. Keep long content and controls reachable without changing domain or persistence behavior.

**Scope:** Seven user-facing exchanges over one focused layout task, followed by testing, manual GUI feedback, checklist/commit preparation, and this log request.

**Key prompts:**

- “Implement only Koko’s resizing increment.” This constrained the work to the approved presentation increment and required inspection of existing code, resources, tests, build configuration, Checkstyle, and CI.
- “At minimal window sizing ... ‘Select a deck to see its cards’ disappears ...” This identified Home compression and required a layout solution rather than increasing the window to hide the defect.
- “The Add existing card and Remove card from deck buttons now disappear ... Review due ... buttons are also overlapping.” This second observation rejected the first workaround as insufficient and redirected the Home layout toward fixed action rows with scrollable list content.

**What was done:** Changed `MainWindow.fxml`, `ReviewView.fxml`, `TypingReviewView.fxml`, and `koko.css`. Home action groups became wrapping panes; list cells wrap long text; Home panels and membership content gained constrained scrolling; review content moved behind scroll panes while progress/actions remain protected; review labels and summaries are centered; progress bars have minimum height. `MainController`, `ReviewController`, and `TypingReviewController` received presentation-only cell, scroll-reset, and prompt/feedback visibility changes. `ResourceWiringTest` gained targeted scroll-pane and handler compatibility checks.

**Decisions and trade-offs:** Kept the minimum Stage size unchanged. Rejected merely shrinking fonts or clipping content. The final Home design keeps the right panel fixed, scrolls the membership list area, and leaves both deck action rows outside that viewport so Sensei cannot cover them.
