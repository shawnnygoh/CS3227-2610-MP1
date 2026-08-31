# Internal JSON Type Validation

**Goal:** Reject malformed scalar values at Koko’s internal JSON storage boundary before Jackson can coerce them into typed records and later persist the corrupted values.

**Scope:** Three user-request exchanges over one focused storage-validation task, followed by commit-message preparation; no unrelated feature work.

**Key prompts:**

- “Implement a focused fix for Koko’s internal JSON type validation.” This defined the defect, required boundary validation, and limited the change to internal storage behavior.
- “Add focused regression tests using otherwise-valid synthetic documents with one invalid property per case.” This required coverage for both learning modes, string fields, byte preservation, service-state preservation, and valid round trips.
- “Can you provide the full git command, with a message that is more aligned with past commit messages?” This shifted the final interaction toward a copy-pasteable, scoped commit command without authorizing the assistant to commit.
- “Are we sure this follows recent commit conventions? Can we make it more clear and succinct?” This prompted inspection of recent history and refinement of the subject to `Reject invalid internal JSON types`.

**What was done:** Read `AGENTS.md` and `README.md`, confirmed the starting HEAD (`8ccaa8e`), and inspected status and diffs before editing. `JsonStorage.load()` now parses a JSON tree, checks integer and string node types recursively, then uses the existing typed conversion and restore validation. `JsonStorageTest` gained a positive synthetic schema-one round trip and 16 parameterized malformed-property cases covering schema version, every progress integer in both modes, card/deck strings, dates, and membership IDs. The tests use temporary files and assert rejected loads preserve both raw bytes and the service’s existing state.

**Decisions and trade-offs:** Kept the correction at the internal storage boundary, preserving the schema, portable `DeckTransfer` behavior, duplicate/unknown/trailing checks, optional null dates, and domain validation. A JSON-tree gate was chosen over changing domain records or applying broader mapper behavior. Real runtime data and the GUI were deliberately not used.
