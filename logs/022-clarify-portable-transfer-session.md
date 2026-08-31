# Clarify Portable Transfer Workflow

**Goal:** Implement the focused Transfer usability increment: suggest export filenames, distinguish filenames from embedded deck names, and let users confirm or edit an imported deck name while preserving validation and transactional persistence.

**Scope:** One focused implementation task followed by one clarification exchange; no unrelated features were pursued.

**Key prompts:**

- “Implement one focused Transfer usability increment for Koko.” This defined the narrow feature boundary and explicitly excluded statistics, sorting, resizing, metadata removal, and other cleanup.
- “Suggest a filename from the selected deck name and show an editable import-name confirmation.” This framed the UI change around the usability problem while keeping portable DTOs and storage free of UI policy.
- “Use a service-facing prepare/read operation followed by applying that immutable document under the confirmed name.” This required validation and persistence to remain in the service and prevented rereading a changed source file.
- “Remove the guidance of ‘The filename did not change the embedded deck name’.” This follow-up removed redundant success text while retaining the explanation where it helps the import workflow.

**What was done:** Verified the clean 60b5548 baseline and prerequisite commits. Added TransferFileNames and tests for Unicode, spaces, invalid characters, fallback names, reserved basenames, suffixes, and parent paths. Split service import into preparation and application, added public portable-document validation, and retained copy-save-publish behavior. Updated MainController with filename suggestions, .json normalization, actual-path feedback, and an owned editable import dialog that keeps recoverable errors and retries in place. Updated Help and transfer/service tests.

**Decisions and trade-offs:** Kept embedded deck names because filenames are transport details that users can rename or move; deriving identity from filenames would make renaming files alter the default deck name. Kept strict no-overwrite behavior even when the native chooser offers replacement confirmation. Rejected resizing and broader transfer redesign.
