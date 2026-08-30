package koko.service;

import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;

import koko.model.Deck;
import koko.model.KokoData;
import koko.model.Mode;
import koko.model.ModeProgress;
import koko.model.VocabularyCard;
import koko.storage.Storage;
import koko.storage.StorageException;
import koko.transfer.DeckTransfer;
import koko.transfer.DeckTransferException;
import koko.transfer.PortableCard;
import koko.transfer.PortableDeck;

/**
 * Application service for the current Koko data set.
 *
 * <p>The service keeps domain operations together with their persistence
 * boundary. A successful mutation is saved exactly once. If saving fails,
 * the in-memory mutation is rolled back.
 */
public final class KokoService {

    private final Storage storage;
    private final Clock clock;
    private final DeckTransfer deckTransfer;
    private KokoData data;

    /**
     * Creates a service with an empty current state.
     *
     * @param storage persistence boundary.
     * @param clock clock used for new-card creation dates.
     * @throws NullPointerException if storage or clock is null.
     */
    public KokoService(Storage storage, Clock clock) {
        this.storage = Objects.requireNonNull(storage, "Storage cannot be null");
        this.clock = Objects.requireNonNull(clock, "Clock cannot be null");
        deckTransfer = new DeckTransfer();
        data = new KokoData();
    }

    /**
     * Loads the complete current state from storage.
     *
     * @throws StorageException if the stored state cannot be loaded or is invalid.
     */
    public void load() throws StorageException {
        data = Objects.requireNonNull(storage.load(), "Storage returned no data");
    }

    /**
     * Returns the current domain state.
     *
     * @return current vocabulary and deck state.
     */
    public KokoData data() {
        return data;
    }

    /**
     * Imports one portable deck as a single transactional application mutation.
     *
     * <p>The document is fully parsed and validated before the service samples
     * the import date or changes a detached candidate. Existing global cards are
     * matched by the domain identity and retain their text, UUID, memberships,
     * and both progress records.
     *
     * @param source source portable JSON file.
     * @return the newly created deck.
     * @throws DeckTransferException if the source cannot be read or violates the
     *         portable format.
     * @throws IllegalArgumentException if the deck name conflicts with an existing
     *         deck.
     * @throws StorageException if the complete candidate cannot be persisted.
     * @throws NullPointerException if source is null.
     */
    public Deck importDeck(Path source) throws DeckTransferException, StorageException {
        PortableDeck document = deckTransfer.read(source);
        LocalDate importDate = LocalDate.now(clock);
        KokoData candidate = copyOf(data);
        List<UUID> resolvedCardIds = new ArrayList<>();
        for (PortableCard portableCard : document.cards()) {
            VocabularyCard card = candidate.findVocabularyCardByIdentity(
                    portableCard.hiragana(), portableCard.englishMeaning()).orElse(null);
            if (card == null) {
                card = candidate.addVocabularyCard(portableCard.hiragana(), portableCard.romaji(),
                        portableCard.englishMeaning(), importDate);
            }
            resolvedCardIds.add(card.id());
        }
        Deck importedDeck = candidate.createDeck(document.deckName());
        for (UUID cardId : resolvedCardIds) {
            candidate.addCardToDeck(importedDeck.id(), cardId);
        }
        storage.save(candidate);
        data = candidate;
        return importedDeck;
    }

    /**
     * Exports one current deck to a new portable JSON file.
     *
     * <p>Membership order is taken from the deck while card text is resolved
     * from the current global library. Export does not persist or otherwise
     * mutate application state.
     *
     * @param deckId deck to export.
     * @param destination new destination JSON file.
     * @throws DeckTransferException if serialization or destination writing fails.
     * @throws IllegalArgumentException if deckId is unknown.
     * @throws NullPointerException if deckId or destination is null.
     */
    public void exportDeck(UUID deckId, Path destination) throws DeckTransferException {
        Deck deck = data.findDeckById(deckId)
                .orElseThrow(() -> new IllegalArgumentException("Deck does not exist"));
        List<PortableCard> cards = deck.cardIds().stream()
                .map(cardId -> data.findVocabularyCard(cardId).orElseThrow(() ->
                        new IllegalStateException("Deck references an unknown card")))
                .map(card -> new PortableCard(card.hiragana(), card.romaji(),
                        card.englishMeaning()))
                .toList();
        deckTransfer.write(new PortableDeck(DeckTransfer.CURRENT_SCHEMA_VERSION, deck.name(), cards),
                destination);
    }

