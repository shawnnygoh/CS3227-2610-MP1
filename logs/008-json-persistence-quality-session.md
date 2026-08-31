# Add and Review Versioned JSON Persistence

**Goal:** Add internal JSON persistence for Koko’s cards, progress, and ordered deck references. Then review it against NUS software-engineering guidance and improve important quality weaknesses.

**Scope:** Roughly eight substantive exchanges covering one focused persistence feature, testing, design review, rework, and documentation guidance.

**Key prompts:**

- “Add internal JSON persistence to Koko.” This required versioned UTF-8 JSON, stable identity restoration, complete validation, safe saves, and comprehensive JUnit tests.
- “How can I test if the implementation is accurate and working?” This shifted the session from implementation alone to requirements traceability, OOP, error handling, design patterns, and test-case design using the NUS references.
- “Can you proceed with all the suggestions you have given above?” This authorized targeted edge-case tests, restoration refactoring, and narrower exception handling, while keeping JavaFX integration out of scope.
- “We do not need to add JaCoCo reporting.” This explicitly rejected adding coverage tooling and kept the build change focused.

**What was done:** Added Jackson Databind, the `Storage` boundary, `StorageException`, and `JsonStorage` with private JSON DTO records. Added stable-identity restoration factories to `VocabularyCard` and `Deck`, plus `KokoData.restore` for uniqueness and referential-integrity rules. Added `data/` to `.gitignore`. Expanded `JsonStorageTest` to 15 tests covering round trips, ordering, shared/unassigned cards, invalid documents, domain violations, parent creation, and failed saves. Refactored restoration and narrowed expected exception handling.

**Decisions and trade-offs:** Jackson was chosen instead of handwritten JSON, with DTOs outside the domain. Strict parsing rejects unknown fields, duplicate properties, trailing content, and unsupported versions. Saves serialize first, write a neighboring temporary file, and replace the target atomically when supported. JaCoCo and JavaFX integration were not added; the non-atomic move fallback remains a risk.
