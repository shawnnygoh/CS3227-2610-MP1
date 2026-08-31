[![Java CI](https://github.com/shawnnygoh/CS3227-2610-MP1/actions/workflows/gradle.yml/badge.svg)](https://github.com/shawnnygoh/CS3227-2610-MP1/actions/workflows/gradle.yml)

# Koko

![Koko's Home screen](docs/images/UI.png)

**Koko is a desktop app for learning Japanese vocabulary**, built for beginners who want to keep their own word list and practice it with spaced repetition. It is driven by buttons and dialogs rather than commands, and it stores everything locally so it works offline.

* One **global vocabulary library**: a card (Hiragana, Romaji, English meaning) can sit in any number of study decks, and stays a single card everywhere.
* Two **independent practice modes**: **Flashcard** for recognizing Japanese, **Typing** for producing Hiragana from an English meaning. Each keeps its own mastery and review schedule.
* **Spaced repetition**: mastery 0–5 drives intervals of 1, 3, 7, 14, and 30 days.
* **Deck sharing**: export a deck as a portable JSON file, or import one — vocabulary only, never your progress.
* **Safe saving**: every change is written to disk before it becomes the app's state, so a failed save leaves your library intact.

## Getting started

Requires **JDK 25**. The ready-to-run app is committed at [`release/koko.jar`](release/koko.jar) with JavaFX bundled for Windows (x64), Apple Silicon macOS, and x64 Linux:

```shell
java -jar release/koko.jar
```

To build it yourself, run `./gradlew shadowJar` for a JAR targeting your own platform (`build/libs/koko.jar`), or `./gradlew releaseJar` to regenerate the cross-platform `release/koko.jar`.

Koko keeps its data in `data/koko-data.json` relative to the folder you launch it from. See the [User Guide's Quick start](docs/UserGuide.md#quick-start) for the full setup, and try the [example deck](examples/koko-sample-deck.json) with **Transfer > Import deck...**.

## Documentation

* **[User Guide](docs/UserGuide.md)** — setup, every feature, FAQ, and known issues.
* **[Developer Guide](docs/DeveloperGuide.md)** — architecture, implementation notes, design considerations, testing, and manual test instructions.

## Acknowledgements

Built with [JavaFX](https://openjfx.io/), [Jackson](https://github.com/FasterXML/jackson-databind), [JUnit 5](https://junit.org/junit5/), and [Gradle](https://gradle.org/). Code style follows the [se-education.org Java coding standard](https://se-education.org/guides/conventions/java/intermediate.html), and the documentation structure is adapted from [AddressBook-Level3](https://github.com/se-edu/addressbook-level3) by [SE-EDU](https://se-education.org). Koko itself is a greenfield project written for CS3227 MP1.
