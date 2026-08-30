package koko.review;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import koko.model.Deck;
import koko.model.Mode;
import koko.model.ModeProgress;
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

    private record QueuedCard(UUID cardId, LocalDate dueDate) {
    }

    private final KokoService service;
    private final List<UUID> queuedCardIds;
    private int currentPosition;
    private int correct;
    private int incorrect;
    private int skipped;
    private Feedback feedback;
    private State state;

    /**
     * Creates a session for the typing-due cards in a selected deck.
     *
     * <p>Eligibility is evaluated once using the date from {@code clock}. Cards are
     * ordered by oldest due date first, with stable deck order for equal due dates.
     *
     * @param service service holding the current global cards and decks.
     * @param deckId selected deck.
     * @param clock clock used to determine the session start date.
     * @throws IllegalArgumentException if the deck or a referenced card does not exist.
     * @throws NullPointerException if an argument is null.
     */
    public TypingSession(KokoService service, UUID deckId, Clock clock) {
        this(Objects.requireNonNull(service, "Service cannot be null"),
                dueCardIds(service, deckId, clock));
    }

    private TypingSession(KokoService service, List<UUID> queuedCardIds) {
        this.service = service;
        this.queuedCardIds = List.copyOf(queuedCardIds);
        state = this.queuedCardIds.isEmpty() ? State.COMPLETED : State.PROMPT;
    }

    /**
     * Creates a session for typing-due cards in a selected deck.
     *
     * @param service service holding the current global cards and decks.
     * @param deckId selected deck.
     * @param clock clock used to determine the session start date.
     * @return a typing session.
     * @throws IllegalArgumentException if the deck or a referenced card does not exist.
     * @throws NullPointerException if an argument is null.
     */
    public static TypingSession forDeck(KokoService service, UUID deckId, Clock clock) {
        return new TypingSession(service, deckId, clock);
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
        return currentPosition < queuedCardIds.size()
                ? Optional.of(queuedCardIds.get(currentPosition)) : Optional.empty();
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
        VocabularyCard card = requireCurrentCard();
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
        requireCurrentCard();
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
        requireExpectedCurrentCard(expectedCardId);
        requireState(State.PROMPT, "Only a prompt can be submitted");
        Objects.requireNonNull(enteredAnswer, "Entered answer cannot be null");
        VocabularyCard card = requireCurrentCard();
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
        requireExpectedCurrentCard(expectedCardId);
        requireState(State.PROMPT, "Only a prompt can be skipped");
        VocabularyCard card = requireCurrentCard();
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
        requireExpectedCurrentCard(expectedCardId);
        requireState(State.FEEDBACK, "Next is available only after an outcome");
        requireCurrentCard();
        currentPosition++;
        feedback = null;
        state = currentPosition == queuedCardIds.size() ? State.COMPLETED : State.PROMPT;
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
        requireExpectedCurrentCard(expectedCardId);
        if (state == State.PROMPT || state == State.FEEDBACK) {
            requireCurrentCard();
            state = State.STOPPED;
        }
    }

    /**
     * Stops the active session without an external card ID.
     *
     * <p>This overload supports non-view callers. View actions should use the ID-bound
     * overload so stale events cannot stop a different card.
     */
    public void stop() {
        if (state == State.PROMPT || state == State.FEEDBACK) {
            requireCurrentCard();
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
     * Returns the number of successfully persisted incorrect outcomes.
     *
     * @return incorrect outcome count.
     */
    public int incorrect() {
        return incorrect;
    }

    /**
     * Returns the number of successfully persisted skipped outcomes.
     *
     * @return skipped outcome count.
     */
    public int skipped() {
        return skipped;
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
        return queuedCardIds.size() - attempted();
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
        return new Summary(queuedCardIds.size(), correct, incorrect, skipped, attempted(),
                remaining(), stopped());
    }

    private void recordFeedback(String enteredAnswer, String expectedHiragana,
            ReviewOutcome outcome) {
        feedback = new Feedback(currentCardId().orElseThrow(), enteredAnswer, expectedHiragana,
                outcome);
        switch (outcome) {
            case CORRECT -> correct++;
            case INCORRECT -> incorrect++;
            case SKIPPED -> skipped++;
            default -> throw new IllegalArgumentException("Unsupported review outcome");
        }
        state = State.FEEDBACK;
    }

    private void requireExpectedCurrentCard(UUID expectedCardId) {
        Objects.requireNonNull(expectedCardId, "Expected card ID cannot be null");
        if (currentCardId().isEmpty() || !expectedCardId.equals(currentCardId().orElseThrow())) {
            throw new IllegalStateException("Card action is stale");
        }
    }

    private void requireState(State expectedState, String message) {
        if (state != expectedState) {
            throw new IllegalStateException(message);
        }
    }

    private VocabularyCard requireCurrentCard() {
        UUID cardId = currentCardId().orElseThrow(() ->
                new IllegalStateException("The session has no current card"));
        return service.data().findVocabularyCard(cardId).orElseThrow(() ->
                new IllegalStateException("Vocabulary card " + cardId + " no longer exists"));
    }

    private static List<UUID> dueCardIds(KokoService service, UUID deckId, Clock clock) {
        Objects.requireNonNull(service, "Service cannot be null");
        UUID checkedDeckId = Objects.requireNonNull(deckId, "Deck ID cannot be null");
        Clock checkedClock = Objects.requireNonNull(clock, "Clock cannot be null");
        Deck deck = service.data().findDeckById(checkedDeckId).orElseThrow(() ->
                new IllegalArgumentException("Deck does not exist"));
        LocalDate startDate = LocalDate.now(checkedClock);
        Set<UUID> seenCardIds = new LinkedHashSet<>();
        List<QueuedCard> dueCards = new ArrayList<>();
        for (UUID cardId : deck.cardIds()) {
            if (seenCardIds.add(cardId)) {
                VocabularyCard card = service.data().findVocabularyCard(cardId).orElseThrow(() ->
                        new IllegalArgumentException("Vocabulary card " + cardId
                                + " does not exist"));
                ModeProgress progress = card.progressFor(Mode.TYPING);
                if (progress.isDueOn(startDate)) {
                    dueCards.add(new QueuedCard(card.id(), progress.nextDueDate()));
                }
            }
        }
        dueCards.sort(Comparator.comparing(QueuedCard::dueDate));
        return dueCards.stream().map(QueuedCard::cardId).toList();
    }
}
