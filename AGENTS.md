# Project context

Koko is a greenfield Java 25 JavaFX desktop application for learning Japanese vocabulary developed for an agentic software engineering course in an undergraduate computer science program. It maintains a global vocabulary library whose cards can be organized into one or more decks and practiced using spaced repetition.

# Default user context

Unless the user says otherwise, assume that you are assisting a student working on a project in this repository.

# Student profile

* Prior knowledge: Basic Java and OOP concepts.
* Level of programming experience: Junior
* IDE and level of expertise: IntelliJ IDEA, Beginner

# Guidance for interacting with users

* Explain the rationale for significant actions: what you did and why.
* Keep explanations brief but instructive, supporting learning through responsible use of AI. For example:

    * When suggesting a Git command, briefly explain what it does.
    * Add explanatory Javadoc comments to all classes and to nontrivial methods and fields when their purpose or behavior is not obvious.
    * Make generated code as self-explanatory as possible, and include explanatory comments where they improve understanding.
    * When faced with a design choice, choose the simplest option that is sufficient for the requirements, while briefly explaining relevant more advanced alternatives.

# Project-specific requirements

## Coding conventions

* Use American spelling in documentation, comments, Javadocs, user-facing messages, and project identifiers.
* Separate paragraphs with a blank Javadoc line. Start each paragraph after the first with `<p>` immediately before its first word. Omit closing `</p>` tags in prose paragraphs.
* End every Javadoc summary and each `@param`, `@return`, and `@throws` description with a period, including descriptions that span multiple lines.
* Use one `@throws` entry per exception and explain when it occurs. Declare checked exceptions that escape a method; do not add unchecked exceptions to method declarations.

## Java version:

Ensure that Java 25 is used when running the application or build tasks. On macOS, use `sdk use java 25.0.3.fx-zulu` to switch to Java 25 if needed.

## Build and verification

Run commands from the repository root using the Gradle wrapper.

* `./gradlew check` runs tests and Checkstyle. For focused tests, use `./gradlew test --tests koko.service.KokoServiceTest`, substituting the relevant test class.
* `./gradlew shadowJar` builds `build/libs/koko.jar`; `java -jar build/libs/koko.jar` launches it. Use `./gradlew run` for a development launch.
* Add or update tests with behavior changes. Before handing off code changes, run `./gradlew check`; rebuild the JAR when production code, resources, or build configuration changes. Use `./gradlew clean check shadowJar` for a clean verification run.
* Review the diff and run `git diff --check`. Documentation-only changes do not require application tests.
* Testing the GUI should be left to the developer.

## Git

* Keep commits focused on one logical change with its relevant tests.
* Use lightweight tags unless the user requests an annotated tag.
* When proposing or creating a commit message, include enough detail to explain the rationale for the change.
* Use an imperative, capitalized subject with no trailing period. Aim for about
  50 characters and never exceed 72.
* Add a body for non-trivial changes, separated by a blank line and wrapped at
  72 characters. Explain what and why; let the diff explain how.
* Do not commit or push unless explicitly asked.
