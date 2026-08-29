# Project context

Koko is a greenfield Java 25 JavaFX desktop application for learning Japanese vocabulary developed for an agentic software engineering course in an undergraduate computer science program. It allows users to keep track of Japanese vocabulary they have been learning and test themselves using spaced repetition.

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

## Java version:

Ensure that Java 25 is used when running the application or build tasks. On macOS, use `sdk use java 25.0.3.fx-zulu` to switch to Java 25 if needed.

## Git

* Use lightweight tags unless the user requests an annotated tag.
* When proposing or creating a commit message, include enough detail to explain the rationale for the change.
* Use an imperative, capitalized subject with no trailing period. Aim for about
  50 characters and never exceed 72.
* Add a body for non-trivial changes, separated by a blank line and wrapped at
  72 characters. Explain what and why; let the diff explain how.
* Do not commit or push unless explicitly asked.