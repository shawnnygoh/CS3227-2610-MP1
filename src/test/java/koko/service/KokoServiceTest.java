package koko.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import org.junit.jupiter.api.Test;

import koko.model.Deck;
import koko.model.KokoData;
import koko.model.Mode;
import koko.model.ModeProgress;
import koko.model.VocabularyCard;
import koko.storage.Storage;
import koko.storage.StorageException;

/**
 * Tests service orchestration, mutation persistence, and injected dependencies.
 */
class KokoServiceTest {

    private static final LocalDate CREATION_DATE = LocalDate.of(2026, 8, 30);
    private static final Clock FIXED_CLOCK = Clock.fixed(
            Instant.parse("2026-08-30T01:00:00Z"), ZoneId.of("Asia/Singapore"));

    @Test
    void loadsStoredStateAndMissingStateIsEmpty() throws StorageException {
        FakeStorage stored = new FakeStorage();
        KokoData savedData = new KokoData();
        savedData.addVocabularyCard("ねこ", "neko", "cat", CREATION_DATE);
        stored.loadedData = savedData;
        KokoService service = new KokoService(stored, FIXED_CLOCK);

        service.load();

        assertSame(savedData, service.data());
        assertEquals(1, service.data().vocabularyCards().size());

        FakeStorage missing = new FakeStorage();
        KokoService emptyService = new KokoService(missing, FIXED_CLOCK);
        emptyService.load();

        assertTrue(emptyService.data().vocabularyCards().isEmpty());
        assertTrue(emptyService.data().decks().isEmpty());
    }

    @Test
    void savesExactlyOnceAfterEachSuccessfulMutation() throws StorageException {
        FakeStorage storage = new FakeStorage();
        KokoService service = new KokoService(storage, FIXED_CLOCK);

        VocabularyCard card = service.addVocabularyCard("ねこ", "neko", "cat");
        Deck firstDeck = service.createDeck("Basics");
        service.addCardToDeck(firstDeck.id(), card.id());
        service.editVocabularyCard(card.id(), "ねこ", "neko", "animal");
        service.renameDeck(firstDeck.id(), "Core");
        service.removeCardFromDeck(firstDeck.id(), card.id());
        service.deleteVocabularyCard(card.id());

        assertEquals(7, storage.saveCount);
    }

    @Test
    void rejectedOperationsDoNotSave() throws StorageException {
        FakeStorage storage = new FakeStorage();
        KokoService service = new KokoService(storage, FIXED_CLOCK);
        VocabularyCard card = service.addVocabularyCard("ねこ", "neko", "cat");
        Deck deck = service.createDeck("Basics");
        service.addCardToDeck(deck.id(), card.id());
        int savesBeforeRejectedOperations = storage.saveCount;

        assertThrows(IllegalArgumentException.class, () ->
                service.addVocabularyCard("ねこ", "neko2", "CAT"));
        assertThrows(IllegalArgumentException.class, () -> service.createDeck(" basics "));
        assertThrows(IllegalArgumentException.class, () ->
                service.addCardToDeck(deck.id(), card.id()));
        assertThrows(IllegalArgumentException.class, () ->
                service.removeCardFromDeck(deck.id(), java.util.UUID.randomUUID()));

        assertEquals(savesBeforeRejectedOperations, storage.saveCount);
    }

    @Test
    void duplicateCardAndDeckNameErrorsComeFromTheDomain() throws StorageException {
        KokoService service = new KokoService(new FakeStorage(), FIXED_CLOCK);

        service.addVocabularyCard("いぬ", "inu", "dog");
        service.createDeck("Animals");

        assertThrows(IllegalArgumentException.class, () ->
                service.addVocabularyCard("いぬ", "inu2", "DOG"));
        assertThrows(IllegalArgumentException.class, () -> service.createDeck(" animals "));
    }

    @Test
    void editingPreservesIdentityAndBothProgressRecords() throws StorageException {
        KokoService service = new KokoService(new FakeStorage(), FIXED_CLOCK);
        VocabularyCard card = service.addVocabularyCard("ねこ", "neko", "cat");
        ModeProgress flashcard = new ModeProgress(3, 4, 3,
                CREATION_DATE.minusDays(1), CREATION_DATE.plusDays(5));
        ModeProgress typing = new ModeProgress(2, 3, 2,
                CREATION_DATE.minusDays(2), CREATION_DATE.plusDays(3));
        card.updateProgress(Mode.FLASHCARD, flashcard);
        card.updateProgress(Mode.TYPING, typing);

        service.editVocabularyCard(card.id(), "ねこ", "neko-2", "animal");

        VocabularyCard edited = service.data().findVocabularyCard(card.id()).orElseThrow();
        assertEquals(card.id(), edited.id());
        assertEquals("neko-2", edited.romaji());
        assertEquals("animal", edited.englishMeaning());
        assertSame(flashcard, edited.progressFor(Mode.FLASHCARD));
        assertSame(typing, edited.progressFor(Mode.TYPING));
    }

    @Test
    void creationDateComesFromTheInjectedClock() throws StorageException {
        KokoService service = new KokoService(new FakeStorage(), FIXED_CLOCK);

        VocabularyCard card = service.addVocabularyCard("そら", "sora", "sky");

        assertEquals(CREATION_DATE, card.progressFor(Mode.FLASHCARD).nextDueDate());
        assertEquals(CREATION_DATE, card.progressFor(Mode.TYPING).nextDueDate());
    }

