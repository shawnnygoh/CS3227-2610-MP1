package koko.service;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.InstantSource;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import koko.model.Deck;
import koko.model.KokoData;
import koko.model.Mode;
import koko.model.ModeProgress;
import koko.model.VocabularyCard;
import koko.storage.JsonStorage;
import koko.storage.Storage;
import koko.storage.StorageException;
import koko.testutil.KokoDataSnapshots;

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
    void createdCardsAndDecksAreThePublishedInstances() throws StorageException {
        KokoService service = new KokoService(new FakeStorage(), FIXED_CLOCK);

        // Each result is the published instance only until the next mutation copies
        // the aggregate, so every check has to follow its own create.
        VocabularyCard first = service.addVocabularyCard("ねこ", "neko", "cat");
        assertSame(first, currentCard(service, first.id()));

        VocabularyCard second = service.addVocabularyCard("いぬ", "inu", "dog");
        assertSame(second, currentCard(service, second.id()));

        Deck firstDeck = service.createDeck("First");
        assertSame(firstDeck, currentDeck(service, firstDeck.id()));

        Deck secondDeck = service.createDeck("Second");
        assertSame(secondDeck, currentDeck(service, secondDeck.id()));
    }

    @Test
    void membershipKeepsInsertionOrderWithinEachDeck() throws StorageException {
        KokoService service = new KokoService(new FakeStorage(), FIXED_CLOCK);
        VocabularyCard first = service.addVocabularyCard("ねこ", "neko", "cat");
        VocabularyCard second = service.addVocabularyCard("いぬ", "inu", "dog");
        Deck firstDeck = service.createDeck("First");
        Deck secondDeck = service.createDeck("Second");

        service.addCardToDeck(firstDeck.id(), first.id());
        service.addCardToDeck(firstDeck.id(), second.id());
        service.addCardToDeck(secondDeck.id(), first.id());

        assertEquals(List.of(first.id(), second.id()),
                currentDeck(service, firstDeck.id()).cardIds());
        assertEquals(List.of(first.id()), currentDeck(service, secondDeck.id()).cardIds());
    }

    @Test
    void successfulMutationReplacesTheAffectedDomainObject() throws StorageException {
        KokoService service = new KokoService(new FakeStorage(), FIXED_CLOCK);
        VocabularyCard card = service.addVocabularyCard("ねこ", "neko", "cat");
        VocabularyCard beforeEdit = currentCard(service, card.id());

        service.editVocabularyCard(card.id(), "ねこ", "neko-2", "animal");

        assertNotSame(beforeEdit, currentCard(service, card.id()));
        assertEquals(card.id(), currentCard(service, card.id()).id());
    }

    @Test
    void everySavedCandidateIsDetachedAndThePublishedStateIsTheSavedOne()
            throws StorageException {
        FakeStorage storage = new FakeStorage();
        KokoService service = new KokoService(storage, FIXED_CLOCK);
        storage.observedService = service;

        // Every mutation runs under the observer, which is the only check on ordering
        // during save. An operation left out here could publish before saving and
        // restore on failure while still passing every after-the-fact assertion.
        VocabularyCard card = service.addVocabularyCard("ねこ", "neko", "cat");
        Deck deck = service.createDeck("First");
        service.addCardToDeck(deck.id(), card.id());
        service.editVocabularyCard(card.id(), "ねこ", "neko-2", "animal");
        service.renameDeck(deck.id(), "Renamed");
        service.removeCardFromDeck(deck.id(), card.id());
        service.deleteDeck(deck.id());
        service.deleteVocabularyCard(card.id());

        assertEquals(8, storage.saveInvocations);
        assertTrue(storage.allSavedCandidatesWereDetached);
        assertSame(storage.lastSavedData, service.data());
        assertTrue(service.data().vocabularyCards().isEmpty());
        assertTrue(service.data().decks().isEmpty());
    }

    @ParameterizedTest(name = "rejects {0}")
    @MethodSource("rejectedManagementOperations")
    void rejectedManagementOperationDoesNotSaveOrReplaceCurrentState(String name,
            Class<? extends Throwable> expected, RejectedOperation operation)
            throws StorageException {
        FakeStorage storage = new FakeStorage();
        KokoService service = new KokoService(storage, FIXED_CLOCK);
        VocabularyCard card = service.addVocabularyCard("ねこ", "neko", "cat");
        Deck deck = service.createDeck("Animals");
        Deck otherDeck = service.createDeck("Other");
        service.addCardToDeck(deck.id(), card.id());
        Fixture fixture = new Fixture(service, card, deck);
        KokoData originalData = service.data();
        KokoData originalValues = KokoDataSnapshots.copyOf(originalData);
        VocabularyCard originalCard = currentCard(service, card.id());
        Deck originalDeck = currentDeck(service, deck.id());
        Deck originalOtherDeck = currentDeck(service, otherDeck.id());
        int savesBefore = storage.saveInvocations;

        assertThrows(expected, () -> operation.run(fixture));

        assertEquals(savesBefore, storage.saveInvocations);
        assertSame(originalData, service.data());
        assertDataEquals(originalValues, service.data());
        assertSame(originalCard, currentCard(service, card.id()));
        assertSame(originalDeck, currentDeck(service, deck.id()));
        assertSame(originalOtherDeck, currentDeck(service, otherDeck.id()));
        assertEquals(List.of(card.id()), originalDeck.cardIds());
        assertEquals("ねこ", originalCard.hiragana());
        assertEquals("neko", originalCard.romaji());
        assertEquals("cat", originalCard.englishMeaning());
    }

    private static Stream<Arguments> rejectedManagementOperations() {
        return Stream.of(
                rejection("duplicate vocabulary identity", IllegalArgumentException.class,
                        f -> f.service().addVocabularyCard("ねこ", "other", "CAT")),
                rejection("blank Hiragana on edit", IllegalArgumentException.class,
                        f -> f.service().editVocabularyCard(f.card().id(), " ", "neko", "cat")),
                rejection("edit of an unknown card", IllegalArgumentException.class,
                        f -> f.service().editVocabularyCard(UUID.randomUUID(), "ねこ", "neko", "cat")),
                rejection("delete of an unknown card", IllegalArgumentException.class,
                        f -> f.service().deleteVocabularyCard(UUID.randomUUID())),
                rejection("a deck name differing only by case and spacing",
                        IllegalArgumentException.class,
                        f -> f.service().createDeck(" animals ")),
                rejection("a blank deck rename", IllegalArgumentException.class,
                        f -> f.service().renameDeck(f.deck().id(), " ")),
                rejection("a rename onto an existing deck name", IllegalArgumentException.class,
                        f -> f.service().renameDeck(f.deck().id(), " other ")),
                rejection("a rename of an unknown deck", IllegalArgumentException.class,
                        f -> f.service().renameDeck(UUID.randomUUID(), "New")),
                rejection("delete of an unknown deck", IllegalArgumentException.class,
                        f -> f.service().deleteDeck(UUID.randomUUID())),
                rejection("duplicate deck membership", IllegalArgumentException.class,
                        f -> f.service().addCardToDeck(f.deck().id(), f.card().id())),
                rejection("an add to an unknown deck", IllegalArgumentException.class,
                        f -> f.service().addCardToDeck(UUID.randomUUID(), f.card().id())),
                rejection("removal of an absent membership", IllegalArgumentException.class,
                        f -> f.service().removeCardFromDeck(f.deck().id(), UUID.randomUUID())),
                rejection("removal from an unknown deck", IllegalArgumentException.class,
                        f -> f.service().removeCardFromDeck(UUID.randomUUID(), f.card().id())),
                rejection("a null deck name", NullPointerException.class,
                        f -> f.service().createDeck(null)),
                rejection("null Hiragana on edit", NullPointerException.class,
                        f -> f.service().editVocabularyCard(f.card().id(), null, "neko", "cat")));
    }

    private static Arguments rejection(String name, Class<? extends Throwable> expected,
            RejectedOperation operation) {
        return Arguments.of(name, expected, operation);
    }

    @Test
    void invalidUnicodeDeckNamesAreRejectedBeforePublication() throws StorageException {
        FakeStorage storage = new FakeStorage();
        KokoService service = new KokoService(storage, FIXED_CLOCK);
        VocabularyCard card = service.addVocabularyCard("ねこ", "neko", "cat");
        Deck deck = service.createDeck("Existing");
        service.addCardToDeck(deck.id(), card.id());
        KokoData originalData = service.data();
        Deck originalDeck = currentDeck(service, deck.id());
        VocabularyCard originalCard = currentCard(service, card.id());
        int savesBefore = storage.saveInvocations;

        for (char surrogate : new char[] {'\uD800', '\uDC00'}) {
            String invalidName = "Deck " + surrogate;
            assertThrows(IllegalArgumentException.class, () ->
                    service.createDeck(invalidName));
            assertThrows(IllegalArgumentException.class, () ->
                    service.renameDeck(deck.id(), invalidName));
        }

        assertEquals(savesBefore, storage.saveInvocations);
        assertSame(originalData, service.data());
        assertSame(originalDeck, currentDeck(service, deck.id()));
        assertSame(originalCard, currentCard(service, card.id()));
        assertEquals("Existing", originalDeck.name());
        assertEquals(List.of(card.id()), originalDeck.cardIds());
    }

    @Test
    void failedDeckCreationSaveLeavesStateIntactAndCanBeRetried() throws StorageException {
        FakeStorage storage = new FakeStorage();
        KokoService service = new KokoService(storage, FIXED_CLOCK);
        VocabularyCard card = service.addVocabularyCard("ねこ", "neko", "cat");
        Deck deck = service.createDeck("Animals");
        KokoData before = service.data();

        assertFailedSave(storage, service, () -> service.createDeck("Retry"), before,
                List.of(currentCard(service, card.id())),
                List.of(currentDeck(service, deck.id())));

        Deck retryDeck = service.createDeck("Retry");
        assertSame(retryDeck, currentDeck(service, retryDeck.id()));
    }

    @Test
    void failedDeckRenameSaveLeavesStateIntactAndCanBeRetried() throws StorageException {
        FakeStorage storage = new FakeStorage();
        KokoService service = new KokoService(storage, FIXED_CLOCK);
        VocabularyCard card = service.addVocabularyCard("ねこ", "neko", "cat");
        Deck deck = service.createDeck("Animals");
        KokoData before = service.data();

        assertFailedSave(storage, service, () -> service.renameDeck(deck.id(), "Renamed"), before,
                List.of(currentCard(service, card.id())),
                List.of(currentDeck(service, deck.id())));

        service.renameDeck(deck.id(), "Renamed");
        assertEquals("Renamed", currentDeck(service, deck.id()).name());
    }

    @Test
    void failedMembershipAddSaveLeavesStateIntactAndCanBeRetried() throws StorageException {
        FakeStorage storage = new FakeStorage();
        KokoService service = new KokoService(storage, FIXED_CLOCK);
        VocabularyCard card = service.addVocabularyCard("ねこ", "neko", "cat");
        Deck deck = service.createDeck("Animals");
        KokoData before = service.data();

        assertFailedSave(storage, service, () -> service.addCardToDeck(deck.id(), card.id()),
                before, List.of(currentCard(service, card.id())),
                List.of(currentDeck(service, deck.id())));

        service.addCardToDeck(deck.id(), card.id());
        assertEquals(List.of(card.id()), currentDeck(service, deck.id()).cardIds());
    }

    @Test
    void failedMembershipRemovalSaveLeavesStateIntactAndCanBeRetried() throws StorageException {
        FakeStorage storage = new FakeStorage();
        KokoService service = new KokoService(storage, FIXED_CLOCK);
        VocabularyCard card = service.addVocabularyCard("ねこ", "neko", "cat");
        Deck deck = service.createDeck("Animals");
        service.addCardToDeck(deck.id(), card.id());
        KokoData before = service.data();

        assertFailedSave(storage, service, () -> service.removeCardFromDeck(deck.id(), card.id()),
                before, List.of(currentCard(service, card.id())),
                List.of(currentDeck(service, deck.id())));

        service.removeCardFromDeck(deck.id(), card.id());
        assertTrue(currentDeck(service, deck.id()).cardIds().isEmpty());
    }

    @Test
    void failedDeckDeletionSaveLeavesStateIntactAndCanBeRetried() throws StorageException {
        FakeStorage storage = new FakeStorage();
        KokoService service = new KokoService(storage, FIXED_CLOCK);
        VocabularyCard card = service.addVocabularyCard("ねこ", "neko", "cat");
        Deck deck = service.createDeck("Animals");
        service.addCardToDeck(deck.id(), card.id());
        KokoData before = service.data();

        assertFailedSave(storage, service, () -> service.deleteDeck(deck.id()), before,
                List.of(currentCard(service, card.id())),
                List.of(currentDeck(service, deck.id())));

        service.deleteDeck(deck.id());
        assertTrue(service.data().findDeckById(deck.id()).isEmpty());
        assertTrue(service.data().findVocabularyCard(card.id()).isPresent());
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
                service.removeCardFromDeck(deck.id(), UUID.randomUUID()));

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
        ModeProgress flashcard = new ModeProgress(3, CREATION_DATE.plusDays(5));
        ModeProgress typing = new ModeProgress(2, CREATION_DATE.plusDays(3));
        card.updateProgress(Mode.FLASHCARD, flashcard);
        card.updateProgress(Mode.TYPING, typing);

        service.editVocabularyCard(card.id(), "ねこ", "neko-2", "animal");

        VocabularyCard edited = service.data().findVocabularyCard(card.id()).orElseThrow();
        assertEquals(card.id(), edited.id());
        assertEquals("neko-2", edited.romaji());
        assertEquals("animal", edited.englishMeaning());
        assertProgressEquals(flashcard, edited.progressFor(Mode.FLASHCARD));
        assertProgressEquals(typing, edited.progressFor(Mode.TYPING));
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
        assertEquals(List.of(card.id()), service.data().findDeckById(first.id())
                .orElseThrow().cardIds());
        assertEquals(List.of(card.id()), service.data().findDeckById(second.id())
                .orElseThrow().cardIds());

        service.removeCardFromDeck(first.id(), card.id());

        assertTrue(service.data().findDeckById(first.id()).orElseThrow().cardIds().isEmpty());
        assertEquals(List.of(card.id()), service.data().findDeckById(second.id())
                .orElseThrow().cardIds());
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
        assertTrue(service.data().findDeckById(first.id()).orElseThrow().cardIds().isEmpty());
        assertTrue(service.data().findDeckById(second.id()).orElseThrow().cardIds().isEmpty());
    }

    @Test
    void storageFailureIsSurfacedAndDoesNotCountAsACompletedSave() throws StorageException {
        FakeStorage storage = new FakeStorage();
        storage.failSaves = true;
        KokoService service = new KokoService(storage, FIXED_CLOCK);
        KokoData originalData = service.data();
        List<VocabularyCard> originalCards = originalData.vocabularyCards();

        assertThrows(StorageException.class, () ->
                service.addVocabularyCard("つき", "tsuki", "moon"));
        assertEquals(1, storage.saveInvocations);
        assertEquals(0, storage.successfulSaveCount);
        assertSame(originalData, service.data());
        assertTrue(originalCards.isEmpty());
        assertTrue(service.data().vocabularyCards().isEmpty());

        storage.failSaves = false;
        VocabularyCard retry = service.addVocabularyCard("つき", "tsuki", "moon");
        assertSame(retry, currentCard(service, retry.id()));
        assertEquals(2, storage.saveInvocations);
        assertEquals(1, storage.successfulSaveCount);
    }

    @Test
    void failedManagementSavePreservesFileAndRetainedViewsUntilRetry(@TempDir Path directory)
            throws IOException, StorageException {
        Path path = directory.resolve("koko-data.json");
        JsonStorage jsonStorage = new JsonStorage(path);
        FailOnceStorage storage = new FailOnceStorage(jsonStorage);
        KokoService service = new KokoService(storage, FIXED_CLOCK);
        VocabularyCard first = service.addVocabularyCard("ねこ", "neko", "cat");
        VocabularyCard second = service.addVocabularyCard("いぬ", "inu", "dog");
        service.recordFlashcardOutcome(first.id(), ReviewOutcome.CORRECT);
        service.recordTypingOutcome(first.id(), ReviewOutcome.INCORRECT);
        Deck deck = service.createDeck("Animals");
        service.addCardToDeck(deck.id(), first.id());
        service.addCardToDeck(deck.id(), second.id());

        KokoData originalData = service.data();
        KokoData originalValues = KokoDataSnapshots.copyOf(originalData);
        List<VocabularyCard> originalCards = originalData.vocabularyCards();
        List<Deck> originalDecks = originalData.decks();
        VocabularyCard originalCard = currentCard(service, first.id());
        Deck originalDeck = currentDeck(service, deck.id());
        List<UUID> originalMemberships = originalDeck.cardIds();
        byte[] originalBytes = Files.readAllBytes(path);
        int savesBefore = storage.saveInvocations();
        storage.failNextSave();

        assertThrows(StorageException.class, () -> service.deleteVocabularyCard(first.id()));

        assertSame(originalData, service.data());
        assertSame(originalCard, currentCard(service, first.id()));
        assertSame(originalDeck, currentDeck(service, deck.id()));
        assertDataEquals(originalValues, service.data());
        assertEquals(List.of(first.id(), second.id()), originalCards.stream().map(VocabularyCard::id).toList());
        assertSame(originalDeck, originalDecks.getFirst());
        assertEquals(List.of(first.id(), second.id()), originalMemberships);
        assertArrayEquals(originalBytes, Files.readAllBytes(path));
        assertDataEquals(originalValues, jsonStorage.load());
        assertEquals(savesBefore + 1, storage.saveInvocations());

        service.deleteVocabularyCard(first.id());

        assertNotSame(originalData, service.data());
        assertEquals(List.of(second.id()), service.data().vocabularyCards().stream().map(VocabularyCard::id).toList());
        assertEquals(List.of(second.id()), currentDeck(service, deck.id()).cardIds());
        assertDataEquals(service.data(), jsonStorage.load());
        assertEquals(savesBefore + 2, storage.saveInvocations());
        assertDataEquals(originalValues, originalData);
        assertEquals(List.of(first.id(), second.id()), originalMemberships);
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
    void failedEditSaveLeavesCardTextMembershipAndProgressUnchanged()
            throws StorageException {
        FakeStorage storage = new FakeStorage();
        KokoService service = new KokoService(storage, FIXED_CLOCK);
        VocabularyCard card = service.addVocabularyCard("こーひー", "kōhī", "coffee");
        ModeProgress progress = new ModeProgress(3, CREATION_DATE.plusDays(5));
        card.updateProgress(Mode.FLASHCARD, progress);
        Deck deck = service.createDeck("Cafe");
        service.addCardToDeck(deck.id(), card.id());
        KokoData originalData = service.data();
        VocabularyCard originalCard = service.data().findVocabularyCard(card.id()).orElseThrow();
        Deck originalDeck = service.data().findDeckById(deck.id()).orElseThrow();
        ModeProgress originalTyping = originalCard.progressFor(Mode.TYPING);
        storage.failSaves = true;

        assertThrows(StorageException.class, () ->
                service.editVocabularyCard(card.id(), "おちゃ", "ocha", "tea"));

        assertSame(originalData, service.data());
        assertSame(originalCard, service.data().findVocabularyCard(card.id()).orElseThrow());
        assertSame(originalDeck, service.data().findDeckById(deck.id()).orElseThrow());
        assertEquals("こーひー", originalCard.hiragana());
        assertEquals("kōhī", originalCard.romaji());
        assertEquals("coffee", originalCard.englishMeaning());
        assertProgressEquals(progress, originalCard.progressFor(Mode.FLASHCARD));
        assertProgressEquals(originalTyping, originalCard.progressFor(Mode.TYPING));
        assertEquals(List.of(card.id()), originalDeck.cardIds());

        storage.failSaves = false;
        service.editVocabularyCard(card.id(), "おちゃ", "ocha", "tea");
        VocabularyCard edited = currentCard(service, card.id());
        assertEquals("おちゃ", edited.hiragana());
        assertEquals("ocha", edited.romaji());
        assertEquals("tea", edited.englishMeaning());
    }

    @Test
    void failedDeleteSaveLeavesCardAndAllMembershipsUnchanged() throws StorageException {
        FakeStorage storage = new FakeStorage();
        KokoService service = new KokoService(storage, FIXED_CLOCK);
        VocabularyCard card = service.addVocabularyCard("ねこ", "neko", "cat");
        Deck first = service.createDeck("First");
        Deck second = service.createDeck("Second");
        service.addCardToDeck(first.id(), card.id());
        service.addCardToDeck(second.id(), card.id());
        KokoData originalData = service.data();
        VocabularyCard originalCard = service.data().findVocabularyCard(card.id()).orElseThrow();
        Deck originalFirst = service.data().findDeckById(first.id()).orElseThrow();
        Deck originalSecond = service.data().findDeckById(second.id()).orElseThrow();
        storage.failSaves = true;

        assertThrows(StorageException.class, () -> service.deleteVocabularyCard(card.id()));

        assertSame(originalData, service.data());
        assertSame(originalCard, service.data().findVocabularyCard(card.id()).orElseThrow());
        assertSame(originalFirst, service.data().findDeckById(first.id()).orElseThrow());
        assertSame(originalSecond, service.data().findDeckById(second.id()).orElseThrow());
        assertEquals(List.of(card.id()), originalFirst.cardIds());
        assertEquals(List.of(card.id()), originalSecond.cardIds());

        storage.failSaves = false;
        service.deleteVocabularyCard(card.id());
        assertTrue(service.data().findVocabularyCard(card.id()).isEmpty());
        assertTrue(currentDeck(service, first.id()).cardIds().isEmpty());
        assertTrue(currentDeck(service, second.id()).cardIds().isEmpty());
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
            ModeProgress typing = new ModeProgress(2, CREATION_DATE.plusDays(4));
            card.updateProgress(Mode.FLASHCARD,
                    new ModeProgress(currentMastery, CREATION_DATE.minusDays(2)));
            card.updateProgress(Mode.TYPING, typing);

            service.recordFlashcardOutcome(card.id(), ReviewOutcome.CORRECT);

            VocabularyCard updated = service.data().findVocabularyCard(card.id()).orElseThrow();
            ModeProgress flashcard = updated.progressFor(Mode.FLASHCARD);
            assertEquals(currentMastery == 5 ? 5 : currentMastery + 1, flashcard.mastery());
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
    void incorrectOutcomeLowersMasteryAndIsDueTheFollowingDay() throws StorageException {
        FakeStorage storage = new FakeStorage();
        KokoService service = new KokoService(storage, FIXED_CLOCK);
        VocabularyCard card = service.addVocabularyCard("いぬ", "inu", "dog");
        ModeProgress original = new ModeProgress(4, CREATION_DATE.plusDays(20));
        card.updateProgress(Mode.FLASHCARD, original);

        service.recordFlashcardOutcome(card.id(), ReviewOutcome.INCORRECT);

        ModeProgress updated = service.data().findVocabularyCard(card.id()).orElseThrow()
                .progressFor(Mode.FLASHCARD);
        assertEquals(3, updated.mastery());
        assertEquals(CREATION_DATE.plusDays(1), updated.nextDueDate());
    }

    @Test
    void typingOutcomeUpdatesOnlyTypingProgressAndAcceptsSkipped() throws StorageException {
        FakeStorage storage = new FakeStorage();
        KokoService service = new KokoService(storage, FIXED_CLOCK);
        VocabularyCard card = service.addVocabularyCard("ねこ", "neko", "cat");
        ModeProgress flashcard = new ModeProgress(4, CREATION_DATE.plusDays(5));
        ModeProgress typing = new ModeProgress(2, CREATION_DATE.plusDays(4));
        card.updateProgress(Mode.FLASHCARD, flashcard);
        card.updateProgress(Mode.TYPING, typing);

        service.recordTypingOutcome(card.id(), ReviewOutcome.SKIPPED);

        VocabularyCard updated = service.data().findVocabularyCard(card.id()).orElseThrow();
        assertProgressEquals(flashcard, updated.progressFor(Mode.FLASHCARD));
        assertProgressEquals(new ModeProgress(2, CREATION_DATE.plusDays(1)),
                updated.progressFor(Mode.TYPING));
        assertEquals(card.id(), updated.id());
        assertEquals("ねこ", updated.hiragana());
        assertEquals("neko", updated.romaji());
        assertEquals("cat", updated.englishMeaning());
        assertEquals(2, storage.saveInvocations);
    }

    @Test
    void invalidTypingOutcomeInputsDoNotSaveOrChangeProgress() throws StorageException {
        FakeStorage storage = new FakeStorage();
        KokoService service = new KokoService(storage, FIXED_CLOCK);
        VocabularyCard card = service.addVocabularyCard("ねこ", "neko", "cat");
        KokoData originalData = service.data();
        ModeProgress originalProgress = card.progressFor(Mode.TYPING);
        int savesBefore = storage.saveInvocations;

        assertThrows(NullPointerException.class, () ->
                service.recordTypingOutcome(null, ReviewOutcome.CORRECT));
        assertThrows(IllegalArgumentException.class, () ->
                service.recordTypingOutcome(UUID.randomUUID(), ReviewOutcome.CORRECT));
        assertThrows(NullPointerException.class, () ->
                service.recordTypingOutcome(card.id(), null));

        assertSame(originalData, service.data());
        assertSame(originalProgress, card.progressFor(Mode.TYPING));
        assertEquals(savesBefore, storage.saveInvocations);
    }

    @Test
    void eachOutcomeUsesActualDateForDueAndNonDueCardsAcrossMidnight() throws StorageException {
        FakeStorage storage = new FakeStorage();
        AtomicReference<Instant> currentInstant = new AtomicReference<>(FIXED_CLOCK.instant());
        InstantSource timeSource = currentInstant::get;
        KokoService service = new KokoService(storage, timeSource.withZone(FIXED_CLOCK.getZone()));
        VocabularyCard overdue = service.addVocabularyCard("あめ", "ame", "rain");
        VocabularyCard nonDue = service.addVocabularyCard("ゆき", "yuki", "snow");
        service.data().findVocabularyCard(overdue.id()).orElseThrow().updateProgress(
                Mode.FLASHCARD, new ModeProgress(1, CREATION_DATE.minusDays(1)));
        service.data().findVocabularyCard(nonDue.id()).orElseThrow().updateProgress(
                Mode.FLASHCARD, new ModeProgress(1, CREATION_DATE.plusDays(20)));

        // Advance beyond creation, then cross midnight in Singapore without sleeping.
        currentInstant.set(Instant.parse("2026-08-31T15:59:59Z"));
        service.recordFlashcardOutcome(overdue.id(), ReviewOutcome.INCORRECT);
        currentInstant.set(Instant.parse("2026-08-31T16:00:00Z"));
        service.recordFlashcardOutcome(nonDue.id(), ReviewOutcome.CORRECT);

        ModeProgress overdueProgress = service.data().findVocabularyCard(overdue.id()).orElseThrow()
                .progressFor(Mode.FLASHCARD);
        ModeProgress nonDueProgress = service.data().findVocabularyCard(nonDue.id()).orElseThrow()
                .progressFor(Mode.FLASHCARD);
        assertEquals(LocalDate.of(2026, 9, 1), overdueProgress.nextDueDate());
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

        assertProgressEquals(new ModeProgress(1, CREATION_DATE.plusDays(1)),
                currentCard(service, unassigned.id()).progressFor(Mode.FLASHCARD));
        assertProgressEquals(new ModeProgress(0, CREATION_DATE.plusDays(1)),
                currentCard(service, shared.id()).progressFor(Mode.FLASHCARD));
        assertProgressEquals(ModeProgress.forCreationDate(CREATION_DATE),
                currentCard(service, other.id()).progressFor(Mode.FLASHCARD));
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
        Clock endOfSupportedDates = Clock.fixed(
                LocalDate.MAX.atStartOfDay(ZoneOffset.UTC).toInstant(), ZoneOffset.UTC);
        KokoService service = new KokoService(storage, endOfSupportedDates);
        VocabularyCard card = service.addVocabularyCard("くも", "kumo", "cloud");
        ModeProgress original = new ModeProgress(0, CREATION_DATE);
        card.updateProgress(Mode.FLASHCARD, original);
        KokoData originalData = service.data();

        assertThrows(DateTimeException.class, () ->
                service.recordFlashcardOutcome(card.id(), ReviewOutcome.INCORRECT));

        assertSame(originalData, service.data());
        assertSame(card, service.data().findVocabularyCard(card.id()).orElseThrow());
        assertSame(original, card.progressFor(Mode.FLASHCARD));
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
        VocabularyCard originalCard = service.data().findVocabularyCard(card.id()).orElseThrow();
        ModeProgress originalFlashcard = originalCard.progressFor(Mode.FLASHCARD);
        ModeProgress originalTyping = originalCard.progressFor(Mode.TYPING);
        Deck originalDeck = service.data().findDeckById(deck.id()).orElseThrow();
        storage.failSaves = true;

        assertThrows(StorageException.class, () ->
                service.recordFlashcardOutcome(card.id(), ReviewOutcome.CORRECT));

        assertSame(originalData, service.data());
        assertSame(originalCard, service.data().findVocabularyCard(card.id()).orElseThrow());
        assertSame(originalFlashcard, originalCard.progressFor(Mode.FLASHCARD));
        assertSame(originalTyping, originalCard.progressFor(Mode.TYPING));
        assertSame(originalDeck, service.data().findDeckById(deck.id()).orElseThrow());
        assertEquals(List.of(card.id()), originalDeck.cardIds());
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
        assertProgressEquals(new ModeProgress(1, CREATION_DATE.plusDays(1)), updated);
        assertEquals(3, storage.saveInvocations);
        assertEquals(2, storage.successfulSaveCount);
    }

    @Test
    void suppliedSchedulerReplacesTheDefaultPolicyAndReceivesTheReviewedProgress()
            throws StorageException {
        FakeStorage storage = new FakeStorage();
        // No mastery policy produces a 99-day interval, so this result can only
        // come from the injected scheduler.
        RecordingScheduler scheduler = new RecordingScheduler(
                new ModeProgress(5, CREATION_DATE.plusDays(99)));
        KokoService service = new KokoService(storage, FIXED_CLOCK, scheduler);
        VocabularyCard card = service.addVocabularyCard("ねこ", "neko", "cat");
        ModeProgress beforeReview = card.progressFor(Mode.FLASHCARD);
        ModeProgress typingBeforeReview = card.progressFor(Mode.TYPING);

        service.recordFlashcardOutcome(card.id(), ReviewOutcome.CORRECT);

        VocabularyCard updated = currentCard(service, card.id());
        assertEquals(5, updated.progressFor(Mode.FLASHCARD).mastery());
        assertEquals(CREATION_DATE.plusDays(99),
                updated.progressFor(Mode.FLASHCARD).nextDueDate());
        assertProgressEquals(typingBeforeReview, updated.progressFor(Mode.TYPING));
        assertEquals(1, scheduler.invocations);
        assertProgressEquals(beforeReview, scheduler.lastProgress);
        assertEquals(ReviewOutcome.CORRECT, scheduler.lastOutcome);
        assertEquals(CREATION_DATE, scheduler.lastReviewDate);
    }

    @Test
    void typingOutcomesAlsoUseTheSuppliedScheduler() throws StorageException {
        RecordingScheduler scheduler = new RecordingScheduler(
                new ModeProgress(4, CREATION_DATE.plusDays(77)));
        KokoService service = new KokoService(new FakeStorage(), FIXED_CLOCK, scheduler);
        VocabularyCard card = service.addVocabularyCard("いぬ", "inu", "dog");

        service.recordTypingOutcome(card.id(), ReviewOutcome.SKIPPED);

        assertEquals(CREATION_DATE.plusDays(77),
                currentCard(service, card.id()).progressFor(Mode.TYPING).nextDueDate());
        assertEquals(ReviewOutcome.SKIPPED, scheduler.lastOutcome);
    }

    @Test
    void serviceRejectsAMissingScheduler() {
        assertThrows(NullPointerException.class, () ->
                new KokoService(new FakeStorage(), FIXED_CLOCK, null));
    }

    private static VocabularyCard currentCard(KokoService service, UUID cardId) {
        return service.data().findVocabularyCard(cardId).orElseThrow();
    }

    private static Deck currentDeck(KokoService service, UUID deckId) {
        return service.data().findDeckById(deckId).orElseThrow();
    }

    /**
     * Verifies that one failed management save leaves all retained aggregate references intact.
     *
     * @param storage storage whose next save should fail.
     * @param service service under test.
     * @param operation management operation whose save should fail.
     * @param originalData aggregate retained before the failed operation.
     * @param cards cards retained before the failed operation.
     * @param decks decks retained before the failed operation.
     */
    private static void assertFailedSave(FakeStorage storage, KokoService service,
            StorageMutation operation, KokoData originalData, List<VocabularyCard> cards,
            List<Deck> decks) {
        KokoData originalValues = KokoDataSnapshots.copyOf(originalData);
        KokoData savedValues = KokoDataSnapshots.copyOf(storage.loadedData);
        int savesBefore = storage.saveInvocations;
        int successfulSavesBefore = storage.successfulSaveCount;
        storage.failNextSave = true;
        assertThrows(StorageException.class, operation::run);
        assertSame(originalData, service.data());
        assertDataEquals(originalValues, service.data());
        assertDataEquals(savedValues, storage.loadedData);
        assertEquals(savesBefore + 1, storage.saveInvocations);
        assertEquals(successfulSavesBefore, storage.successfulSaveCount);
        for (VocabularyCard card : cards) {
            assertSame(card, currentCard(service, card.id()));
        }
        for (Deck deck : decks) {
            assertSame(deck, currentDeck(service, deck.id()));
        }
    }

    private static void assertDataEquals(KokoData expected, KokoData actual) {
        assertEquals(expected.vocabularyCards().stream().map(VocabularyCard::id).toList(),
                actual.vocabularyCards().stream().map(VocabularyCard::id).toList());
        for (VocabularyCard expectedCard : expected.vocabularyCards()) {
            VocabularyCard actualCard = currentCardIn(actual, expectedCard.id());
            assertEquals(expectedCard.hiragana(), actualCard.hiragana());
            assertEquals(expectedCard.romaji(), actualCard.romaji());
            assertEquals(expectedCard.englishMeaning(), actualCard.englishMeaning());
            assertProgressEquals(expectedCard.progressFor(Mode.FLASHCARD),
                    actualCard.progressFor(Mode.FLASHCARD));
            assertProgressEquals(expectedCard.progressFor(Mode.TYPING),
                    actualCard.progressFor(Mode.TYPING));
        }
        assertEquals(expected.decks().stream().map(Deck::id).toList(),
                actual.decks().stream().map(Deck::id).toList());
        for (Deck expectedDeck : expected.decks()) {
            Deck actualDeck = actual.findDeckById(expectedDeck.id()).orElseThrow();
            assertEquals(expectedDeck.name(), actualDeck.name());
            assertEquals(expectedDeck.cardIds(), actualDeck.cardIds());
        }
    }

    private static VocabularyCard currentCardIn(KokoData data, UUID cardId) {
        return data.findVocabularyCard(cardId).orElseThrow();
    }

    /** The management fixture each rejection case acts on. */
    private record Fixture(KokoService service, VocabularyCard card, Deck deck) {
    }

    /** A management call that must be rejected before anything is saved. */
    @FunctionalInterface
    private interface RejectedOperation {
        void run(Fixture fixture) throws StorageException;
    }

    /** A management action whose persistence can fail. */
    @FunctionalInterface
    private interface StorageMutation {
        void run() throws StorageException;
    }

    /**
     * Scheduler double that records its inputs and returns one fixed result.
     *
     * <p>The fixed result is deliberately unreachable by {@link MasteryScheduler},
     * so a test can tell the injected policy apart from the default one.
     */
    private static final class RecordingScheduler implements ReviewScheduler {

        private final ModeProgress result;
        private ModeProgress lastProgress;
        private ReviewOutcome lastOutcome;
        private LocalDate lastReviewDate;
        private int invocations;

        private RecordingScheduler(ModeProgress result) {
            this.result = result;
        }

        @Override
        public ModeProgress schedule(ModeProgress progress, ReviewOutcome outcome,
                LocalDate reviewDate) {
            lastProgress = progress;
            lastOutcome = outcome;
            lastReviewDate = reviewDate;
            invocations++;
            return result;
        }
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
        private KokoData lastSavedData;
        private KokoService observedService;
        private boolean allSavedCandidatesWereDetached = true;

        @Override
        public KokoData load() {
            return loadedData;
        }

        @Override
        public void save(KokoData data) throws StorageException {
            saveInvocations++;
            if (observedService != null && observedService.data() == data) {
                allSavedCandidatesWereDetached = false;
            }
            if (failSaves || failNextSave) {
                failNextSave = false;
                throw new StorageException("forced save failure", null);
            }
            successfulSaveCount++;
            lastSavedData = data;
            loadedData = KokoDataSnapshots.copyOf(data);
        }
    }

    private static void assertProgressEquals(ModeProgress expected, ModeProgress actual) {
        assertEquals(expected.mastery(), actual.mastery());
        assertEquals(expected.nextDueDate(), actual.nextDueDate());
    }
}
