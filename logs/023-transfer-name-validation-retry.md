# Transfer Name Validation and Retry

**Goal:** Complete Koko’s focused Transfer naming increment: suggest export filenames, allow imported deck names to be edited independently, close the confirmed-name Unicode validation gap, and strengthen prepared-document retry coverage.

**Scope:** One focused development task with iterative verification; no unrelated features were pursued.

**Key prompts:**

- “Complete Koko’s existing Transfer naming increment, including its validation fixes, as one focused commit candidate.” This set the narrow feature boundary and required preserving all existing work.
- “Add the smallest service-owned valid-Unicode check for the confirmed name.” This placed the fix at the service boundary, before candidate creation, date sampling, or saving.
- “Prepare once, fail saving, change or delete the source, advance an injected clock, and retry the SAME immutable document under an edited name.” This required proving retries do not reread or mutate the source and still use the retry date.
- “Do NOT implement export replacement in this session.” This preserved `CREATE_NEW` protection and the existing no-overwrite contract.

**What was done:** Verified the `60b5548` baseline, empty index, existing modifications, and untracked transfer files. Added UTF-8 encodability validation for confirmed names in `KokoService`. Expanded `KokoServiceTransferTest` with surrogate, valid-Unicode, invalid-document, direct DTO, correction, snapshot, and failed-save retry cases. The increment also covered `TransferFileNames`, editable import UI, suffix handling, JSON-only filters, Help text, and transfer validation.

**Decisions and trade-offs:** Kept validation of the original portable document before name confirmation. Kept filename policy in the controller helper, deck identity independent from filenames, immutable prepared documents, copy-save-publish persistence, and `CREATE_NEW` export behavior. Export replacement, metadata changes, resizing, and broader cleanup were rejected.
