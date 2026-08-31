# Executable Shadow JAR Packaging

**Goal:** Add executable fat-JAR packaging to Koko so it could be launched with `java -jar`, while preserving the existing JavaFX application, resources, tests, and CI workflow.

**Scope:** One focused packaging task across roughly eight exchanges, with implementation, verification, and configuration simplification.

**Key prompts:**

- “Add executable Shadow JAR packaging to Koko.” This defined the main deliverable: a Shadow JAR containing the launcher, runtime dependencies, FXML, and CSS.
- “Start only from a clean working tree and preserve unrelated user changes. Do not commit or push.” This established the safety boundary around the pre-existing `AGENTS.md` and `README.md` changes.
- “Can we not just follow what the tutorial has instead?” This redirected the implementation toward the tutorial’s simpler configuration instead of retaining extra packaging settings.
- “Can it not literally just be this?” This required the final Gradle syntax to match the tutorial exactly: `shadowJar { archiveFileName = 'koko.jar' }`.

**What was done:** `build.gradle` was changed to apply `com.gradleup.shadow` version `9.6.1` and configure `shadowJar` to output `koko.jar`. The existing `application` plugin provided `koko.Launcher` as the manifest main class, while the JavaFX plugin resolved platform runtime dependencies. No application source, resources, tests, or workflow files were changed.

**Decisions and trade-offs:** The final configuration used the current tutorial-style form rather than explicit archive naming, classifier, manifest, and reproducibility settings. The all-platform JavaFX dependency block from the tutorial was rejected because bundling multiple platforms’ native libraries into one JAR would not reliably produce a cross-platform executable. The result is platform-specific, using macOS ARM64 JavaFX libraries.