    @Test
    void membershipCanBeZeroOneOrMultipleAndRemovalKeepsGlobalCard()
            throws StorageException {
        KokoService service = new KokoService(new FakeStorage(), FIXED_CLOCK);
        VocabularyCard card = service.addVocabularyCard("みず", "mizu", "water");
        Deck first = service.createDeck("Travel");
        Deck second = service.createDeck("Food");

        assertTrue(first.cardIds().isEmpty());
        service.addCardToDeck(first.id(), card.id());
        service.addCardToDeck(second.id(), card.id());
        assertEquals(List.of(card.id()), first.cardIds());
        assertEquals(List.of(card.id()), second.cardIds());

        service.removeCardFromDeck(first.id(), card.id());

        assertTrue(first.cardIds().isEmpty());
        assertEquals(List.of(card.id()), second.cardIds());
        assertTrue(service.data().findVocabularyCard(card.id()).isPresent());
    }

    @Test
    void globalDeletionRemovesEveryMembership() throws StorageException {
        KokoService service = new KokoService(new FakeStorage(), FIXED_CLOCK);
        VocabularyCard card = service.addVocabularyCard("やま", "yama", "mountain");
        Deck first = service.createDeck("Nature");
        Deck second = service.createDeck("Places");
        service.addCardToDeck(first.id(), card.id());
        service.addCardToDeck(second.id(), card.id());

        service.deleteVocabularyCard(card.id());

        assertTrue(service.data().findVocabularyCard(card.id()).isEmpty());
        assertTrue(first.cardIds().isEmpty());
        assertTrue(second.cardIds().isEmpty());
    }

    @Test
    void storageFailureIsSurfacedAndDoesNotCountAsACompletedSave() {
        FakeStorage storage = new FakeStorage();
        storage.failSaves = true;
        KokoService service = new KokoService(storage, FIXED_CLOCK);

        assertThrows(StorageException.class, () ->
                service.addVocabularyCard("つき", "tsuki", "moon"));
        assertEquals(0, storage.saveCount);
        assertTrue(service.data().vocabularyCards().isEmpty());
    }

    @Test
    void deletingDeckPreservesGlobalCardsAndSavesOnce() throws StorageException {
        FakeStorage storage = new FakeStorage();
        KokoService service = new KokoService(storage, FIXED_CLOCK);
        VocabularyCard card = service.addVocabularyCard("とり", "tori", "bird");
        Deck deck = service.createDeck("Animals");
        service.addCardToDeck(deck.id(), card.id());
        int savesBeforeDelete = storage.saveCount;

        service.deleteDeck(deck.id());

        assertEquals(savesBeforeDelete + 1, storage.saveCount);
        assertTrue(service.data().decks().isEmpty());
        assertTrue(service.data().findVocabularyCard(card.id()).isPresent());
    }

    @Test
    void failedEditSaveRollsBackCardTextMembershipAndProgress() throws StorageException {
        FakeStorage storage = new FakeStorage();
        KokoService service = new KokoService(storage, FIXED_CLOCK);
        VocabularyCard card = service.addVocabularyCard("こーひー", "kōhī", "coffee");
        ModeProgress progress = new ModeProgress(3, 4, 3,
                CREATION_DATE.minusDays(1), CREATION_DATE.plusDays(5));
        card.updateProgress(Mode.FLASHCARD, progress);
        Deck deck = service.createDeck("Cafe");
        service.addCardToDeck(deck.id(), card.id());
        storage.failSaves = true;

        assertThrows(StorageException.class, () ->
                service.editVocabularyCard(card.id(), "おちゃ", "ocha", "tea"));

        VocabularyCard restored = service.data().findVocabularyCard(card.id()).orElseThrow();
        assertEquals("こーひー", restored.hiragana());
        assertEquals("kōhī", restored.romaji());
        assertEquals("coffee", restored.englishMeaning());
        assertEquals(progress.mastery(),
                restored.progressFor(Mode.FLASHCARD).mastery());
        assertEquals(List.of(card.id()), service.data().findDeckById(deck.id()).orElseThrow().cardIds());
    }

    @Test
    void failedDeleteSaveRollsBackCardAndAllMemberships() throws StorageException {
        FakeStorage storage = new FakeStorage();
        KokoService service = new KokoService(storage, FIXED_CLOCK);
        VocabularyCard card = service.addVocabularyCard("ねこ", "neko", "cat");
        Deck first = service.createDeck("First");
        Deck second = service.createDeck("Second");
        service.addCardToDeck(first.id(), card.id());
        service.addCardToDeck(second.id(), card.id());
        storage.failSaves = true;

        assertThrows(StorageException.class, () -> service.deleteVocabularyCard(card.id()));

        assertTrue(service.data().findVocabularyCard(card.id()).isPresent());
        assertEquals(List.of(card.id()),
                service.data().findDeckById(first.id()).orElseThrow().cardIds());
        assertEquals(List.of(card.id()),
                service.data().findDeckById(second.id()).orElseThrow().cardIds());
    }

    /**
     * Small deterministic storage double used to count save calls.
     */
    private static final class FakeStorage implements Storage {

        private KokoData loadedData = new KokoData();
        private int saveCount;
        private boolean failSaves;

        @Override
        public KokoData load() {
            return loadedData;
        }

        @Override
        public void save(KokoData data) throws StorageException {
            if (failSaves) {
                throw new StorageException("forced save failure", null);
            }
            saveCount++;
            loadedData = data;
        }
    }
}
