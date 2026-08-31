# JavaFX Application Shell Session

**Goal:** Replace Koko's temporary console entry point with a minimal JavaFX 25 application shell using FXML and CSS. The shell needed to display an honest empty deck-library state while remaining ready for later feature work.

**Scope:** Roughly eight user/assistant exchanges covering one focused implementation task, verification, and commit-message discussion.

**Key prompts:**

- “Inspect AGENTS.md, Git status, build.gradle, the current entry point, and the linked guidance. Do not modify files yet.” This required a read-only baseline and prevented implementation from silently copying the tutorial's JavaFX 17 examples.
- “Implement the JavaFX/FXML application shell from the specification you just proposed.” This established the approved file structure, JavaFX 25 dependency approach, classpath resource loading, and exact scope.
- “Do not add a controller solely to establish a pattern.” This deliberately kept the behavior-free shell free of speculative controllers and future navigation.
- “Run java -version... clean check... start the application... use Computer Use.” This defined the required Java, build, runtime, and GUI verification evidence.

**What was done:** Updated `build.gradle` with the OpenJFX Gradle plugin, JavaFX 25, `javafx.controls`, `javafx.fxml`, and `koko.Launcher` as the main class. Deleted `src/main/java/koko/Koko.java`; added documented `Launcher` and `KokoApplication` classes. Added `MainWindow.fxml` and `koko.css` under `src/main/resources/koko/`. The root FXML contains only a centered “No decks yet” label. `KokoApplication` loads FXML and CSS through classpath URLs, sets a 720x480 resizable stage with 420x280 minimum dimensions, and throws clear missing-resource exceptions.

**Decisions and trade-offs:** Used the platform-aware OpenJFX plugin instead of manually listing native classifiers. JavaFX 25 replaced the tutorial's incompatible 17.0.7 example. No Help dialog, controllers, controls, fake data, domain logic, persistence, tests, or packaging changes were added.
