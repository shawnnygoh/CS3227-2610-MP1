# Reflections

## 1. How My Prompts Changed Over Time

When I first started making changes with my first few prompts, I realized that they were more like dialogue you would have in a conversation than any strict instructions. By the end of the project, these "dialogue-like" conversational prompts had turned into written specifications with acceptance criteria, exclusions, and the exact commands I wanted to run. They acted as specific instructions the model could follow which increased their likelihood of task adherence and following what I set out for them to achieve. This was not something I had planned before I started on the project, but I soon realized that not including each one of my specifications led to more problems or frustrations which I had to resolve manually.

The clearest example of how being non-specific can lead to unintended changes can be seen in one of my first few prompts.

```
Actually revert the ASCII banner changes.
```

This experience with LLMs hallucinating came when I wanted to revert changes I made to an ASCII banner I had added as part of an initial scaffold of the project. Instead of removing the banner and leaving the rest of the file, it deleted the entire file and I had to re-create it.

The prompt was obvious to me because I knew which change I wanted to make, but the LLM interpreted it as reverting the whole session's work, which was technically also a reasonable interpretation. Nothing in that sentence said which file to keep, how far the edit should go, or how I planned to check the result, so all three of those were left for the model to guess. This was a lesson for me to be more specific with my prompts in future sessions, even when it came to small changes like this.

From then on, most increments went through the same deterministic stages, which involved me getting the model to clarify the work, write the specification, and critically review the specification before implementing anything. I would be the human-in-the-loop as well reviewing each and every part of this process. After implementation, I would also have a separate prompt for doing a thorough code review of the changes before committing anything. These were changes I made after realizing that each additional stage made the model's output more deterministic and aligned with what I wanted.

## 2. Multi-Section Prompts

An example of one of my prompts with multiple sections can be seen below:

```
Implement a Flashcard review feature for Koko.

Rules
- Read AGENTS.md, README.md, git status/history, the existing service, scheduler,
  model, and their tests before editing.
- Implement the feature and its tests together in this increment.
- Do not stage, commit, or push any work.

Existing Specification
Cards are globally owned and decks hold ordered unique UUID references. FLASHCARD and
TYPING progress are independent and global per card. Persistence copies the whole
aggregate, saves once, and publishes only after the save succeeds. Do not change the
scheduler's policy, the storage schema, or the save policy in this increment.

Expected Behavior
Start a session from one deck's FLASHCARD cards with nextDue <= today, ordered by
oldest nextDue, keeping deck membership order for ties, or from one selected global
card regardless of due date. A session starts at PROMPT showing Hiragana. Reveal
enters ANSWER_REVEALED and shows romaji and English. Correct or Incorrect applies
exactly one outcome to FLASHCARD progress, saves once, then advances or completes.
Stop from either state ends with a summary and does not change an unanswered card.
Handle empty queues.

Keep state and scheduling out of the views. Use FXML and CSS for presentation, thin
controllers, and session logic in its own JavaFX-independent class. Disable illegal
controls, and also guard the transitions in session logic against double clicks.

Tests
Cover empty, one-card, and many-card queues, ordering including ties, every legal and
illegal transition, reveal twice, an outcome before reveal, stop in each state, a
selected non-due card, double submission, summary counts, exactly-once save, and
TYPING progress staying untouched. Assert domain values and UUIDs rather than Java
object identity, because a successful save republishes the aggregate.

Verification, from the repository root
  ./gradlew test --tests koko.review.FlashcardSessionTest --tests koko.service.KokoServiceTest
  ./gradlew clean check shadowJar
  git diff --check
Inspect the reports for executed tests, failures, and skips, and both Checkstyle tasks.

Handoff
Report the changed files, decisions and rationale, exact verification
results, remaining developer checks, and limitations.
```

Almost every block in this prompt was added because of something I learnt in an earlier session, so the prompt ended up reading like a list of my own past mistakes.

| Block in the prompt | Why I added it                                                                                                                                     |
|-------|----------------------------------------------------------------------------------------------------------------------------------------------------|
| Rules | There were times when the model did not reference AGENTS.md for repo-wide steps or conventions to follow.                                          |
| Existing Specification | There were times when the model's implementation overrode existing specifications which should have remained.                                      |
| Expected Behavior | Being vague with what the model should implement results in vague implementations as well which might not be aligned with what I'm looking for.    |
| Tests | Having a working implementation means little without tests that test boundary cases.                                                               |
| Verification | Reminds the model not to make silly mistakes like failing style checks.                                                                            |
| Handoff | Provides the developer with an easily reviewable list of what was implemented so that they can review it and see if modifications need to be made. |

## 3. Using a Review Checklist

The prompt I reused the most does not produce any code. Instead of asking whether the work looks good, it lists the software engineering criteria I wanted the review to check against baked directly into the prompt. An example can be found below.

