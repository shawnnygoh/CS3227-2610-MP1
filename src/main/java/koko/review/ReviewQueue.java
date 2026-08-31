package koko.review;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import koko.model.Deck;
import koko.model.Mode;
import koko.model.ModeProgress;
import koko.model.VocabularyCard;
import koko.service.KokoService;

/**
 * The frozen card queue shared by Koko's review sessions.
 *
 * <p>A queue is selected once when a session starts and never grows. It stores
 * only card IDs and a position, so card text is always resolved from the service
 * at the moment it is used and a persistence operation cannot leave a session
 * holding a stale card.
 *
 * <p>The queue owns selection, ordering, and current-card checks. Lifecycle
 * states and outcome counters differ between review modes and stay with the
 * session that owns this queue.
 *
 * <p>Deck membership already guarantees unique card IDs, so deck queues do not
 * need to deduplicate them.
 */
final class ReviewQueue {

    private record QueuedCard(UUID cardId, LocalDate dueDate) {
    }

    private final KokoService service;
    private final List<UUID> cardIds;
    private int currentPosition;

    private ReviewQueue(KokoService service, List<UUID> cardIds) {
        this.service = service;
        this.cardIds = List.copyOf(cardIds);
    }

    /**
     * Creates a queue holding one selected global card, regardless of due status
     * or deck membership.
     *
     * @param service service holding the selected card.
     * @param cardId selected global card.
     * @return a single-card queue.
     * @throws IllegalArgumentException if the card does not exist.
     * @throws NullPointerException if an argument is null.
     */
    static ReviewQueue forSingleCard(KokoService service, UUID cardId) {
        KokoService checkedService = Objects.requireNonNull(service, "Service cannot be null");
        UUID checkedCardId = Objects.requireNonNull(cardId, "Card ID cannot be null");
        requireCardForStart(checkedService, checkedCardId);
        return new ReviewQueue(checkedService, List.of(checkedCardId));
    }

    /**
     * Creates a queue of the cards in a deck that are due for one learning mode.
     *
     * <p>Eligibility is evaluated once using the date from {@code clock}. Cards are
     * ordered by oldest due date first, with stable deck order for equal due dates.
     *
     * @param service service holding the current global cards and decks.
     * @param deckId selected deck.
     * @param clock clock used to determine the session start date.
     * @param mode learning mode whose progress decides due status.
     * @return a due-card queue in due-date order.
     * @throws IllegalArgumentException if the deck or a referenced card does not exist.
     * @throws NullPointerException if an argument is null.
     */
    static ReviewQueue forDueCardsInDeck(KokoService service, UUID deckId, Clock clock, Mode mode) {
        KokoService checkedService = Objects.requireNonNull(service, "Service cannot be null");
        UUID checkedDeckId = Objects.requireNonNull(deckId, "Deck ID cannot be null");
        Clock checkedClock = Objects.requireNonNull(clock, "Clock cannot be null");
        Objects.requireNonNull(mode, "Mode cannot be null");
        Deck deck = requireDeck(checkedService, checkedDeckId);
        LocalDate startDate = LocalDate.now(checkedClock);
        List<QueuedCard> dueCards = new ArrayList<>();
        for (UUID cardId : deck.cardIds()) {
            VocabularyCard card = requireCardForStart(checkedService, cardId);
            ModeProgress progress = card.progressFor(mode);
            if (progress.isDueOn(startDate)) {
                dueCards.add(new QueuedCard(card.id(), progress.nextDueDate()));
            }
        }
        dueCards.sort(Comparator.comparing(QueuedCard::dueDate));
        return new ReviewQueue(checkedService,
                dueCards.stream().map(QueuedCard::cardId).toList());
    }

    /**
     * Creates a queue of every card in a deck, regardless of due status.
     *
     * <p>Cards are included once in their deck membership order. The membership
     * snapshot is taken when this method is called.
     *
     * @param service service holding the current global cards and decks.
     * @param deckId selected deck.
     * @return an all-card queue in deck membership order.
     * @throws IllegalArgumentException if the deck or a referenced card does not exist.
     * @throws NullPointerException if an argument is null.
     */
    static ReviewQueue forAllCardsInDeck(KokoService service, UUID deckId) {
        KokoService checkedService = Objects.requireNonNull(service, "Service cannot be null");
        UUID checkedDeckId = Objects.requireNonNull(deckId, "Deck ID cannot be null");
        Deck deck = requireDeck(checkedService, checkedDeckId);
        List<UUID> queuedCardIds = new ArrayList<>();
        for (UUID cardId : deck.cardIds()) {
            queuedCardIds.add(requireCardForStart(checkedService, cardId).id());
        }
        return new ReviewQueue(checkedService, queuedCardIds);
    }

    /**
     * Returns the service backing this queue, for recording outcomes.
     *
     * @return service supplied when the queue was created.
     */
    KokoService service() {
        return service;
    }

    /**
     * Returns the number of cards selected when the session started.
     *
     * @return initial queue size.
     */
    int size() {
        return cardIds.size();
    }

    /**
     * Returns whether the queue selected no cards at all.
     *
     * @return true when the queue is empty.
     */
    boolean isEmpty() {
        return cardIds.isEmpty();
    }

    /**
     * Returns whether every queued card has been advanced past.
     *
     * @return true when no current card remains.
     */
    boolean isExhausted() {
        return currentPosition == cardIds.size();
    }

    /**
     * Returns the current card ID until the queue advances past it.
     *
     * <p>A card stays current after its outcome is recorded, until the owning
     * session calls {@link #advance()}. Typing sessions rely on that to display
     * feedback for the card that was just answered.
     *
     * @return current card ID, or empty once the queue is exhausted.
     */
    Optional<UUID> currentCardId() {
        return currentPosition < cardIds.size()
                ? Optional.of(cardIds.get(currentPosition)) : Optional.empty();
    }

    /** Moves past the current card. */
    void advance() {
        currentPosition++;
    }

    /**
     * Requires that a captured card ID still identifies the current card.
     *
     * @param expectedCardId card ID captured when the view was rendered.
     * @throws IllegalStateException if the event is stale or the queue is exhausted.
     * @throws NullPointerException if expectedCardId is null.
     */
    void requireExpectedCurrentCard(UUID expectedCardId) {
        Objects.requireNonNull(expectedCardId, "Expected card ID cannot be null");
        if (currentCardId().isEmpty() || !expectedCardId.equals(currentCardId().orElseThrow())) {
            throw new IllegalStateException("Card action is stale");
        }
    }

    /**
     * Resolves the current card's text from the service.
     *
     * @return the current card as the service currently holds it.
     * @throws IllegalStateException if the queue is exhausted or the card no longer exists.
     */
    VocabularyCard requireCurrentCard() {
        UUID cardId = currentCardId().orElseThrow(() ->
                new IllegalStateException("The session has no current card"));
        return service.data().findVocabularyCard(cardId).orElseThrow(() ->
                new IllegalStateException("Vocabulary card " + cardId + " no longer exists"));
    }

    private static Deck requireDeck(KokoService service, UUID deckId) {
        return service.data().findDeckById(deckId).orElseThrow(() ->
                new IllegalArgumentException("Deck does not exist"));
    }

    private static VocabularyCard requireCardForStart(KokoService service, UUID cardId) {
        return service.data().findVocabularyCard(cardId).orElseThrow(() ->
                new IllegalArgumentException("Vocabulary card " + cardId + " does not exist"));
    }
}
