package koko.model;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Owns the ordered global vocabulary library and decks, including their invariants.
 */
public final class KokoData {

    private final List<VocabularyCard> vocabularyCards = new ArrayList<>();
    private final List<Deck> decks = new ArrayList<>();

    /**
     * Creates an empty Koko data set.
     */
    public KokoData() {
    }

    /**
     * Restores a complete data set while reapplying every collection and reference invariant.
     *
     * @param restoredCards ordered globally stored cards.
     * @param restoredDecks ordered decks.
     * @return a validated restored data set.
     * @throws IllegalArgumentException if identities, content, names, or references are invalid.
     * @throws NullPointerException if a collection or element is null.
     */
    public static KokoData restore(List<VocabularyCard> restoredCards,
            List<Deck> restoredDecks) {
        KokoData restored = new KokoData();
        List<VocabularyCard> cards = List.copyOf(Objects.requireNonNull(
                restoredCards, "Vocabulary cards cannot be null"));
        List<Deck> decksToRestore = List.copyOf(Objects.requireNonNull(
                restoredDecks, "Decks cannot be null"));
        Set<UUID> cardIds = new HashSet<>();
        for (VocabularyCard card : cards) {
            Objects.requireNonNull(card, "Vocabulary card cannot be null");
            if (!cardIds.add(card.id())) {
                throw new IllegalArgumentException("Vocabulary card ID is duplicated");
            }
            restored.ensureVocabularyIsUnique(card);
            restored.vocabularyCards.add(card);
        }

        Set<UUID> deckIds = new HashSet<>();
        for (Deck deck : decksToRestore) {
            Objects.requireNonNull(deck, "Deck cannot be null");
            if (!deckIds.add(deck.id())) {
                throw new IllegalArgumentException("Deck ID is duplicated");
            }
            restored.ensureDeckNameIsUnique(deck);
            for (UUID cardId : deck.cardIds()) {
                if (!cardIds.contains(cardId)) {
                    throw new IllegalArgumentException("Deck references an unknown card");
                }
            }
            restored.decks.add(deck);
        }
        return restored;
    }

    /**
     * Creates and stores a globally unique vocabulary card.
     *
     * @param hiragana Hiragana text stored on the new card.
     * @param romaji romaji pronunciation stored on the new card.
     * @param englishMeaning English meaning stored on the new card.
     * @param creationDate date used for both fresh progress records.
     * @return the newly stored card.
     * @throws IllegalArgumentException if the card is invalid or globally duplicated.
     * @throws NullPointerException if a required argument is null.
     */
    public VocabularyCard addVocabularyCard(String hiragana, String romaji,
            String englishMeaning, LocalDate creationDate) {
        VocabularyCard card = new VocabularyCard(hiragana, romaji, englishMeaning, creationDate);
        ensureVocabularyIsUnique(card);
        vocabularyCards.add(card);
        return card;
    }

    /**
     * Creates and stores a deck with a globally unique name.
     *
     * @param name deck name.
     * @return the newly stored deck.
     * @throws IllegalArgumentException if the name is invalid or already used.
     * @throws NullPointerException if name is null.
     */
    public Deck createDeck(String name) {
        Deck deck = new Deck(name);
        ensureDeckNameIsUnique(deck);
        decks.add(deck);
        return deck;
    }

    /**
     * Renames an owned deck while preserving global name uniqueness.
     *
     * @param deckId ID of the deck to rename.
     * @param newName replacement deck name.
     * @throws IllegalArgumentException if the ID or name is invalid, or the name is already used.
     * @throws NullPointerException if an argument is null.
     */
    public void renameDeck(UUID deckId, String newName) {
        Deck deck = findDeck(deckId);
        Deck candidate = new Deck(newName);
        boolean duplicate = decks.stream().anyMatch(existing -> existing != deck
                && existing.name().equalsIgnoreCase(candidate.name()));
        if (duplicate) {
            throw new IllegalArgumentException("Deck name is already in use");
        }
        deck.rename(candidate.name());
    }

    /**
     * Deletes a deck while preserving every globally owned vocabulary card.
     *
     * @param deckId ID of the deck to delete.
     * @throws IllegalArgumentException if deckId is unknown.
     * @throws NullPointerException if deckId is null.
     */
    public void deleteDeck(UUID deckId) {
        decks.remove(findDeck(deckId));
    }

