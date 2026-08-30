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

    private record QueuedCard(UUID cardId, LocalDate dueDate) {
    }

    private final KokoService service;
    private final List<UUID> queuedCardIds;
    private int currentPosition;
    private int correct;
    private int incorrect;
    private State state;

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
     * @throws IllegalArgumentException if the deck or a referenced card does not exist.
     * @throws NullPointerException if an argument is null.
     */
    public FlashcardSession(KokoService service, UUID deckId, Clock clock) {
        this(Objects.requireNonNull(service, "Service cannot be null"),
                dueCardIds(service, deckId, clock));
    }

    private FlashcardSession(KokoService service, List<UUID> queuedCardIds) {
        this.service = service;
        this.queuedCardIds = List.copyOf(queuedCardIds);
        state = this.queuedCardIds.isEmpty() ? State.COMPLETED : State.PROMPT;
    }

    /**
     * Creates a session containing one selected global card, regardless of due status
     * or deck membership.
     *
     * @param service service holding the selected card.
     * @param cardId selected global card.
     * @param clock clock used to establish the session start date.
     * @return a session containing the selected card.
     * @throws IllegalArgumentException if the card does not exist.
     * @throws NullPointerException if an argument is null.
     */
    public static FlashcardSession forCard(KokoService service, UUID cardId, Clock clock) {
        KokoService checkedService = Objects.requireNonNull(service, "Service cannot be null");
        Objects.requireNonNull(clock, "Clock cannot be null");
        UUID checkedCardId = Objects.requireNonNull(cardId, "Card ID cannot be null");
        requireCardForStart(checkedService, checkedCardId);
        return new FlashcardSession(checkedService, List.of(checkedCardId));
    }

    /**
     * Creates a session for due cards in a selected deck.
     *
     * @param service service holding the current global cards and decks.
     * @param deckId selected deck.
     * @param clock clock used to determine the session start date.
     * @return a deck session.
     * @throws IllegalArgumentException if the deck or a referenced card does not exist.
     * @throws NullPointerException if an argument is null.
     */
    public static FlashcardSession forDeck(KokoService service, UUID deckId, Clock clock) {
        return new FlashcardSession(service, deckId, clock);
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
        return currentPosition < queuedCardIds.size()
                ? Optional.of(queuedCardIds.get(currentPosition)) : Optional.empty();
    }

    /**
     * Returns a fresh Hiragana prompt snapshot for the current card.
     *
     * @return current prompt, or empty after completion or for an empty session.
     * @throws IllegalStateException if the queued card no longer exists.
     */
    public Optional<Prompt> currentPrompt() {
        if (currentCardId().isEmpty()) {
            return Optional.empty();
        }
        VocabularyCard card = requireCurrentCard();
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
        VocabularyCard card = requireCurrentCard();
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
        requireExpectedCurrentCard(expectedCardId);
        requireState(State.PROMPT, "Only a prompt can be revealed");
        requireCurrentCard();
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
        requireExpectedCurrentCard(expectedCardId);
        requireState(State.ANSWER_REVEALED, "Only a revealed answer can be submitted");
        Objects.requireNonNull(outcome, "Review outcome cannot be null");
        if (outcome == ReviewOutcome.SKIPPED) {
            throw new IllegalArgumentException("Skipped outcomes are not accepted");
        }
        boolean correctOutcome;
        if (outcome == ReviewOutcome.CORRECT) {
            correctOutcome = true;
        } else if (outcome == ReviewOutcome.INCORRECT) {
            correctOutcome = false;
        } else {
            throw new IllegalArgumentException("Unsupported review outcome");
        }
        requireCurrentCard();
        service.recordFlashcardOutcome(expectedCardId, outcome);

        if (correctOutcome) {
            correct++;
        } else {
            incorrect++;
        }
        currentPosition++;
        state = currentPosition == queuedCardIds.size() ? State.COMPLETED : State.PROMPT;
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
        return queuedCardIds.size() - attempted();
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
        return new Summary(queuedCardIds.size(), correct, incorrect, attempted(), remaining(),
                stopped());
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
        return requireCard(service, cardId);
    }

    private static VocabularyCard requireCard(KokoService service, UUID cardId) {
        return service.data().findVocabularyCard(cardId).orElseThrow(() ->
                new IllegalStateException("Vocabulary card " + cardId + " no longer exists"));
    }

    private static VocabularyCard requireCardForStart(KokoService service, UUID cardId) {
        return service.data().findVocabularyCard(cardId).orElseThrow(() ->
                new IllegalArgumentException("Vocabulary card " + cardId + " does not exist"));
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
                VocabularyCard card = requireCardForStart(service, cardId);
                ModeProgress progress = card.progressFor(Mode.FLASHCARD);
                if (progress.isDueOn(startDate)) {
                    dueCards.add(new QueuedCard(card.id(), progress.nextDueDate()));
                }
            }
        }
        dueCards.sort(Comparator.comparing(QueuedCard::dueDate));
        return dueCards.stream().map(QueuedCard::cardId).toList();
    }
}
