package koko.service;

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
    private KokoData data;

    /**
     * Creates a service with an empty current state.
     *
     * @param storage persistence boundary
     * @param clock clock used for new-card creation dates
     * @throws NullPointerException if storage or clock is null
     */
    public KokoService(Storage storage, Clock clock) {
        this.storage = Objects.requireNonNull(storage, "Storage cannot be null");
        this.clock = Objects.requireNonNull(clock, "Clock cannot be null");
        data = new KokoData();
    }

    /**
     * Loads the complete current state from storage.
     *
     * @throws StorageException if the stored state cannot be loaded or is invalid
     */
    public void load() throws StorageException {
        data = Objects.requireNonNull(storage.load(), "Storage returned no data");
    }

    /**
     * Returns the current domain state.
     *
     * @return current vocabulary and deck state
     */
    public KokoData data() {
        return data;
    }

    /**
     * Creates and persists a globally owned vocabulary card.
     *
     * @param hiragana Hiragana text
     * @param romaji romaji pronunciation
     * @param englishMeaning English meaning
     * @return the newly created card
     * @throws IllegalArgumentException if a field is blank, invalid, or duplicated
     * @throws NullPointerException if a card field is null
     * @throws StorageException if persistence fails
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
     * @param cardId card to edit
     * @param hiragana replacement Hiragana text
     * @param romaji replacement romaji pronunciation
     * @param englishMeaning replacement English meaning
     * @throws IllegalArgumentException if the card ID is unknown, a field is invalid,
     *         or the replacement is duplicated
     * @throws NullPointerException if the card ID or a replacement field is null
     * @throws StorageException if persistence fails
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
     * @param cardId card to delete
     * @throws IllegalArgumentException if the card ID is unknown
     * @throws NullPointerException if the card ID is null
     * @throws StorageException if persistence fails
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
     * @param name deck name
     * @return the newly created deck
     * @throws IllegalArgumentException if the name is blank or already used
     * @throws NullPointerException if the name is null
     * @throws StorageException if persistence fails
     */
    public Deck createDeck(String name) throws StorageException {
        return mutate(working -> working.createDeck(name));
    }

    /**
     * Renames a deck while retaining its identity and memberships.
     *
     * @param deckId deck to rename
     * @param newName replacement name
     * @throws IllegalArgumentException if the deck ID is unknown, the name is blank,
     *         or the name is already used
     * @throws NullPointerException if the deck ID or replacement name is null
     * @throws StorageException if persistence fails
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
     * @param deckId deck to delete
     * @throws IllegalArgumentException if the deck ID is unknown
     * @throws NullPointerException if the deck ID is null
     * @throws StorageException if persistence fails
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
     * @param deckId destination deck
     * @param cardId existing global card
     * @throws IllegalArgumentException if either ID is unknown or the card is already
     *         in the deck
     * @throws NullPointerException if either ID is null
     * @throws StorageException if persistence fails
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
     * @param deckId deck to change
     * @param cardId card membership to remove
     * @throws IllegalArgumentException if either ID is unknown or the card is not in
     *         the deck
     * @throws NullPointerException if either ID is null
     * @throws StorageException if persistence fails
     */
    public void removeCardFromDeck(UUID deckId, UUID cardId) throws StorageException {
        mutate(working -> {
            working.removeCardFromDeck(deckId, cardId);
            return null;
        });
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
