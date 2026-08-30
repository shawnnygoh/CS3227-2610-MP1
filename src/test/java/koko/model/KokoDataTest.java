package koko.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertIterableEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

/**
 * Tests global library, deck, and referential-integrity rules.
 */
class KokoDataTest {

    private static final LocalDate CREATION_DATE = LocalDate.of(2026, 8, 29);

    @Test
    void globalDuplicateUsesNormalisedHiraganaAndCaseInsensitiveMeaning() {
        KokoData data = new KokoData();
        data.addVocabularyCard("  か\u3099  ", "ga", " Cat ", CREATION_DATE);

        assertThrows(IllegalArgumentException.class, () ->
                data.addVocabularyCard("が", "ga", "cat", CREATION_DATE));
        assertEquals(1, data.vocabularyCards().size());
    }

    @Test
    void duplicateDetectionRemainsConsistentWithCanonicalTextNormalization() {
        KokoData data = new KokoData();
        data.addVocabularyCard("ねこ", "neko", "café", CREATION_DATE);

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () ->
                data.addVocabularyCard("ねこ", "neko2", "cafe\u0301", CREATION_DATE));

        assertTrue(exception.getMessage().contains("already in the global library"));
        assertEquals(1, data.vocabularyCards().size());
    }

    @Test
    void editingToCanonicallyEquivalentMeaningIsRejectedAsDuplicate() {
        KokoData data = new KokoData();
        VocabularyCard existing = data.addVocabularyCard("ねこ", "neko", "café", CREATION_DATE);
        VocabularyCard edited = data.addVocabularyCard("いぬ", "inu", "dog", CREATION_DATE);

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () ->
                data.editVocabularyCard(edited.id(), "ねこ", "inu2", "cafe\u0301"));

        assertTrue(exception.getMessage().contains("already in the global library"));
        assertEquals("ねこ", existing.hiragana());
        assertEquals("café", existing.englishMeaning());
        assertEquals("いぬ", edited.hiragana());
        assertEquals("inu", edited.romaji());
        assertEquals("dog", edited.englishMeaning());
    }

    @Test
    void nearDuplicatesRemainDistinctWhenEitherDuplicateKeyDiffers() {
        KokoData data = new KokoData();
        VocabularyCard first = data.addVocabularyCard("ねこ", "neko", "cat", CREATION_DATE);
        VocabularyCard differentMeaning = data.addVocabularyCard(
                "ねこ", "neko", "kitten", CREATION_DATE);
        VocabularyCard differentHiragana = data.addVocabularyCard(
                "いぬ", "inu", "cat", CREATION_DATE);

        assertEquals(List.of(first, differentMeaning, differentHiragana), data.vocabularyCards());
    }

    @Test
    void deckNamesAreTrimmedAndUniqueIgnoringCase() {
        KokoData data = new KokoData();
        Deck first = data.createDeck("  Core  ");

        assertThrows(IllegalArgumentException.class, () -> data.createDeck("core"));
        data.renameDeck(first.id(), "  Basics  ");
        assertEquals("Basics", first.name());
        assertThrows(IllegalArgumentException.class, () -> data.createDeck(" basics "));
    }

    @Test
    void failedDeckRenameLeavesTheExistingNameUnchanged() {
        KokoData data = new KokoData();
        Deck first = data.createDeck("First");
        Deck second = data.createDeck("Second");

        assertThrows(IllegalArgumentException.class, () ->
                data.renameDeck(first.id(), " second "));

        assertEquals("First", first.name());
        assertEquals("Second", second.name());
    }

    @Test
    void cardsCanBeSharedAcrossMultipleDecksWithoutCopying() {
        KokoData data = new KokoData();
        VocabularyCard card = data.addVocabularyCard("ねこ", "neko", "cat", CREATION_DATE);
        Deck first = data.createDeck("First");
        Deck second = data.createDeck("Second");

        data.addCardToDeck(first.id(), card.id());
        data.addCardToDeck(second.id(), card.id());

        assertEquals(List.of(card.id()), first.cardIds());
        assertEquals(List.of(card.id()), second.cardIds());
        assertSame(card, data.findVocabularyCard(card.id()).orElseThrow());
        assertEquals(1, data.vocabularyCards().size());
    }

    @Test
    void duplicateMembershipAndUnknownReferencesAreRejected() {
        KokoData data = new KokoData();
        VocabularyCard card = data.addVocabularyCard("ねこ", "neko", "cat", CREATION_DATE);
        Deck deck = data.createDeck("Core");

        data.addCardToDeck(deck.id(), card.id());
        assertThrows(IllegalArgumentException.class, () ->
                data.addCardToDeck(deck.id(), card.id()));
        assertThrows(IllegalArgumentException.class, () ->
                data.addCardToDeck(deck.id(), UUID.randomUUID()));
        assertThrows(IllegalArgumentException.class, () ->
                data.addCardToDeck(UUID.randomUUID(), card.id()));
    }

    @Test
    void removingMembershipPreservesGlobalCard() {
        KokoData data = new KokoData();
        VocabularyCard card = data.addVocabularyCard("ねこ", "neko", "cat", CREATION_DATE);
        Deck deck = data.createDeck("Core");
        data.addCardToDeck(deck.id(), card.id());

        data.removeCardFromDeck(deck.id(), card.id());

        assertTrue(deck.cardIds().isEmpty());
        assertTrue(data.findVocabularyCard(card.id()).isPresent());
    }

    @Test
    void globalDeletionRemovesEveryMembership() {
        KokoData data = new KokoData();
        VocabularyCard card = data.addVocabularyCard("ねこ", "neko", "cat", CREATION_DATE);
        Deck first = data.createDeck("First");
        Deck second = data.createDeck("Second");
        data.addCardToDeck(first.id(), card.id());
        data.addCardToDeck(second.id(), card.id());

        data.deleteVocabularyCard(card.id());

        assertTrue(data.vocabularyCards().isEmpty());
        assertTrue(first.cardIds().isEmpty());
        assertTrue(second.cardIds().isEmpty());
    }

    @Test
    void dataCollectionsAreReadOnlyAndRetainInsertionOrder() {
        KokoData data = new KokoData();
        VocabularyCard first = data.addVocabularyCard("ねこ", "neko", "cat", CREATION_DATE);
        VocabularyCard second = data.addVocabularyCard("いぬ", "inu", "dog", CREATION_DATE);
        Deck firstDeck = data.createDeck("First");
        Deck secondDeck = data.createDeck("Second");

        assertIterableEquals(List.of(first, second), data.vocabularyCards());
        assertIterableEquals(List.of(firstDeck, secondDeck), data.decks());
        assertThrows(UnsupportedOperationException.class, () ->
                data.vocabularyCards().clear());
        assertThrows(UnsupportedOperationException.class, () -> data.decks().clear());
    }

    @Test
    void cardAndDeckIdentitiesAreStableAndDistinct() {
        KokoData data = new KokoData();
        VocabularyCard firstCard = data.addVocabularyCard("ねこ", "neko", "cat", CREATION_DATE);
        VocabularyCard secondCard = data.addVocabularyCard("いぬ", "inu", "dog", CREATION_DATE);
        Deck firstDeck = data.createDeck("First");
        Deck secondDeck = data.createDeck("Second");

        assertNotEquals(firstCard.id(), secondCard.id());
        assertNotEquals(firstDeck.id(), secondDeck.id());
    }

    @Test
    void missingLookupsReturnEmptyAndNullIdsAreRejected() {
        KokoData data = new KokoData();
        UUID missingId = UUID.randomUUID();

        assertTrue(data.findVocabularyCard(missingId).isEmpty());
        assertTrue(data.findDeckById(missingId).isEmpty());
        assertThrows(NullPointerException.class, () -> data.findVocabularyCard(null));
        assertThrows(NullPointerException.class, () -> data.findDeckById(null));
    }

    @Test
    void editingThroughDataPreservesProgressAndRejectsNewDuplicate() {
        KokoData data = new KokoData();
        VocabularyCard first = data.addVocabularyCard("ねこ", "neko", "cat", CREATION_DATE);
        VocabularyCard second = data.addVocabularyCard("いぬ", "inu", "dog", CREATION_DATE);
        ModeProgress progress = new ModeProgress(4, 6, 5,
                CREATION_DATE.plusDays(1), CREATION_DATE.plusDays(15));
        first.updateProgress(Mode.FLASHCARD, progress);

        assertThrows(IllegalArgumentException.class, () ->
                data.editVocabularyCard(second.id(), "ねこ", "neko2", "CAT"));
        data.editVocabularyCard(first.id(), "  ねこ  ", " neko2 ", " animal ");

        assertEquals("neko2", first.romaji());
        assertEquals("animal", first.englishMeaning());
        assertEquals(progress, first.progressFor(Mode.FLASHCARD));
    }

    @Test
    void failedDataEditDoesNotPartiallyChangeCard() {
        KokoData data = new KokoData();
        VocabularyCard card = data.addVocabularyCard("ねこ", "neko", "cat", CREATION_DATE);

        assertThrows(IllegalArgumentException.class, () ->
                data.editVocabularyCard(card.id(), "いぬ", "   ", "dog"));

        assertEquals("ねこ", card.hiragana());
        assertEquals("neko", card.romaji());
        assertEquals("cat", card.englishMeaning());
    }
}