    /**
     * Edits an owned card while preserving its identity and progress.
     *
     * @param cardId ID of the card to edit.
     * @param newHiragana replacement Hiragana text.
     * @param newRomaji replacement romaji pronunciation.
     * @param newEnglishMeaning replacement English meaning.
     * @throws IllegalArgumentException if the ID, text, or global uniqueness rule is invalid.
     * @throws NullPointerException if an argument is null.
     */
    public void editVocabularyCard(UUID cardId, String newHiragana, String newRomaji,
            String newEnglishMeaning) {
        VocabularyCard card = requireVocabularyCard(cardId);
        VocabularyCard.validateContent(newHiragana, newRomaji, newEnglishMeaning);
        String normalizedHiragana = VocabularyCard.normalizeHiragana(newHiragana);
        String normalizedMeaning = VocabularyCard.normalizeEnglishMeaning(newEnglishMeaning);
        boolean duplicate = vocabularyCards.stream().anyMatch(existing -> existing != card
                && existing.hiragana().equals(normalizedHiragana)
                && existing.englishMeaning().equalsIgnoreCase(normalizedMeaning));
        if (duplicate) {
            throw new IllegalArgumentException("Vocabulary card is already in the global library");
        }
        card.editContent(newHiragana, newRomaji, newEnglishMeaning);
    }

    /**
     * Adds a global card reference to a deck while preserving referential integrity.
     *
     * @param deckId ID of an owned deck.
     * @param cardId ID of an owned vocabulary card.
     * @throws IllegalArgumentException if either ID is unknown or membership is duplicated.
     * @throws NullPointerException if an ID is null.
     */
    public void addCardToDeck(UUID deckId, UUID cardId) {
        findDeck(deckId).addCard(requireVocabularyCard(cardId).id());
    }

    /**
     * Removes a card reference from a deck without deleting the global card.
     *
     * @param deckId ID of an owned deck.
     * @param cardId ID of an owned vocabulary card.
     * @throws IllegalArgumentException if either ID is unknown or membership is absent.
     * @throws NullPointerException if an ID is null.
     */
    public void removeCardFromDeck(UUID deckId, UUID cardId) {
        requireVocabularyCard(cardId);
        findDeck(deckId).removeCard(cardId);
    }

    /**
     * Deletes a global card and every membership that references it.
     *
     * @param cardId ID of the global card to delete.
     * @throws IllegalArgumentException if cardId is unknown.
     * @throws NullPointerException if cardId is null.
     */
    public void deleteVocabularyCard(UUID cardId) {
        VocabularyCard card = requireVocabularyCard(cardId);
        vocabularyCards.remove(card);
        for (Deck deck : decks) {
            if (deck.containsCard(cardId)) {
                deck.removeCard(cardId);
            }
        }
    }

    /**
     * Finds a globally stored vocabulary card.
     *
     * @param cardId card ID to find.
     * @return matching card, or empty when no card has that ID.
     */
    public java.util.Optional<VocabularyCard> findVocabularyCard(UUID cardId) {
        Objects.requireNonNull(cardId, "Card ID cannot be null");
        return vocabularyCards.stream().filter(card -> card.id().equals(cardId)).findFirst();
    }

    /**
     * Finds an owned deck.
     *
     * @param deckId deck ID to find.
     * @return matching deck, or empty when no deck has that ID.
     */
    public java.util.Optional<Deck> findDeckById(UUID deckId) {
        Objects.requireNonNull(deckId, "Deck ID cannot be null");
        return decks.stream().filter(deck -> deck.id().equals(deckId)).findFirst();
    }

    /**
     * Returns the ordered global vocabulary library as a read-only collection.
     *
     * @return read-only vocabulary cards in insertion order.
     */
    public List<VocabularyCard> vocabularyCards() {
        return Collections.unmodifiableList(vocabularyCards);
    }

    /**
     * Returns the ordered decks as a read-only collection.
     *
     * @return read-only decks in insertion order.
     */
    public List<Deck> decks() {
        return Collections.unmodifiableList(decks);
    }

    private void ensureVocabularyIsUnique(VocabularyCard candidate) {
        boolean duplicate = vocabularyCards.stream().anyMatch(existing ->
                existing.hiragana().equals(candidate.hiragana())
                        && existing.englishMeaning().equalsIgnoreCase(candidate.englishMeaning()));
        if (duplicate) {
            throw new IllegalArgumentException("Vocabulary card is already in the global library");
        }
    }

    private void ensureDeckNameIsUnique(Deck candidate) {
        boolean duplicate = decks.stream().anyMatch(existing ->
                existing.name().equalsIgnoreCase(candidate.name()));
        if (duplicate) {
            throw new IllegalArgumentException("Deck name is already in use");
        }
    }

    private VocabularyCard requireVocabularyCard(UUID cardId) {
        return findVocabularyCard(cardId)
                .orElseThrow(() -> new IllegalArgumentException("Vocabulary card does not exist"));
    }

    private Deck findDeck(UUID deckId) {
        return findDeckById(deckId)
                .orElseThrow(() -> new IllegalArgumentException("Deck does not exist"));
    }

    private static String requireNonBlank(String value, String fieldName) {
        Objects.requireNonNull(value, fieldName + " cannot be null");
        String trimmed = value.strip();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException(fieldName + " cannot be blank");
        }
        return trimmed;
    }
}
