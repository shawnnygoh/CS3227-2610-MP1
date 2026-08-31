# Koko User Guide

Koko is a desktop app for beginner Japanese learners. Keep a vocabulary library, organize words into study decks, and practice recognizing Japanese or typing Hiragana from an English meaning. Koko uses buttons and dialogs; you do not enter application commands to manage words or study.

Each card has **Hiragana**, **Romaji** (pronunciation in Latin letters), and an **English meaning**. Flashcard and Typing practice have separate learning progress. Koko saves locally and works offline after setup.

This guide also gives peer testers repeatable steps and expected results. Automated tests and CI builds do not establish GUI usability.

## Contents

- [Quick start](#quick-start)
- [Find your way around](#find-your-way-around)
- [Understand cards and decks](#understand-cards-and-decks)
- [Manage vocabulary](#manage-vocabulary)
- [Manage decks](#manage-decks)
- [Review vocabulary](#review-vocabulary)
- [Import and export decks](#import-and-export-decks)
- [Saving and backups](#saving-and-backups)

## Quick start

### Prerequisites

- A desktop graphical environment on Windows, macOS, or Linux, and a writable folder for Koko's data.
- **Java Development Kit (JDK) 25**, matching your operating system and processor. Use Java 25 for both building and launching.
- Git for the clone command below, and internet access for the first build to download Gradle and dependencies.
- A Japanese input method set to Hiragana for Typing practice. For initial testing, you may paste the Japanese text supplied in this guide. Koko does not convert romaji into Hiragana for you.

Open a terminal and check Java before continuing:

```shell
java -version
javac -version
```

Both should report version 25. If you use SDKMAN on macOS and already have the project's JDK installed, select it in that terminal:

```shell
sdk use java 25.0.3.fx-zulu
```

Otherwise install JDK 25 and set `JAVA_HOME` to its installation folder and `PATH` to include its `bin` folder. Open a new terminal and repeat the version checks.

### Build and launch

1. Open a terminal in the parent folder where you want the project. Clone it and enter its root folder:

   ```shell
   git clone https://github.com/shawnnygoh/CS3227-2610-MP1.git
   cd CS3227-2610-MP1
   ```

   If you already have the repository, open a terminal in that existing folder instead. It should contain `build.gradle` and the Gradle wrapper scripts.

2. Build the application with the wrapper. On macOS or Linux:

   ```shell
   ./gradlew shadowJar
   ```

   On Windows PowerShell:

   ```powershell
   .\gradlew.bat shadowJar
   ```

   Wait for `BUILD SUCCESSFUL`. The output is `build/libs/koko.jar`. The build includes the application's dependencies, including JavaFX 25.

3. From the same repository root, launch the JAR:

   ```shell
   java -jar build/libs/koko.jar
   ```

   On a fresh installation, the **Koko** window shows empty vocabulary and deck lists. The example deck is not loaded automatically. Existing valid data loads instead if this working folder already contains `data/koko-data.json`.

For a development launch from the repository root, use `./gradlew run` on macOS/Linux or `.\gradlew.bat run` on Windows. Run only one Koko instance against a data folder at a time.

**Keep using the same working folder.** The data path is relative to the terminal's current folder, not the JAR's location. See [Saving and backups](#saving-and-backups) before moving the JAR or testing with a separate library.

### Try the example deck

For the results below, start with an empty library and perform the steps on the same day.

1. Choose **Transfer > Import deck...**. In the file chooser, open [examples/koko-sample-deck.json](../examples/koko-sample-deck.json) from the repository.
2. In **Confirm the imported deck name**, keep **Deck name** as `Koko Starter Vocabulary` and click **Import**. Koko selects the new deck, shows `12 card(s)`, and adds 12 global cards. The first three are `いえ · ie — house`, `いす · isu — chair`, and `えき · eki — station`. All new cards are due today in both modes.
3. Under **Review mode**, choose **Flashcard**, then **Review due**. The first prompt is `いえ`. Think of its meaning, click **Reveal**, and check `ie` and `house`.
4. Click **Correct**. Koko saves that outcome and shows `いす`. Click **Stop**. The summary shows **Attempted: 1**, **Correct: 1**, **Incorrect: 0**, **Remaining: 11**, and **Status: stopped**. Click **Back to Home**.
5. Select `いえ · ie — house` in **Global vocabulary**, choose **Typing**, and click **Review selected**. Enter `いえ` for `house`, then click **Submit**. The feedback says **Correct** and displays your answer and the expected Hiragana. Click **Next** to see a completed one-card summary, then **Back to Home**.

If you already imported this deck, choose a different name when importing again; matching cards will keep their existing progress rather than restart.

## Find your way around

The main management screen is called **Home** in this guide and you may navigate back to it from other screens by using the **Back to Home** button.

| Area or control | What it does                                                                                                                |
| --- |-----------------------------------------------------------------------------------------------------------------------------|
| **Global vocabulary**, on the left | Lists all cards, including cards outside every deck. Select one here for **Edit**, **Delete**, or **Review selected**.      |
| **Deck selection**, on the right | Lists decks. Click a deck name to open its card list below.                                                                 |
| Selected deck's card list | Shows that deck's name, membership count, and cards. Select a card here for **Remove card from deck**.                      |
| **Review mode** | Choose **Flashcard** or **Typing** before any review action. Flashcard is selected at application startup.                  |
| **Transfer** | Opens **Import deck...** and **Export selected deck...**.                                                                   |
| **Help** | Opens **Koko help**, explaining **How Koko works**. Scroll to read the built-in instructions and click **Close** to return. |
| **Sensei**, at the bottom | Displays guidance, successful-action messages, and the last session's summary after returning Home.                         |

Selections are single-card or single-deck selections. A disabled button usually needs a selection; see the prerequisites below. During review, finish or **Stop**, then choose **Back to Home** before managing cards, transferring decks, or changing mode.

To exit, use the window's close control.

## Understand cards and decks

The **global vocabulary library owns each card**. A deck is an ordered collection of references to those cards. A card can belong to no decks, one deck, or several decks, but only once in any particular deck.

For example, add `ねこ · neko — cat` once, then place it in two decks called `Animals` and `Daily words`. Editing the card changes its text in both decks. Reviewing it in Flashcard through either deck changes the same Flashcard progress everywhere; its Typing progress stays unchanged.

| Action | Effect on the selected deck                                          | Effect on global vocabulary and progress |
| --- |----------------------------------------------------------------------| --- |
| **Remove card from deck** | Removes the card from the selected deck.                             | Keeps the card, both modes' progress, and memberships in other decks. |
| **Delete deck** | Deletes the deck and all its memberships after confirmation.         | Keeps every global card and its progress, including cards that now belong to no decks. |
| **Delete** under **Global vocabulary** | Removes the selected global card from every deck after confirmation. | Deletes the card and both modes' progress. |

There is no Undo or progress-reset button. Back up valuable data before deletion. Recreating a deleted card gives it fresh progress.

## Manage vocabulary

### Valid card text and duplicates

All three fields are required. Surrounding whitespace is removed and canonically equivalent Unicode text is normalized (NFC). Fields must be single-line text; embedded tabs, line breaks, and control characters are rejected.

| Field | Accepted text | Valid example |
| --- | --- | --- |
| **Hiragana** | Hiragana, spaces, and the prolonged sound mark `ー`. Kanji, ordinary Katakana, Latin letters, and digits are not accepted. | `ねこ` |
| **Romaji** | Latin letters, including accented letters, digits, spaces, and the punctuation listed below. | `neko` |
| **English meaning** | The same character rules as Romaji. | `cat` |

The accepted punctuation for Romaji and English meaning is: `- ' ’ . , ! ? : ; / & ( ) + =`. For example, `cat (animal)` is valid; an emoji or quotation mark `"` is not. These checks validate characters, not whether the pronunciation or translation is linguistically correct.

A duplicate has the same normalized Hiragana and English meaning, ignoring English letter case. Romaji is not part of this comparison. If `ねこ · neko — cat` exists, adding `ねこ · NEKO — CAT` is rejected. Use **Add existing card** to place the existing word in another deck. A different English meaning can form a separate card; Koko does not combine synonyms or accept multiple answers automatically.

### Add a card

**Prerequisite:** Home is available and storage loaded successfully; no selection is needed.

1. Under **Global vocabulary**, click **Add card**.
2. Enter `ねこ` in **Hiragana**, `neko` in **Romaji**, and `cat` in **English meaning**.
3. Click **Add card** in the dialog.

**Expected:** The card appears at the end of the global list. Sensei confirms the addition. It starts at mastery 0 and is due today in both modes, but is not automatically added to a deck, even if a deck is selected.

If a field is blank, invalid, or duplicates existing vocabulary, **Action not completed** explains the problem. Dismiss the error and correct the reopened form, which retains your entries. **Cancel** or closing the form discards the unsubmitted addition. A save failure creates no card.

### Edit a card

**Prerequisite:** Select one card in **Global vocabulary**, not only in the deck's card list.

1. Select `ねこ · neko — cat` and click **Edit**.
2. For example, change **English meaning** to `cat (animal)`.
3. Click **Save changes**.

**Expected:** The updated text appears globally and in every deck containing the card. Both modes' progress and all memberships are preserved. The text rules above still apply, including the duplicate check against other cards. Invalid input reopens the form for correction; **Cancel** keeps the original text. Editing does not reset learning progress.

### Delete global vocabulary

**Prerequisite:** Select the card in **Global vocabulary**. Only use a disposable card for this example.

1. Select `ねこ · neko — cat (animal)` and click **Delete**.
2. Read **Delete global vocabulary?** and check the card named in the confirmation.
3. Click **OK** to delete it, or **Cancel** to keep it.

**Expected after OK:** The card disappears from the global list and every deck, and its learning progress is deleted. Decks themselves remain, even if empty. Canceling or closing the confirmation keeps the card and every membership. A save failure leaves the deletion unapplied.

## Manage decks

### Create, select, and rename decks

**Create:** On Home, click **New deck**, enter `Animals` in **Deck name:**, and click **OK**. An empty deck appears at the end of **Deck selection**. Click its name to view it and add cards; creating a deck does not automatically select it.

**Select/open:** Click `Animals` in **Deck selection**. The heading below shows `Animals · 0 card(s)` for an empty deck. After cards are added, their text appears in that list. Clicking another deck changes the displayed memberships without changing the global list.

**Rename:** Select `Animals`, click **Rename**, enter `Daily words` in **New deck name:**, and click **OK**. The deck keeps its position, cards, and shared progress; only its name changes.

Deck names must be nonblank after surrounding whitespace is removed and unique without regard to letter case. `Animals` and `animals` conflict. Japanese names such as `どうぶつ` are allowed; the card-field alphabet restrictions do not apply to deck names. Use a short, readable name. A blank or conflicting name reports **Action not completed** and reopens a name dialog with the entered value for correction. **Cancel** or closing a name dialog leaves the library unchanged.

### Add an existing card to a deck

**Prerequisites:** Select a deck and have at least one global card not already in it. Create the card first with **Add card** if necessary.

1. Select `Animals` (or your renamed deck).
2. Click **Add existing card**.
3. In **Add card to deck**, choose `ねこ · neko — cat` from **Vocabulary card:**, then click **OK**. If you edited the card earlier, choose its updated text instead.

**Expected:** The card appears at the end of the deck's card list, and its count increases by one. No duplicate global card is created and no progress is reset. Repeat one card at a time for additional memberships.

The chooser lists only cards absent from this deck; selecting a global card beforehand does not determine the chooser's choice. If every global card is already a member, **No cards available** appears. With no vocabulary or no selected deck, the button is disabled. Canceling the chooser adds nothing.

### Remove a card from a deck

**Prerequisites:** Select the deck, then select a card in that deck's card list.

Click **Remove card from deck**. For example, remove `ねこ` from `Animals`.

**Expected:** The membership is removed and the deck count decreases. There is no confirmation dialog. The global card, progress, and other decks remain unchanged. If this was a mistake, use **Add existing card** to put it back; it will be appended at the end. With no deck-card selection, removal is disabled.

### Delete a deck

**Prerequisite:** Select the deck in **Deck selection**.

Click **Delete deck**, check the deck named in **Delete deck?**, and click **OK**. For example, deleting `Animals` removes that deck but keeps its vocabulary available globally and in other decks. **Cancel** or closing the confirmation keeps the deck. A save failure leaves it unchanged.

## Review vocabulary

### Choose a mode and cards

On Home, first choose **Flashcard** or **Typing** under **Review mode**. Then use one of these actions:

| Action | Required selection | Included cards and order |
| --- | --- | --- |
| **Review due** | One deck in **Deck selection**. | Cards due today or earlier in the chosen mode. Oldest due date first; equal dates follow deck membership order. |
| **Review all** | One deck in **Deck selection**. | Every member once, in deck membership order, including cards due in the future. |
| **Review selected** | One card in **Global vocabulary**. | That single card, even if due in the future or outside every deck. The selected deck does not matter. |

For example, **Typing > Review all** on `Koko Starter Vocabulary` begins with `house`, then `chair`, then `station`, following the example file's membership order. Here `>` means choose the mode and then click the button; it is not a menu or typed command.

The session fixes its card list and order when it starts. Each card appears once; wrong or skipped cards do not reappear in that session.

If the deck is empty, or no cards qualify for **Review due**, Koko shows **No cards in this review queue.** Use **Back to Home** to choose another deck, another mode, or **Review all**.

### Flashcard mode

**Prerequisite:** Start a Flashcard session using one of the actions above.

1. Read the **Hiragana** prompt and think of its pronunciation and English meaning.
2. Click **Reveal** to see **Romaji** and **English meaning**. **Correct** and **Incorrect** are disabled until you reveal.
3. Compare your recalled answer and click **Correct** or **Incorrect**.
4. After saving, Koko immediately advances to the next prompt, or shows the summary after the last card. There is no separate Next button or success-feedback screen in this mode.

For `いえ`, **Reveal** shows `ie` and `house`. Marking **Correct** records one correct attempt; marking **Incorrect** records one incorrect attempt. Revealing alone records nothing.

### Typing mode

**Prerequisite:** Start a Typing session and enable your operating system's Hiragana input method, or have text ready to paste.

1. Read **English meaning** on the **English-to-Hiragana typing** screen.
2. Enter the card's Hiragana in **Type Hiragana here**, then click **Submit**. For `house`, enter `いえ`.
3. Read **Your answer**, **Expected Hiragana**, and the result: **Correct** or **Incorrect**. Submitting saves the outcome before this feedback appears.
4. Click **Next** to advance. After the last card, **Next** opens the session summary. While feedback is displayed, the answer field, **Submit**, and **Skip** are disabled; **Next** becomes available.

**Answer matching:** Koko removes surrounding whitespace and applies Unicode NFC normalization, then compares against the stored Hiragana exactly. NFC treats a composed voiced character and its equivalent base character plus combining mark as the same text. It does not fix spelling or convert alphabets.

| Stored answer | Entered answer | Result |
| --- | --- | --- |
| `いえ` | `いえ` | Correct. |
| `いえ` | ` いえ ` | Correct; surrounding spaces are ignored. |
| `いえ` | `ie`, `イエ`, `家`, or `い え` | Incorrect; romaji, Katakana, Kanji, and inserted spaces do not match. |
| `いえ` | An empty field | Incorrect; submitting blank is an attempt, not Skip. |

Only the current card's stored Hiragana is accepted. A synonym, missing character, or extra character is incorrect even if it is a plausible translation of the English prompt.

**Skip:** While a prompt is active, click **Skip** if you want to see the answer without grading a typed response. It records a skipped attempt, preserves Typing mastery, and schedules the card for tomorrow. Feedback shows **Skipped**, **Your answer** as `(blank)` (even if you typed something before skipping), and **Expected Hiragana**. Click **Next** to advance. Skip is not counted as correct or incorrect.

### Stop and read the summary

Click **Stop** during a prompt, after revealing a Flashcard answer, or during Typing feedback. It ends the session immediately without a confirmation dialog. Unanswered cards keep their existing progress, while already saved outcomes stay saved.

After stopping or completing, click **Back to Home**. This button is disabled during an active session. A stopped session cannot be resumed; start a new one from Home. **Review all** and **Review selected** allow further practice even if those cards are now scheduled for later, and those extra answers also change progress.

| Summary label | Meaning |
| --- | --- |
| **Attempted** | Successfully saved outcomes in this session. Equals Correct + Incorrect, plus Skipped in Typing. |
| **Correct** / **Incorrect** | Saved answers in each category. |
| **Skipped** | Saved Skip actions; shown only in Typing. |
| **Remaining** | Cards in the initial queue without a saved outcome. The current unanswered card is included, but a card already showing Typing feedback is not. |
| **Status** | `completed` after finishing the queue, or `stopped` after Stop. This is a status, not a stopped-card count. |

For example, in a 12-card Typing session, submit one correct answer, click **Next**, skip the second card, and stop: **Attempted: 2**, **Correct: 1**, **Incorrect: 0**, **Skipped: 1**, **Remaining: 10**, **Status: stopped**.

The progress bar measures saved attempts out of the initial queue, not mastery.

### Mastery and next review date

Each card starts with mastery **0** and is due on its creation/import date in each mode. Progress is shared across decks but independent between Flashcard and Typing. Mastery and next due dates drive review selection.

| Saved outcome | Mastery change in the active mode | Next due date |
| --- | --- | --- |
| Correct | Increase by 1, up to 5. | Use the interval below for the resulting mastery. |
| Incorrect | Decrease by 1, down to 0. | Tomorrow. |
| Skip (Typing only) | No change. | Tomorrow. |
| Stop, Reveal, or Next | No additional progress change. | No additional date change. |

| Mastery after a correct answer | 1 | 2 | 3 | 4 | 5 |
| --- | --- | --- | --- | --- | --- |
| Days until due | 1 | 3 | 7 | 14 | 30 |

Intervals start from the actual answer date, not the previous due date. For example, a correct answer at mastery 1 produces mastery 2 and a due date three days from today. Early practice can raise or lower mastery and replace the due date. Overdue cards do not lose mastery just because time passes.

## Import and export decks

Portable decks are **UTF-8 JSON files containing one deck's vocabulary in membership order**. They do not include card IDs, mastery, due dates, session results, or other learning progress. They are useful for sharing words, not for backing up your full library.

### Import a deck

**Prerequisites:** Be on Home with storage loaded successfully and have a readable Koko portable `.json` file, such as the bundled example.

1. Choose **Transfer > Import deck...** and open the file.
2. Koko validates the complete document before showing **Confirm the imported deck name**. Check **Source file:** and the suggested **Deck name** from inside the document.
3. Keep the suggested name or enter a new one, such as `Starter practice`, then click **Import**.

**Expected:** A new deck is created and selected. Its memberships follow the file order. Import never replaces an existing deck. A blank or case-insensitive conflicting name keeps the dialog open with an error so you can correct it and retry. Renaming the import does not rename or edit the source file.

For each imported word, Koko checks the [duplicate identity rule](#valid-card-text-and-duplicates). A match reuses the existing global card and keeps **its existing text and both modes' progress**, even if the imported romaji or English capitalization differs. Only new vocabulary gets fresh progress and becomes due today in both modes. Reimporting the starter file under another deck name therefore creates another deck, not another set of matching cards or a progress reset.

Canceling or closing either the file chooser or the name dialog leaves data and selection unchanged. If saving fails, the name dialog remains open with the entered name and a retry message.

Invalid JSON, wrong field types, unsupported versions, missing/extra fields, duplicate JSON keys, invalid card text, and duplicate vocabulary within the file reject the whole import without changing data.

### Export a deck

**Prerequisite:** Select a deck in **Deck selection**. Empty decks can also be exported.

1. Choose **Transfer > Export selected deck...**.
2. Choose an existing writable destination folder. Koko suggests a filename based on the selected deck's name; you may edit it.
3. Accept the native save dialog. If that destination already exists, confirm replacement only if you intend to overwrite that file. Native button wording varies by operating system.
4. Check Sensei's export-success message and destination path.

For `Koko Starter Vocabulary`, the suggested filename is `Koko Starter Vocabulary.json`. Exporting it as `practice.json` changes only the filename; the embedded deck name remains `Koko Starter Vocabulary`.

Suggested filenames replace common filename-invalid characters with underscores, trim trailing spaces/dots, and use `koko-deck.json` when no usable name remains. Japanese names and ordinary spaces are retained. User-edited filenames receive only suffix handling: `practice` becomes `practice.json`, `practice.JSON` becomes `practice.json`, and `practice.txt` becomes `practice.txt.json`.

If suffix handling points to a different existing file, the native save chooser opens again for that final destination so replacement can be confirmed there. Canceling or closing any chooser/confirmation exports nothing and leaves data and selection unchanged.

Koko rejects exporting over its configured internal data file. If export fails, Koko reports **Deck export was not completed**. Choose a suitable local folder and try again.

### Portable JSON example

To prepare your own small deck, save the following as a plain-text UTF-8 file named `animals.json`, then import it. Use exactly these field names and types:

```json
{
  "schemaVersion": 1,
  "deckName": "Animals",
  "cards": [
    {
      "hiragana": "ねこ",
      "romaji": "neko",
      "englishMeaning": "cat"
    },
    {
      "hiragana": "いぬ",
      "romaji": "inu",
      "englishMeaning": "dog"
    }
  ]
}
```

`schemaVersion` must be the integer `1`; `deckName` must be a nonblank string, and `cards` an array of valid cards. An empty array is valid. Do not add progress or IDs, use comments or trailing commas, or combine multiple JSON documents. The internal `data/koko-data.json` has a different contract and cannot be imported through Transfer, even though both formats currently use version 1.

## Saving and backups

### Automatic saving and the data folder

Koko saves each successful card/deck change, import, and review outcome automatically. There is no Save command. Data is stored in `data/koko-data.json` relative to the **working folder from which the application is launched**.

For the repository-root launch above, the file is `CS3227-2610-MP1/data/koko-data.json`. If you instead open a terminal in `KokoPractice` and launch a JAR elsewhere using its absolute path, the file is `KokoPractice/data/koko-data.json`. Moving only the JAR does not move your library.

### Back up or move the whole library

1. Close every Koko instance using that data folder.
2. Copy `data/koko-data.json` to a separate backup folder. Keep the original and clearly label the copy with its date and application revision.
3. To restore or move your library, close Koko at the destination, back up any destination data first, then copy the chosen backup into the destination working folder as `data/koko-data.json`.
4. Launch the **same compatible application revision** from that folder and check your cards and decks before making changes.

The internal file preserves the global library, deck relationships, and each mode's mastery and due date. A portable export preserves only one deck's words and order, so importing exports is not a substitute for a full backup.
