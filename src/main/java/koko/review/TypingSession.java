package koko.review;

import java.time.Clock;
import java.time.LocalDate;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import koko.model.Mode;
import koko.model.VocabularyCard;
import koko.service.KokoService;
import koko.service.ReviewOutcome;
import koko.storage.StorageException;

/**
 * JavaFX-independent coordinator for one frozen English-to-Hiragana typing review session.
 *
 * <p>The session stores only card IDs and its own outcome records. Card text is resolved
 * from the service while it is displayed or acted on, so a successful persistence
 * operation cannot leave the session holding a mutable card.
 */
public final class TypingSession {

    /** The lifecycle states of a typing session. */
    public enum State {
        /** The current card is waiting for a submitted answer or skip. */
        PROMPT,

        /** The current card has a recorded outcome and is waiting for Next. */
        FEEDBACK,

        /** Every queued card has a successfully persisted outcome and Next was completed. */
        COMPLETED,

        /** The session was stopped before Next completed the queue. */
        STOPPED
    }

    /** An immutable prompt snapshot for the current card. */
    public record Prompt(UUID cardId, String englishMeaning) {
    }

    /** An immutable feedback snapshot for the recorded current-card outcome. */
    public record Feedback(UUID cardId, String enteredAnswer, String expectedHiragana,
            ReviewOutcome outcome) {
    }

    /** An immutable read-only summary of session progress. */
    public record Summary(int initialQueueSize, int correct, int incorrect, int skipped,
            int attempted, int remaining, boolean stopped) {
    }

    private final ReviewQueue queue;
    private final KokoService service;
    private int correct;
    private int incorrect;
    private int skipped;
    private Feedback feedback;
    private State state;

    private TypingSession(ReviewQueue queue) {
        this.queue = queue;
        service = queue.service();
        state = queue.isEmpty() ? State.COMPLETED : State.PROMPT;
    }

    /**
     * Creates a session for cards due for the Typing mode in a selected deck.
     *
     * <p>Eligibility is evaluated once using the date from {@code clock}. Cards are
     * ordered by oldest due date first, with stable deck order for equal due dates.
     *
     * @param service service holding the current global cards and decks.
     * @param deckId selected deck.
     * @param clock clock used to determine the session start date.
     * @return a typing session.
     * @throws IllegalArgumentException if the deck or a referenced card does not exist.
     * @throws NullPointerException if an argument is null.
     */
    public static TypingSession forDeck(KokoService service, UUID deckId, Clock clock) {
        return new TypingSession(
                ReviewQueue.forDueCardsInDeck(service, deckId, clock, Mode.TYPING));
    }

    /**
     * Creates a session containing one selected global card, regardless of due status
     * or deck membership.
     *
     * @param service service holding the selected card.
     * @param cardId selected global card.
     * @return a session containing the selected card.
     * @throws IllegalArgumentException if the card does not exist.
     * @throws NullPointerException if an argument is null.
     */
    public static TypingSession forCard(KokoService service, UUID cardId) {
        return new TypingSession(ReviewQueue.forSingleCard(service, cardId));
    }

    /**
     * Creates a session for every card in a selected deck.
     *
     * <p>Cards are included once in their deck membership order, regardless of
     * due status. The membership snapshot is taken when this method is called.
     *
     * @param service service holding the current global cards and decks.
     * @param deckId selected deck.
     * @return an all-card deck session.
     * @throws IllegalArgumentException if the deck or a referenced card does not exist.
     * @throws NullPointerException if an argument is null.
     */
    public static TypingSession forAllCardsInDeck(KokoService service, UUID deckId) {
        return new TypingSession(ReviewQueue.forAllCardsInDeck(service, deckId));
    }

    /**
     * Returns the current lifecycle state.
     *
     * @return current session state.
     */
    public State state() {
        return state;
    }

    /**
     * Returns the current card ID while the queue retains its current card.
     *
     * @return current card ID, or empty after completion or an empty session.
     */
    public Optional<UUID> currentCardId() {
        return queue.currentCardId();
    }

    /**
     * Returns the current English prompt.
     *
     * @return current prompt, or empty unless the session is waiting for an outcome.
     * @throws IllegalStateException if the queued card no longer exists.
     */
    public Optional<Prompt> currentPrompt() {
        if (state != State.PROMPT) {
            return Optional.empty();
        }
        VocabularyCard card = queue.requireCurrentCard();
        return Optional.of(new Prompt(card.id(), card.englishMeaning()));
    }

    /**
     * Returns feedback for the most recently recorded outcome.
     *
     * @return feedback while waiting for Next or after stopping from feedback.
     * @throws IllegalStateException if the queued card no longer exists.
     */
    public Optional<Feedback> currentFeedback() {
        if (feedback == null || (state != State.FEEDBACK && state != State.STOPPED)) {
            return Optional.empty();
        }
        queue.requireCurrentCard();
        return Optional.of(feedback);
    }

