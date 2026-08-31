# Documentation Rewrite and Cross-Platform Release JAR

**Goal:** Restructure the Developer Guide, User Guide, and README to follow the AB3/tp format with every claim verified against the code, condense the in-app Help window, and produce a release JAR that runs on all platforms.

**Scope:** One long session, roughly a dozen substantive instructions, covering documentation, an FXML change, Gradle packaging, and finally commit planning plus prompts for the outstanding logs and reflection work.

**Key prompts:**

1. "I would like the Developer Guide to follow a similar structure... Most importantly, I would like to ensure that everything within it is accurate." — set AB3 as the target structure and made accuracy rank above completeness.
2. "maybe you can use Mermaid diagrams instead... you should be referencing the Project Brief found here" — a mid-task correction that changed the diagram tooling and supplied the grading criteria I had not asked for.
3. "can you do another review over all three files to ensure that they are accurate and consistent before I commit them?" — a dedicated verification pass, treated as its own task rather than trusting the first draft.
4. "is there any way we can create the release jar file which is supported on all platforms" — from an updated brief requiring `release/`.
5. "The developer guide says the repo has 16 test classes but I see 18 test files?" — caught a vague number I had written.

**What was done:** Rewrote `docs/DeveloperGuide.md` (~600 lines, AB3 sections, five Mermaid diagrams) after reading every main source file; rewrote `docs/UserGuide.md` into Quick start / Features / FAQ / Known issues / Action summary; rewrote `README.md` with a CI badge and screenshot; deleted `docs/diagrams/`; cut `HelpView.fxml` by about 60%; added a `releaseJar` Gradle task producing `release/koko.jar` (13 MB, Windows x64 + Apple Silicon macOS + x64 Linux), wired into CI and AGENTS.md.

**Decisions and trade-offs:** Mermaid over PlantUML — no PlantUML tooling locally, GitHub renders Mermaid natively, and the repo is not a Jekyll site. A trimmed Requirements appendix with only three use cases. For the JAR, one architecture per platform is a hard limit because JavaFX ships identically named natives for x64 and ARM; chose Apple Silicon over AB3's Intel-Mac set. Each platform needs its own Gradle configuration, since the variants conflict on capabilities.