```
Review the unstaged changes below. Do not edit in the first pass, and do not commit or push.

[FEATURE BRIEFING]
[APPROVED SPECIFICATION]

Inspect Git status/diff, relevant production code, tests, and resources. Check:
- every requirement and non-goal;
- invariants, errors, Unicode, dates, persistence, state transitions, and double actions as relevant;
- SRP, separation of concerns, SLAP, Law of Demeter, dependency direction, and unnecessary abstractions;
- JUnit test strength and whether tests could pass for the wrong reason;
- FXML/controller/CSS boundaries and UI-thread concerns where relevant;
- Javadoc, names, imports, coding standard, dead/debug/generated code, and secrets;
- whether the diff is one cohesive, independently revertible commit.

For each actionable issue, give severity, exact file/line evidence, failure scenario, and smallest fix.
Separate defects from optional suggestions. If there are no actionable issues, say so.
```

There are three parts of this prompt that I added on purpose. I asked whether tests could pass for the wrong reason because that exact problem had occurred previously. I asked it to say so when there were no actionable issues because an open-ended review request tends to produce findings whether or not any real ones exist. I also told it not to edit on the first pass, so that I could decide which findings were actually worth acting on before any code changed.

## 4. Exploration with "Tree-of-Thoughts"

When implementing specific features, I also got the model to lay out several possible approaches before any code existed, instead of letting it run with the first idea it had. This was used in the process of creating the specification and deciding on the expected behavior. An example prompt can be found below.

```
I am adding portable deck import and export to Koko.

Propose three distinct designs for the portable file format and for where the import and
export logic should live. For each design, describe:
- the file shape, and what it does and does not carry;
- which classes gain responsibility, and what that does to the existing service and storage;
- how a partly written file, an unreadable file, and a deck name clash would behave;
- what becomes hard to change later if I pick it.

Compare them against these criteria in order. The internal library must never be corrupted by
a failed import, portable files must not carry internal identities or learning progress, and
the change has to fit into one cohesive commit. Recommend one design and say what I would be
giving up by choosing it.
```

Asking for three designs stopped the model from anchoring on its first idea, which was usually the most complicated one. Asking what I would be giving up was the part that helped me the most, because it forced the trade-off into the open where I could disagree with it.

## 5. What the LLM Assumed and What It Got Wrong

1. **Scope.** When I did not state a boundary, it built more than I asked for.
2. **Portability.** It assumed that a passing local run meant the feature worked everywhere, until CI showed that a feature did not work on Windows.
3. **Weak tests.** This was one of the failures I ran into most often. I had to correct wrong expected save counts and scheduler intervals, progress that was compared by object equality, and tests that kept passing against deliberately incorrect substitutes until I asked for stronger assertions.

## 6. How I Verified the Results

1. **Automated gates, added before most of the code.** `AGENTS.md` was the second commit of the project, Gradle was the fourth, Checkstyle was the fifth, and cross-platform CI was the eighth. These removed a bunch of convention and correctness mistakes that I would otherwise have had to catch by reading.
2. **Named commands in the prompt instead of asking it to run the tests.** I asked for specific commands like `clean check shadowJar`, and `git diff --check` to get results I could verify easily.
3. **Reading the diff myself.** Codex provided a useful diff visualizer which I could use after every change.
4. **Testing behavior in the GUI.** Many of the UI changes had to be tested manually as even the LLM with Computer Use was having a hard time getting right.

## 7. When Prompting Was Less Effective Than Doing It Myself

Anything visual was consistently faster to do by hand. Without Computer Use the agent was working blind on layout, and with it, it was never reliable enough for me to trust. Describing a layout problem precisely enough for a model to fix it without seeing it usually took longer than opening the file and fixing it myself. The resizing increment only came together after I reported what I was seeing at the minimum window size, and I had to do that multiple times.

## 8. What I Still Had to Decide Myself

1. **Setting policy.** Whether Koko should refuse an unsafe write or accept a documented race was a product decision, so I made it, restated it when the agent's earlier safety rule contradicted it, and asked for a regression test that records the limitation I had accepted.
2. **Judging whether work was necessary and not only correct.** I rejected work that was technically valid but more complex than this project justified, and I retired a whole planned increment of statistics and sorting for the same reason.
3. **Owning the commit history.** Every commit here is mine, made from messages that the agent proposed and I often rewrote for better clarity.
4. **Deciding what counts as a limitation.** Some problems were worth fixing and others were worth documenting and testing instead, and the guides now say plainly where Koko does not protect the user.

## 9. What I Would Do Differently

1. **Start with the contract shape instead of arriving at it.** My later prompts worked because they carried scope, non-goals, test obligations, verification commands, and a handoff format, and I rebuilt that skeleton incident by incident. Next time it would be the first thing I write.
2. **Avoid delegating work whose feedback loop the agent cannot close.** GUI work needs something or someone who can process visual feedback, and the sessions that pretended otherwise still ended with me doing manual acceptance testing.
3. **Keep a project glossary inside the repository.** There were times when the model was confused about certain project-specific terminology I used as I had not documented it properly. This is something I'll definitely include in one way or another next time round.
