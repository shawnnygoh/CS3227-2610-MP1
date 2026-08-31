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
 * JavaFX-independent coordinator for one frozen flashcard review session.
 *
 * <p>The session stores only card IDs and its own progress counters. Card text
 * is resolved from the service whenever it is exposed or submitted, so a
 * persistence operation cannot leave the session holding a mutable card.
 */
public final class FlashcardSession {

    /** The lifecycle states of a flashcard session. */
    public enum State {
        /** The current card shows its Hiragana prompt. */
        PROMPT,

        /** The current card's romaji and English meaning are visible. */
        ANSWER_REVEALED,

        /** Every queued card has a successfully persisted outcome. */
        COMPLETED,

        /** The session was stopped before all queued cards were answered. */
        STOPPED
    }

    /** An immutable prompt snapshot for the current card. */
    public record Prompt(UUID cardId, String hiragana) {
    }

    /** An immutable answer snapshot for a revealed card. */
    public record Answer(UUID cardId, String romaji, String englishMeaning) {
    }

    /** An immutable read-only summary of session progress. */
    public record Summary(int initialQueueSize, int correct, int incorrect, int attempted,
            int remaining, boolean stopped) {
    }

    private final ReviewQueue queue;
    private final KokoService service;
    private int correct;
    private int incorrect;
    private State state;

    private FlashcardSession(ReviewQueue queue) {
        this.queue = queue;
        service = queue.service();
        state = queue.isEmpty() ? State.COMPLETED : State.PROMPT;
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
    public static FlashcardSession forCard(KokoService service, UUID cardId) {
        return new FlashcardSession(ReviewQueue.forSingleCard(service, cardId));
    }

    /**
     * Creates a session for the due flashcard cards in a selected deck.
     *
     * <p>Eligibility is evaluated once using the date from {@code clock}.
     * Cards are ordered by oldest due date first, with stable deck order for
     * equal due dates.
     *
     * @param service service holding the current global cards and decks.
     * @param deckId selected deck.
     * @param clock clock used to determine the session start date.
     * @return a deck session.
     * @throws IllegalArgumentException if the deck or a referenced card does not exist.
     * @throws NullPointerException if an argument is null.
     */
    public static FlashcardSession forDeck(KokoService service, UUID deckId, Clock clock) {
        return new FlashcardSession(
                ReviewQueue.forDueCardsInDeck(service, deckId, clock, Mode.FLASHCARD));
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
    public static FlashcardSession forAllCardsInDeck(KokoService service, UUID deckId) {
        return new FlashcardSession(ReviewQueue.forAllCardsInDeck(service, deckId));
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
     * Returns the current card ID while the session retains an unanswered card.
     *
     * @return current card ID, or empty after completion or for an empty session.
     */
    public Optional<UUID> currentCardId() {
        return queue.currentCardId();
    }

    /**
     * Returns a fresh Hiragana prompt snapshot for the current card.
     *
     * @return current prompt, or empty after completion or for an empty session.
     * @throws IllegalStateException if the queued card no longer exists.
     */
    public Optional<Prompt> currentPrompt() {
        if (queue.currentCardId().isEmpty()) {
            return Optional.empty();
        }
        VocabularyCard card = queue.requireCurrentCard();
        return Optional.of(new Prompt(card.id(), card.hiragana()));
    }

    /**
     * Returns a fresh answer snapshot after the current card has been revealed.
     *
     * @return current answer, or empty unless the session is in ANSWER_REVEALED.
     * @throws IllegalStateException if the queued card no longer exists.
     */
    public Optional<Answer> currentAnswer() {
        if (state != State.ANSWER_REVEALED) {
            return Optional.empty();
        }
        VocabularyCard card = queue.requireCurrentCard();
        return Optional.of(new Answer(card.id(), card.romaji(), card.englishMeaning()));
    }

    /**
     * Reveals the answer for the expected current card.
     *
     * @param expectedCardId card ID captured when the prompt was displayed.
     * @throws NullPointerException if expectedCardId is null.
     * @throws IllegalStateException if the event is stale, repeated, or terminal,
     *         or the queued card no longer exists.
     */
    public void reveal(UUID expectedCardId) {
        queue.requireExpectedCurrentCard(expectedCardId);
        requireState(State.PROMPT, "Only a prompt can be revealed");
        queue.requireCurrentCard();
        state = State.ANSWER_REVEALED;
    }

    /**
     * Persists an outcome for the expected revealed card and advances on success.
     *
     * <p>A failed save leaves the state, current card, counters, and position
     * unchanged, allowing the same event to be retried or stopped.
     *
     * @param expectedCardId card ID captured when the answer was revealed.
     * @param outcome correct or incorrect outcome.
     * @throws IllegalArgumentException if the outcome is SKIPPED or incrementing
     *         the attempt count exceeds the supported integer range.
     * @throws IllegalStateException if the event is stale, early, repeated, or terminal,
     *         or the queued card no longer exists.
     * @throws NullPointerException if an argument is null.
     * @throws java.time.DateTimeException if the next due date exceeds the range supported by {@link LocalDate}.
     * @throws StorageException if persistence fails.
     */
    public void submit(UUID expectedCardId, ReviewOutcome outcome) throws StorageException {
        queue.requireExpectedCurrentCard(expectedCardId);
        requireState(State.ANSWER_REVEALED, "Only a revealed answer can be submitted");
        Objects.requireNonNull(outcome, "Review outcome cannot be null");
        if (outcome == ReviewOutcome.SKIPPED) {
            throw new IllegalArgumentException("Skipped outcomes are not accepted");
        }
        boolean correctOutcome = outcome == ReviewOutcome.CORRECT;
        queue.requireCurrentCard();
        service.recordFlashcardOutcome(expectedCardId, outcome);

        if (correctOutcome) {
            correct++;
        } else {
            incorrect++;
        }
        queue.advance();
        state = queue.isExhausted() ? State.COMPLETED : State.PROMPT;
    }

    /**
     * Stops an active session without changing its current queue position.
     *
     * <p>Stopping an already terminal session is a no-op.
     */
    public void stop() {
        if (state == State.PROMPT || state == State.ANSWER_REVEALED) {
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
     * Returns the number of successfully persisted outcomes.
     *
     * @return attempted count.
     */
    public int attempted() {
        return correct + incorrect;
    }

    /**
     * Returns the number of queued cards not yet successfully answered.
     *
     * @return remaining count, including the current card.
     */
    public int remaining() {
        return queue.size() - attempted();
    }

    /**
     * Returns whether this session was stopped before completion.
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
        return new Summary(queue.size(), correct, incorrect, attempted(), remaining(),
                stopped());
    }

    private void requireState(State expectedState, String message) {
        if (state != expectedState) {
            throw new IllegalStateException(message);
        }
    }
}
