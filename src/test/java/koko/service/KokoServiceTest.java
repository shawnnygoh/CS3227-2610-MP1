package koko.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.InstantSource;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

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

        assertEquals(7, storage.successfulSaveCount);
        assertEquals(7, storage.saveInvocations);
    }

    @Test
    void rejectedOperationsDoNotSave() throws StorageException {
        FakeStorage storage = new FakeStorage();
        KokoService service = new KokoService(storage, FIXED_CLOCK);
        VocabularyCard card = service.addVocabularyCard("ねこ", "neko", "cat");
        Deck deck = service.createDeck("Basics");
        service.addCardToDeck(deck.id(), card.id());
        int savesBeforeRejectedOperations = storage.successfulSaveCount;

        assertThrows(IllegalArgumentException.class, () ->
                service.addVocabularyCard("ねこ", "neko2", "CAT"));
        assertThrows(IllegalArgumentException.class, () -> service.createDeck(" basics "));
        assertThrows(IllegalArgumentException.class, () ->
                service.addCardToDeck(deck.id(), card.id()));
        assertThrows(IllegalArgumentException.class, () ->
                service.removeCardFromDeck(deck.id(), java.util.UUID.randomUUID()));

        assertEquals(savesBeforeRejectedOperations, storage.successfulSaveCount);
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
        assertEquals(1, storage.saveInvocations);
        assertEquals(0, storage.successfulSaveCount);
        assertTrue(service.data().vocabularyCards().isEmpty());
    }

    @Test
    void deletingDeckPreservesGlobalCardsAndSavesOnce() throws StorageException {
        FakeStorage storage = new FakeStorage();
        KokoService service = new KokoService(storage, FIXED_CLOCK);
        VocabularyCard card = service.addVocabularyCard("とり", "tori", "bird");
        Deck deck = service.createDeck("Animals");
        service.addCardToDeck(deck.id(), card.id());
        int savesBeforeDelete = storage.successfulSaveCount;

        service.deleteDeck(deck.id());

        assertEquals(savesBeforeDelete + 1, storage.successfulSaveCount);
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

    @Test
    void correctOutcomeUpdatesEveryFlashcardFieldAndUsesResultingMasteryInterval()
            throws StorageException {
        int[] expectedIntervals = {1, 3, 7, 14, 30, 30};

        for (int currentMastery = 0; currentMastery <= 5; currentMastery++) {
            FakeStorage storage = new FakeStorage();
            KokoService service = new KokoService(storage, FIXED_CLOCK);
            VocabularyCard card = service.addVocabularyCard("ねこ", "neko" + currentMastery,
                    "cat" + currentMastery);
            ModeProgress typing = new ModeProgress(2, 3, 1,
                    CREATION_DATE.minusDays(2), CREATION_DATE.plusDays(4));
            card.updateProgress(Mode.FLASHCARD, new ModeProgress(currentMastery, 7, 5,
                    CREATION_DATE.minusDays(1), CREATION_DATE.minusDays(2)));
            card.updateProgress(Mode.TYPING, typing);

            service.recordFlashcardOutcome(card.id(), ReviewOutcome.CORRECT);

            VocabularyCard updated = service.data().findVocabularyCard(card.id()).orElseThrow();
            ModeProgress flashcard = updated.progressFor(Mode.FLASHCARD);
            assertEquals(currentMastery == 5 ? 5 : currentMastery + 1, flashcard.mastery());
            assertEquals(8, flashcard.attempts());
            assertEquals(6, flashcard.correctAttempts());
            assertEquals(CREATION_DATE, flashcard.lastReviewedDate());
            assertEquals(CREATION_DATE.plusDays(expectedIntervals[currentMastery]),
                    flashcard.nextDueDate());
            assertProgressEquals(typing, updated.progressFor(Mode.TYPING));
            assertEquals(card.id(), updated.id());
            assertEquals(card.hiragana(), updated.hiragana());
            assertEquals(card.romaji(), updated.romaji());
            assertEquals(card.englishMeaning(), updated.englishMeaning());
            assertEquals(2, storage.saveInvocations);
            assertEquals(2, storage.successfulSaveCount);
        }
    }

    @Test
    void incorrectOutcomeLowersMasteryWithoutChangingCorrectAttempts() throws StorageException {
        FakeStorage storage = new FakeStorage();
        KokoService service = new KokoService(storage, FIXED_CLOCK);
        VocabularyCard card = service.addVocabularyCard("いぬ", "inu", "dog");
        ModeProgress original = new ModeProgress(4, 9, 6,
                CREATION_DATE.minusDays(3), CREATION_DATE.plusDays(20));
        card.updateProgress(Mode.FLASHCARD, original);

        service.recordFlashcardOutcome(card.id(), ReviewOutcome.INCORRECT);

        ModeProgress updated = service.data().findVocabularyCard(card.id()).orElseThrow()
                .progressFor(Mode.FLASHCARD);
        assertEquals(3, updated.mastery());
        assertEquals(10, updated.attempts());
        assertEquals(6, updated.correctAttempts());
        assertEquals(CREATION_DATE, updated.lastReviewedDate());
        assertEquals(CREATION_DATE.plusDays(1), updated.nextDueDate());
    }

    @Test
    void eachOutcomeUsesActualDateForDueAndNonDueCardsAcrossMidnight() throws StorageException {
        FakeStorage storage = new FakeStorage();
        AtomicReference<Instant> currentInstant = new AtomicReference<>(FIXED_CLOCK.instant());
        InstantSource timeSource = currentInstant::get;
        KokoService service = new KokoService(storage, timeSource.withZone(FIXED_CLOCK.getZone()));
        VocabularyCard overdue = service.addVocabularyCard("あめ", "ame", "rain");
        VocabularyCard nonDue = service.addVocabularyCard("ゆき", "yuki", "snow");
        overdue.updateProgress(Mode.FLASHCARD, new ModeProgress(1, 1, 1,
                CREATION_DATE.minusDays(5), CREATION_DATE.minusDays(1)));
        nonDue.updateProgress(Mode.FLASHCARD, new ModeProgress(1, 1, 1,
                CREATION_DATE.minusDays(5), CREATION_DATE.plusDays(20)));

        // Advance beyond creation, then cross midnight in Singapore without sleeping.
        currentInstant.set(Instant.parse("2026-08-31T15:59:59Z"));
        service.recordFlashcardOutcome(overdue.id(), ReviewOutcome.INCORRECT);
        currentInstant.set(Instant.parse("2026-08-31T16:00:00Z"));
        service.recordFlashcardOutcome(nonDue.id(), ReviewOutcome.CORRECT);

        ModeProgress overdueProgress = service.data().findVocabularyCard(overdue.id()).orElseThrow()
                .progressFor(Mode.FLASHCARD);
        ModeProgress nonDueProgress = service.data().findVocabularyCard(nonDue.id()).orElseThrow()
                .progressFor(Mode.FLASHCARD);
        assertEquals(LocalDate.of(2026, 8, 31), overdueProgress.lastReviewedDate());
        assertEquals(LocalDate.of(2026, 9, 1), overdueProgress.nextDueDate());
        assertEquals(LocalDate.of(2026, 9, 1), nonDueProgress.lastReviewedDate());
        assertEquals(LocalDate.of(2026, 9, 4), nonDueProgress.nextDueDate());
    }

    @Test
    void outcomeWorksForUnassignedAndSharedCards() throws StorageException {
        FakeStorage storage = new FakeStorage();
        KokoService service = new KokoService(storage, FIXED_CLOCK);
        VocabularyCard unassigned = service.addVocabularyCard("つき", "tsuki", "moon");
        VocabularyCard shared = service.addVocabularyCard("ほし", "hoshi", "star");
        VocabularyCard other = service.addVocabularyCard("たいよう", "taiyou", "sun");
        Deck first = service.createDeck("First");
        Deck second = service.createDeck("Second");
        service.addCardToDeck(first.id(), other.id());
        service.addCardToDeck(first.id(), shared.id());
        service.addCardToDeck(second.id(), shared.id());
        service.addCardToDeck(second.id(), other.id());

        service.recordFlashcardOutcome(unassigned.id(), ReviewOutcome.CORRECT);
        service.recordFlashcardOutcome(shared.id(), ReviewOutcome.INCORRECT);

        assertEquals(1, service.data().findVocabularyCard(unassigned.id()).orElseThrow()
                .progressFor(Mode.FLASHCARD).attempts());
        assertEquals(1, service.data().findVocabularyCard(shared.id()).orElseThrow()
                .progressFor(Mode.FLASHCARD).attempts());
        assertEquals(0, service.data().findVocabularyCard(other.id()).orElseThrow()
                .progressFor(Mode.FLASHCARD).attempts());
        assertEquals(List.of(other.id(), shared.id()), service.data().findDeckById(first.id())
                .orElseThrow().cardIds());
        assertEquals(List.of(shared.id(), other.id()), service.data().findDeckById(second.id())
                .orElseThrow().cardIds());
    }

    @Test
    void invalidOutcomeInputsDoNotSaveOrChangeProgress() throws StorageException {
        FakeStorage storage = new FakeStorage();
        KokoService service = new KokoService(storage, FIXED_CLOCK);
        VocabularyCard card = service.addVocabularyCard("ねこ", "neko", "cat");
        KokoData originalData = service.data();
        ModeProgress originalProgress = card.progressFor(Mode.FLASHCARD);
        int savesBefore = storage.saveInvocations;

        assertThrows(NullPointerException.class, () ->
                service.recordFlashcardOutcome(null, ReviewOutcome.CORRECT));
        assertThrows(IllegalArgumentException.class, () ->
                service.recordFlashcardOutcome(UUID.randomUUID(), ReviewOutcome.CORRECT));
        assertThrows(NullPointerException.class, () ->
                service.recordFlashcardOutcome(card.id(), null));
        assertThrows(IllegalArgumentException.class, () ->
                service.recordFlashcardOutcome(card.id(), ReviewOutcome.SKIPPED));

        assertSame(originalData, service.data());
        assertSame(originalProgress, card.progressFor(Mode.FLASHCARD));
        assertEquals(savesBefore, storage.saveInvocations);
    }

    @Test
    void schedulerFailureLeavesServiceDataAndExposedReferencesUnchanged() throws StorageException {
        FakeStorage storage = new FakeStorage();
        KokoService service = new KokoService(storage, FIXED_CLOCK);
        VocabularyCard card = service.addVocabularyCard("くも", "kumo", "cloud");
        ModeProgress original = new ModeProgress(0, Integer.MAX_VALUE, 0,
                CREATION_DATE.minusDays(1), CREATION_DATE);
        card.updateProgress(Mode.FLASHCARD, original);
        KokoData originalData = service.data();

        assertThrows(IllegalArgumentException.class, () ->
                service.recordFlashcardOutcome(card.id(), ReviewOutcome.INCORRECT));

        assertSame(originalData, service.data());
        assertSame(card, service.data().findVocabularyCard(card.id()).orElseThrow());
        assertSame(original, card.progressFor(Mode.FLASHCARD));
        assertEquals(Integer.MAX_VALUE, original.attempts());
        assertEquals(1, storage.saveInvocations);
    }

    @Test
    void failedOutcomeSaveLeavesCurrentDataAndRetainedReferencesUnchanged()
            throws StorageException {
        FakeStorage storage = new FakeStorage();
        KokoService service = new KokoService(storage, FIXED_CLOCK);
        VocabularyCard card = service.addVocabularyCard("おちゃ", "ocha", "tea");
        Deck deck = service.createDeck("Drinks");
        service.addCardToDeck(deck.id(), card.id());
        KokoData originalData = service.data();
        VocabularyCard originalCard = card;
        ModeProgress originalFlashcard = card.progressFor(Mode.FLASHCARD);
        ModeProgress originalTyping = card.progressFor(Mode.TYPING);
        storage.failSaves = true;

        assertThrows(StorageException.class, () ->
                service.recordFlashcardOutcome(card.id(), ReviewOutcome.CORRECT));

        assertSame(originalData, service.data());
        assertSame(originalCard, service.data().findVocabularyCard(card.id()).orElseThrow());
        assertSame(originalFlashcard, originalCard.progressFor(Mode.FLASHCARD));
        assertSame(originalTyping, originalCard.progressFor(Mode.TYPING));
        assertEquals(List.of(card.id()), deck.cardIds());
        assertEquals(4, storage.saveInvocations);
        assertEquals(3, storage.successfulSaveCount);
    }

    @Test
    void failedOutcomeCanBeRetriedFromUnchangedProgress() throws StorageException {
        FakeStorage storage = new FakeStorage();
        KokoService service = new KokoService(storage, FIXED_CLOCK);
        VocabularyCard card = service.addVocabularyCard("はな", "hana", "flower");
        ModeProgress original = card.progressFor(Mode.FLASHCARD);
        storage.failNextSave = true;

        assertThrows(StorageException.class, () ->
                service.recordFlashcardOutcome(card.id(), ReviewOutcome.CORRECT));
        assertSame(original, card.progressFor(Mode.FLASHCARD));

        service.recordFlashcardOutcome(card.id(), ReviewOutcome.CORRECT);

        ModeProgress updated = service.data().findVocabularyCard(card.id()).orElseThrow()
                .progressFor(Mode.FLASHCARD);
        assertEquals(1, updated.mastery());
        assertEquals(1, updated.attempts());
        assertEquals(1, updated.correctAttempts());
        assertEquals(3, storage.saveInvocations);
        assertEquals(2, storage.successfulSaveCount);
    }

    /**
     * Small deterministic storage double that stores detached snapshots and
     * counts attempted and successful saves separately.
     */
    private static final class FakeStorage implements Storage {

        private KokoData loadedData = new KokoData();
        private int saveInvocations;
        private int successfulSaveCount;
        private boolean failSaves;
        private boolean failNextSave;

        @Override
        public KokoData load() {
            return loadedData;
        }

        @Override
        public void save(KokoData data) throws StorageException {
            saveInvocations++;
            if (failSaves || failNextSave) {
                failNextSave = false;
                throw new StorageException("forced save failure", null);
            }
            successfulSaveCount++;
            loadedData = copyOf(data);
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

    private static void assertProgressEquals(ModeProgress expected, ModeProgress actual) {
        assertEquals(expected.mastery(), actual.mastery());
        assertEquals(expected.attempts(), actual.attempts());
        assertEquals(expected.correctAttempts(), actual.correctAttempts());
        assertEquals(expected.lastReviewedDate(), actual.lastReviewedDate());
        assertEquals(expected.nextDueDate(), actual.nextDueDate());
    }
}