    /**
     * Submits an answer for the expected current card.
     *
     * <p>A blank answer is an incorrect answer. A failed save leaves every session value
     * unchanged, including the submitted text being available to the caller for retry.
     *
     * @param expectedCardId card ID captured when the prompt was displayed.
     * @param enteredAnswer answer entered by the learner.
     * @throws IllegalStateException if the event is stale, early, repeated, or terminal,
     *         or the queued card no longer exists.
     * @throws NullPointerException if an argument is null.
     * @throws java.time.DateTimeException if the next due date exceeds the range supported by {@link LocalDate}.
     * @throws StorageException if persistence fails.
     */
    public void submit(UUID expectedCardId, String enteredAnswer) throws StorageException {
        queue.requireExpectedCurrentCard(expectedCardId);
        requireState(State.PROMPT, "Only a prompt can be submitted");
        Objects.requireNonNull(enteredAnswer, "Entered answer cannot be null");
        VocabularyCard card = queue.requireCurrentCard();
        ReviewOutcome outcome = TypingAnswerEvaluator.isCorrect(enteredAnswer, card.hiragana())
                ? ReviewOutcome.CORRECT : ReviewOutcome.INCORRECT;
        service.recordTypingOutcome(expectedCardId, outcome);
        recordFeedback(enteredAnswer, card.hiragana(), outcome);
    }

    /**
     * Records a skipped outcome for the expected current card.
     *
     * <p>Skipping is an attempt and waits for a separate Next action. A failed save leaves
     * the prompt and all counters unchanged.
     *
     * @param expectedCardId card ID captured when the prompt was displayed.
     * @throws IllegalStateException if the event is stale, early, repeated, or terminal,
     *         or the queued card no longer exists.
     * @throws NullPointerException if expectedCardId is null.
     * @throws java.time.DateTimeException if the next due date exceeds the range supported by {@link LocalDate}.
     * @throws StorageException if persistence fails.
     */
    public void skip(UUID expectedCardId) throws StorageException {
        queue.requireExpectedCurrentCard(expectedCardId);
        requireState(State.PROMPT, "Only a prompt can be skipped");
        VocabularyCard card = queue.requireCurrentCard();
        service.recordTypingOutcome(expectedCardId, ReviewOutcome.SKIPPED);
        recordFeedback("", card.hiragana(), ReviewOutcome.SKIPPED);
    }

    /**
     * Advances after feedback for the expected current card.
     *
     * @param expectedCardId card ID captured while feedback is displayed.
     * @throws IllegalStateException if the event is stale, early, repeated, or terminal,
     *         or the queued card no longer exists.
     * @throws NullPointerException if expectedCardId is null.
     */
    public void next(UUID expectedCardId) {
        queue.requireExpectedCurrentCard(expectedCardId);
        requireState(State.FEEDBACK, "Next is available only after an outcome");
        queue.requireCurrentCard();
        queue.advance();
        feedback = null;
        state = queue.isExhausted() ? State.COMPLETED : State.PROMPT;
    }

    /**
     * Stops the active session while retaining its current queue position.
     *
     * <p>Stopping after feedback retains that feedback and its already-recorded outcome.
     * Stopping a terminal session is a no-op.
     *
     * @param expectedCardId card ID captured from the current view.
     * @throws IllegalStateException if the event is stale or the queued card no longer exists.
     * @throws NullPointerException if expectedCardId is null.
     */
    public void stop(UUID expectedCardId) {
        queue.requireExpectedCurrentCard(expectedCardId);
        if (state == State.PROMPT || state == State.FEEDBACK) {
            queue.requireCurrentCard();
            state = State.STOPPED;
        }
    }

    /**
     * Returns the number of successfully persisted correct outcomes.
     *
     * @return correct outcome count.
     */
    public int correct() {
        return correct;
    }

    /**
     * Returns the number of successfully persisted outcomes.
     *
     * @return attempted count.
     */
    public int attempted() {
        return correct + incorrect + skipped;
    }

    /**
     * Returns the number of queued cards without a successfully persisted outcome.
     *
     * @return remaining count, excluding a card whose feedback is displayed.
     */
    public int remaining() {
        return queue.size() - attempted();
    }

    /**
     * Returns whether this session was stopped.
     *
     * @return true only when the state is STOPPED.
     */
    public boolean stopped() {
        return state == State.STOPPED;
    }

    /**
     * Returns an immutable summary of the current session progress.
     *
     * @return current session summary.
     */
    public Summary summary() {
        return new Summary(queue.size(), correct, incorrect, skipped, attempted(),
                remaining(), stopped());
    }

    private void recordFeedback(String enteredAnswer, String expectedHiragana,
            ReviewOutcome outcome) {
        feedback = new Feedback(queue.currentCardId().orElseThrow(), enteredAnswer,
                expectedHiragana, outcome);
        switch (outcome) {
            case CORRECT -> correct++;
            case INCORRECT -> incorrect++;
            case SKIPPED -> skipped++;
            default -> throw new IllegalArgumentException("Unsupported review outcome");
        }
        state = State.FEEDBACK;
    }

    private void requireState(State expectedState, String message) {
        if (state != expectedState) {
            throw new IllegalStateException(message);
        }
    }
}