    /**
     * Creates and persists a globally owned vocabulary card.
     *
     * @param hiragana Hiragana text.
     * @param romaji romaji pronunciation.
     * @param englishMeaning English meaning.
     * @return the newly created card.
     * @throws IllegalArgumentException if a field is blank, invalid, or duplicated.
     * @throws NullPointerException if a card field is null.
     * @throws StorageException if persistence fails.
     */
    public VocabularyCard addVocabularyCard(String hiragana, String romaji,
            String englishMeaning) throws StorageException {
        LocalDate creationDate = LocalDate.now(clock);
        return mutate(working -> working.addVocabularyCard(hiragana, romaji, englishMeaning,
                creationDate));
    }

    /**
     * Edits a global card while retaining its identity and progress.
     *
     * @param cardId card to edit.
     * @param hiragana replacement Hiragana text.
     * @param romaji replacement romaji pronunciation.
     * @param englishMeaning replacement English meaning.
     * @throws IllegalArgumentException if the card ID is unknown, a field is invalid,
     *         or the replacement is duplicated.
     * @throws NullPointerException if the card ID or a replacement field is null.
     * @throws StorageException if persistence fails.
     */
    public void editVocabularyCard(UUID cardId, String hiragana, String romaji,
            String englishMeaning) throws StorageException {
        mutate(working -> {
            working.editVocabularyCard(cardId, hiragana, romaji, englishMeaning);
            return null;
        });
    }

    /**
     * Deletes a global card and all of its deck memberships.
     *
     * @param cardId card to delete.
     * @throws IllegalArgumentException if the card ID is unknown.
     * @throws NullPointerException if the card ID is null.
     * @throws StorageException if persistence fails.
     */
    public void deleteVocabularyCard(UUID cardId) throws StorageException {
        mutate(working -> {
            working.deleteVocabularyCard(cardId);
            return null;
        });
    }

    /**
     * Creates and persists a uniquely named deck.
     *
     * @param name deck name.
     * @return the newly created deck.
     * @throws IllegalArgumentException if the name is blank or already used.
     * @throws NullPointerException if the name is null.
     * @throws StorageException if persistence fails.
     */
    public Deck createDeck(String name) throws StorageException {
        return mutate(working -> working.createDeck(name));
    }

    /**
     * Renames a deck while retaining its identity and memberships.
     *
     * @param deckId deck to rename.
     * @param newName replacement name.
     * @throws IllegalArgumentException if the deck ID is unknown, the name is blank,
     *         or the name is already used.
     * @throws NullPointerException if the deck ID or replacement name is null.
     * @throws StorageException if persistence fails.
     */
    public void renameDeck(UUID deckId, String newName) throws StorageException {
        mutate(working -> {
            working.renameDeck(deckId, newName);
            return null;
        });
    }

    /**
     * Deletes a deck and persists the removal without deleting its global cards.
     *
     * @param deckId deck to delete.
     * @throws IllegalArgumentException if the deck ID is unknown.
     * @throws NullPointerException if the deck ID is null.
     * @throws StorageException if persistence fails.
     */
    public void deleteDeck(UUID deckId) throws StorageException {
        mutate(working -> {
            working.deleteDeck(deckId);
            return null;
        });
    }

    /**
     * Adds an existing global card to a deck.
     *
     * @param deckId destination deck.
     * @param cardId existing global card.
     * @throws IllegalArgumentException if either ID is unknown or the card is already
     *         in the deck.
     * @throws NullPointerException if either ID is null.
     * @throws StorageException if persistence fails.
     */
    public void addCardToDeck(UUID deckId, UUID cardId) throws StorageException {
        mutate(working -> {
            working.addCardToDeck(deckId, cardId);
            return null;
        });
    }

    /**
     * Removes a card from a deck without deleting the global card.
     *
     * @param deckId deck to change.
     * @param cardId card membership to remove.
     * @throws IllegalArgumentException if either ID is unknown or the card is not in
     *         the deck.
     * @throws NullPointerException if either ID is null.
     * @throws StorageException if persistence fails.
     */
    public void removeCardFromDeck(UUID deckId, UUID cardId) throws StorageException {
        mutate(working -> {
            working.removeCardFromDeck(deckId, cardId);
            return null;
        });
    }

    /**
     * Records one flashcard review outcome and persists the resulting progress.
     *
     * <p>The review is allowed for any globally stored card, regardless of deck
     * membership or due status. The date is sampled from the injected clock for
     * this submission.
     *
     * @param cardId global card whose flashcard progress is reviewed.
     * @param outcome correct or incorrect review result.
     * @throws IllegalArgumentException if the card is unknown, outcome is skipped,
     *         or incrementing the attempt count exceeds the supported integer range.
     * @throws NullPointerException if cardId or outcome is null.
     * @throws java.time.DateTimeException if the next due date exceeds the range supported by {@link LocalDate}.
     * @throws StorageException if persistence fails.
     */
    public void recordFlashcardOutcome(UUID cardId, ReviewOutcome outcome)
            throws StorageException {
        Objects.requireNonNull(cardId, "Card ID cannot be null");
        Objects.requireNonNull(outcome, "Review outcome cannot be null");
        if (outcome == ReviewOutcome.SKIPPED) {
            throw new IllegalArgumentException("Skipped outcomes are not recorded");
        }

        recordOutcome(cardId, outcome, Mode.FLASHCARD);
    }

    /**
     * Records one English-to-Hiragana typing outcome and persists the resulting progress.
     *
     * <p>Typing outcomes are allowed for any globally stored card, regardless of deck
     * membership or due status. The date is sampled from the injected clock for this
     * submission, and only the card's typing progress is changed.
     *
     * @param cardId global card whose typing progress is reviewed.
     * @param outcome correct, incorrect, or skipped review result.
     * @throws IllegalArgumentException if the card is unknown or incrementing the attempt
     *         count exceeds the supported integer range.
     * @throws NullPointerException if cardId or outcome is null.
     * @throws java.time.DateTimeException if the next due date exceeds the range supported by {@link LocalDate}.
     * @throws StorageException if persistence fails.
     */
    public void recordTypingOutcome(UUID cardId, ReviewOutcome outcome)
            throws StorageException {
        Objects.requireNonNull(cardId, "Card ID cannot be null");
        Objects.requireNonNull(outcome, "Review outcome cannot be null");

        recordOutcome(cardId, outcome, Mode.TYPING);
    }

    private void recordOutcome(UUID cardId, ReviewOutcome outcome, Mode mode)
            throws StorageException {

        LocalDate reviewDate = LocalDate.now(clock);
        KokoData candidate = copyOf(data);
        VocabularyCard card = candidate.findVocabularyCard(cardId)
                .orElseThrow(() -> new IllegalArgumentException("Vocabulary card does not exist"));
        ModeProgress scheduled = new MasteryScheduler().schedule(
                card.progressFor(mode), outcome, reviewDate);
        card.updateProgress(mode, scheduled);
        storage.save(candidate);
        data = candidate;
    }

    private <T> T mutate(Function<KokoData, T> operation) throws StorageException {
        KokoData original = copyOf(data);
        try {
            T result = operation.apply(data);
            storage.save(data);
            return result;
        } catch (StorageException | RuntimeException exception) {
            data = original;
            throw exception;
        }
    }

    private static KokoData copyOf(KokoData source) {
        List<VocabularyCard> cards = new ArrayList<>();
        for (VocabularyCard card : source.vocabularyCards()) {
            cards.add(VocabularyCard.restore(card.id(), card.hiragana(), card.romaji(),
                    card.englishMeaning(), copyProgress(card.progressFor(Mode.FLASHCARD)),
                    copyProgress(card.progressFor(Mode.TYPING))));
        }
        List<Deck> decks = source.decks().stream()
                .map(deck -> Deck.restore(deck.id(), deck.name(), deck.cardIds()))
                .toList();
        return KokoData.restore(cards, decks);
    }

    private static ModeProgress copyProgress(ModeProgress progress) {
        return new ModeProgress(progress.mastery(), progress.attempts(),
                progress.correctAttempts(), progress.lastReviewedDate(), progress.nextDueDate());
    }
}
