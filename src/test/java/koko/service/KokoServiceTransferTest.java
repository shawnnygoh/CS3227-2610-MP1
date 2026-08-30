package koko.service;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import koko.model.Deck;
import koko.model.KokoData;
import koko.model.Mode;
import koko.model.ModeProgress;
import koko.model.VocabularyCard;
import koko.storage.JsonStorage;
import koko.storage.Storage;
import koko.storage.StorageException;
import koko.transfer.DeckTransferException;

/**
 * Tests transactional service coordination for portable deck transfers.
 */
class KokoServiceTransferTest {

    private static final LocalDate FIRST_DATE = LocalDate.of(2026, 8, 30);
    private static final Clock FIRST_CLOCK = Clock.fixed(
            Instant.parse("2026-08-30T01:00:00Z"), ZoneId.of("Asia/Singapore"));

    @TempDir
    Path temporaryDirectory;

    @Test
    void mixedImportReusesGlobalCardsAndPreservesOrderingProgressAndText() throws Exception {
        RecordingStorage storage = new RecordingStorage();
        KokoData initial = new KokoData();
        VocabularyCard shared = initial.addVocabularyCard("ねこ", "neko", "cat", FIRST_DATE);
        VocabularyCard unassigned = initial.addVocabularyCard("いぬ", "inu", "dog", FIRST_DATE);
        KokoService service = new KokoService(storage, FIRST_CLOCK);
        ModeProgress sharedFlashcard = new ModeProgress(4, 7, 5,
                FIRST_DATE.minusDays(2), FIRST_DATE.plusDays(8));
        ModeProgress sharedTyping = new ModeProgress(2, 3, 1,
                FIRST_DATE.minusDays(1), FIRST_DATE.plusDays(4));
        shared.updateProgress(Mode.FLASHCARD, sharedFlashcard);
        shared.updateProgress(Mode.TYPING, sharedTyping);
        unassigned.updateProgress(Mode.TYPING, sharedFlashcard);
        Deck existingDeck = initial.createDeck("Existing");
        Deck anotherDeck = initial.createDeck("Another");
        initial.addCardToDeck(existingDeck.id(), shared.id());
        initial.addCardToDeck(anotherDeck.id(), shared.id());
        storage.loadedData = initial;
        service.load();
        int savesBeforeImport = storage.saveInvocations;

        Path source = writeSource("{\"schemaVersion\":1,\"deckName\":\"Imported\","
                + "\"cards\":[{\"hiragana\":\"いぬ\",\"romaji\":\"different\","
                + "\"englishMeaning\":\"DOG\"},{\"hiragana\":\"とり\","
                + "\"romaji\":\"tori\",\"englishMeaning\":\"bird\"},"
                + "{\"hiragana\":\"ねこ\",\"romaji\":\"changed\","
                + "\"englishMeaning\":\"CAT\"}]}");
        byte[] sourceBytes = Files.readAllBytes(source);

        Deck imported = service.importDeck(source);

        assertEquals(savesBeforeImport + 1, storage.saveInvocations);
        assertEquals(List.of(unassigned.id(),
                service.data().vocabularyCards().get(2).id(), shared.id()), imported.cardIds());
        assertEquals(List.of(shared.id()), service.data().findDeckById(existingDeck.id())
                .orElseThrow().cardIds());
        assertEquals(List.of(shared.id()), service.data().findDeckById(anotherDeck.id())
                .orElseThrow().cardIds());
        assertEquals(List.of(existingDeck.id(), anotherDeck.id(), imported.id()),
                service.data().decks().stream().map(Deck::id).toList());
        assertEquals(List.of(shared.id(), unassigned.id(), imported.cardIds().get(1)),
                service.data().vocabularyCards().stream().map(VocabularyCard::id).toList());
        VocabularyCard reusedShared = service.data().findVocabularyCard(shared.id()).orElseThrow();
        VocabularyCard reusedUnassigned = service.data().findVocabularyCard(unassigned.id())
                .orElseThrow();
        assertCardEquals(shared, reusedShared);
        assertCardEquals(unassigned, reusedUnassigned);
        VocabularyCard newCard = service.data().findVocabularyCard(imported.cardIds().get(1))
                .orElseThrow();
        assertNotEquals(shared.id(), newCard.id());
        assertNotEquals(unassigned.id(), newCard.id());
        assertEquals("tori", newCard.romaji());
        assertFreshProgress(newCard, FIRST_DATE);
        assertArrayEquals(sourceBytes, Files.readAllBytes(source));
        assertDataEquals(service.data(), storage.loadedData);
    }

    @Test
    void emptyImportCreatesOneDeckAndSavesOnce() throws Exception {
        RecordingStorage storage = new RecordingStorage();
        KokoService service = new KokoService(storage, FIRST_CLOCK);
        Path source = writeSource("{\"schemaVersion\":1,\"deckName\":\"Empty\","
                + "\"cards\":[]}");

        Deck imported = service.importDeck(source);

        assertEquals(1, storage.saveInvocations);
        assertTrue(imported.cardIds().isEmpty());
        assertEquals(1, service.data().decks().size());
        assertTrue(service.data().vocabularyCards().isEmpty());
    }

    @Test
    void importUsesTheDomainNfcNormalizationRules() throws Exception {
        RecordingStorage storage = new RecordingStorage();
        KokoService service = new KokoService(storage, FIRST_CLOCK);
        Path source = writeSource("{\"schemaVersion\":1,\"deckName\":\"NFC\","
                + "\"cards\":[{\"hiragana\":\"か\\u3099\","
                + "\"romaji\":\"ko\\u0304hi\\u0304\","
                + "\"englishMeaning\":\"cafe\\u0301\"}]}");

        Deck imported = service.importDeck(source);
        VocabularyCard card = service.data().findVocabularyCard(imported.cardIds().get(0))
                .orElseThrow();

        assertEquals("が", card.hiragana());
        assertEquals("kōhī", card.romaji());
        assertEquals("café", card.englishMeaning());
    }

    @Test
    void rejectedImportsDoNotSaveOrChangeLiveReferencesOrBytes() throws Exception {
        Path storagePath = temporaryDirectory.resolve("internal.json");
        FailOnceStorage storage = new FailOnceStorage(new JsonStorage(storagePath));
        KokoService service = new KokoService(storage, FIRST_CLOCK);
        VocabularyCard card = service.addVocabularyCard("ねこ", "neko", "cat");
        Deck deck = service.createDeck("Existing");
        service.addCardToDeck(deck.id(), card.id());
        KokoData originalData = service.data();
        card = service.data().findVocabularyCard(card.id()).orElseThrow();
        deck = service.data().findDeckById(deck.id()).orElseThrow();
        KokoData originalValues = copyOf(originalData);
        byte[] originalBytes = Files.readAllBytes(storagePath);
        ModeProgress originalProgress = card.progressFor(Mode.FLASHCARD);
        int savesBefore = storage.saveInvocations;
        Path source = writeSource("{\"schemaVersion\":1,\"deckName\":\"Imported\","
                + "\"cards\":[{\"hiragana\":\"いぬ\",\"romaji\":\"inu\","
                + "\"englishMeaning\":\"dog\"},{\"hiragana\":\"猫\","
                + "\"romaji\":\"neko\",\"englishMeaning\":\"bad\"}]}");
        Path duplicate = writeSource("{\"schemaVersion\":1,\"deckName\":\"Imported\","
                + "\"cards\":[{\"hiragana\":\"いぬ\",\"romaji\":\"inu\","
                + "\"englishMeaning\":\"dog\"},{\"hiragana\":\" いぬ \","
                + "\"romaji\":\"different\",\"englishMeaning\":\"DOG\"}]}");
        Path invalidMatch = writeSource("{\"schemaVersion\":1,\"deckName\":\"Imported\","
                + "\"cards\":[{\"hiragana\":\"ねこ\",\"romaji\":\" \","
                + "\"englishMeaning\":\"CAT\"}]}");
        Path invalidUnicode = writeSource("{\"schemaVersion\":1,\"deckName\":\"\\uD800\","
                + "\"cards\":[]}");
        Path missing = temporaryDirectory.resolve("missing.json");

        for (Path rejected : List.of(source, duplicate, invalidMatch, invalidUnicode, missing)) {
            assertThrows(DeckTransferException.class, () -> service.importDeck(rejected));
            assertEquals(savesBefore, storage.saveInvocations);
            assertSame(originalData, service.data());
            assertSame(card, service.data().findVocabularyCard(card.id()).orElseThrow());
            assertSame(originalProgress, card.progressFor(Mode.FLASHCARD));
            assertSame(deck, service.data().findDeckById(deck.id()).orElseThrow());
            assertDataEquals(originalValues, service.data());
            assertArrayEquals(originalBytes, Files.readAllBytes(storagePath));
        }
    }

    @Test
    void nameConflictsAndRepeatedSuccessfulImportsDoNotSaveOrPublish() throws Exception {
        Path storagePath = temporaryDirectory.resolve("internal.json");
        FailOnceStorage storage = new FailOnceStorage(new JsonStorage(storagePath));
        KokoService service = new KokoService(storage, FIRST_CLOCK);
        Deck existing = service.createDeck("Animals");
        KokoData originalData = service.data();
        byte[] originalBytes = Files.readAllBytes(storagePath);
        Path conflict = writeSource("{\"schemaVersion\":1,\"deckName\":\" aNiMaLs \","
                + "\"cards\":[{\"hiragana\":\"ねこ\",\"romaji\":\"neko\","
                + "\"englishMeaning\":\"cat\"}]}");

        assertThrows(IllegalArgumentException.class, () -> service.importDeck(conflict));
        assertEquals(1, storage.saveInvocations);
        assertSame(originalData, service.data());
        assertSame(existing, service.data().findDeckById(existing.id()).orElseThrow());
        assertTrue(service.data().vocabularyCards().isEmpty());
        assertArrayEquals(originalBytes, Files.readAllBytes(storagePath));

        Path source = writeSource(Files.readString(conflict, StandardCharsets.UTF_8)
                .replace(" aNiMaLs ", "Imported"));
        Deck imported = service.importDeck(source);
        KokoData importedData = service.data();
        KokoData importedValues = copyOf(importedData);
        byte[] importedBytes = Files.readAllBytes(storagePath);
        byte[] sourceBytes = Files.readAllBytes(source);

        assertThrows(IllegalArgumentException.class, () -> service.importDeck(source));
        assertEquals(2, storage.saveInvocations);
        assertSame(importedData, service.data());
        assertSame(imported, service.data().findDeckById(imported.id()).orElseThrow());
        assertDataEquals(importedValues, service.data());
        assertArrayEquals(importedBytes, Files.readAllBytes(storagePath));
        assertArrayEquals(sourceBytes, Files.readAllBytes(source));
    }

    @Test
    void failedSaveLeavesBytesAndLiveReferencesUnchangedThenRetryUsesNewDate() throws Exception {
        Path storagePath = temporaryDirectory.resolve("internal.json");
        JsonStorage jsonStorage = new JsonStorage(storagePath);
        KokoData initial = new KokoData();
        VocabularyCard existing = initial.addVocabularyCard("ねこ", "neko", "cat", FIRST_DATE);
        existing.updateProgress(Mode.FLASHCARD, new ModeProgress(4, 8, 6,
                FIRST_DATE.minusDays(2), FIRST_DATE.plusDays(14)));
        existing.updateProgress(Mode.TYPING, new ModeProgress(2, 5, 3,
                FIRST_DATE.minusDays(1), FIRST_DATE.plusDays(3)));
        Deck existingDeck = initial.createDeck("Existing");
        initial.addCardToDeck(existingDeck.id(), existing.id());
        jsonStorage.save(initial);
        byte[] originalBytes = Files.readAllBytes(storagePath);

        FailOnceStorage storage = new FailOnceStorage(jsonStorage);
        AtomicReference<Instant> now = new AtomicReference<>(
                Instant.parse("2026-08-30T01:00:00Z"));
        Clock clock = new ReferenceClock(now, ZoneId.of("Asia/Singapore"));
        KokoService service = new KokoService(storage, clock);
        service.load();
        KokoData originalData = service.data();
        KokoData originalValues = copyOf(originalData);
        VocabularyCard originalCard = service.data().findVocabularyCard(existing.id()).orElseThrow();
        Deck originalDeck = service.data().findDeckById(existingDeck.id()).orElseThrow();
        Path source = writeSource("{\"schemaVersion\":1,\"deckName\":\"Retry\","
                + "\"cards\":[{\"hiragana\":\"とり\",\"romaji\":\"tori\","
                + "\"englishMeaning\":\"bird\"},{\"hiragana\":\"ねこ\","
                + "\"romaji\":\"changed\",\"englishMeaning\":\"CAT\"},"
                + "{\"hiragana\":\"いぬ\",\"romaji\":\"inu\",\"englishMeaning\":\"dog\"}]}");
        byte[] sourceBytes = Files.readAllBytes(source);

        storage.failNextSave = true;
        assertThrows(StorageException.class, () -> service.importDeck(source));
        assertEquals(1, storage.saveInvocations);
        assertArrayEquals(originalBytes, Files.readAllBytes(storagePath));
        assertSame(originalData, service.data());
        assertSame(originalCard, service.data().findVocabularyCard(existing.id()).orElseThrow());
        assertSame(originalDeck, service.data().findDeckById(existingDeck.id()).orElseThrow());
        assertDataEquals(originalValues, originalData);
        assertEquals(1, service.data().vocabularyCards().size());
        assertEquals(1, service.data().decks().size());

        now.set(Instant.parse("2026-09-01T01:00:00Z"));
        Deck imported = service.importDeck(source);

        assertEquals(2, storage.saveInvocations);
        assertEquals(2, service.data().decks().size());
        VocabularyCard importedCard = service.data().findVocabularyCard(imported.cardIds().get(0))
                .orElseThrow();
        assertFreshProgress(importedCard, LocalDate.of(2026, 9, 1));
        VocabularyCard otherNewCard = service.data().findVocabularyCard(imported.cardIds().get(2))
                .orElseThrow();
        assertFreshProgress(otherNewCard, LocalDate.of(2026, 9, 1));
        assertNotEquals(importedCard.id(), otherNewCard.id());
        assertEquals(existing.id(), imported.cardIds().get(1));
        assertCardEquals(existing, service.data().findVocabularyCard(existing.id()).orElseThrow());
        assertEquals(3, service.data().vocabularyCards().size());
        assertDataEquals(service.data(), new JsonStorage(storagePath).load());
        assertArrayEquals(sourceBytes, Files.readAllBytes(source));
    }

    @Test
    void importedReferencesShareProgressAndNewMeaningsRemainIndependent() throws Exception {
        RecordingStorage storage = new RecordingStorage();
        KokoService service = new KokoService(storage, FIRST_CLOCK);
        VocabularyCard existing = service.addVocabularyCard("ねこ", "neko", "cat");
        Deck firstDeck = service.createDeck("First");
        service.addCardToDeck(firstDeck.id(), existing.id());
        Path source = writeSource("{\"schemaVersion\":1,\"deckName\":\"Second\","
                + "\"cards\":[{\"hiragana\":\"ねこ\",\"romaji\":\"different\","
                + "\"englishMeaning\":\"CAT\"},{\"hiragana\":\"ねこ\","
                + "\"romaji\":\"neko\",\"englishMeaning\":\"kitten\"}]}");
        Deck imported = service.importDeck(source);
        UUID newId = imported.cardIds().get(1);
        assertNotEquals(existing.id(), newId);
        assertFreshProgress(service.data().findVocabularyCard(newId).orElseThrow(), FIRST_DATE);

        service.recordFlashcardOutcome(newId, ReviewOutcome.CORRECT);
        assertProgressEquals(ModeProgress.forCreationDate(FIRST_DATE),
                service.data().findVocabularyCard(newId).orElseThrow().progressFor(Mode.TYPING));
        service.recordTypingOutcome(existing.id(), ReviewOutcome.CORRECT);

        VocabularyCard shared = service.data().findVocabularyCard(imported.cardIds().get(0))
                .orElseThrow();
        UUID firstDeckCard = service.data().findDeckById(firstDeck.id()).orElseThrow().cardIds().get(0);
        assertSame(shared, service.data().findVocabularyCard(firstDeckCard).orElseThrow());
        assertProgressEquals(new ModeProgress(1, 1, 1, FIRST_DATE, FIRST_DATE.plusDays(1)),
                shared.progressFor(Mode.TYPING));
        assertProgressEquals(ModeProgress.forCreationDate(FIRST_DATE),
                shared.progressFor(Mode.FLASHCARD));
    }

    @Test
    void exportUsesMembershipOrderAndExcludesProgressAndUnrelatedCards() throws Exception {
        Path storagePath = temporaryDirectory.resolve("internal.json");
        FailOnceStorage storage = new FailOnceStorage(new JsonStorage(storagePath));
        KokoService service = new KokoService(storage, FIRST_CLOCK);
        VocabularyCard unrelated = service.addVocabularyCard("そら", "sora", "sky");
        VocabularyCard first = service.addVocabularyCard("ねこ", "neko", "cat");
        VocabularyCard second = service.addVocabularyCard("いぬ", "inu", "dog");
        service.data().findVocabularyCard(first.id()).orElseThrow().updateProgress(
                Mode.FLASHCARD, new ModeProgress(5, 9, 8,
                FIRST_DATE.minusDays(3), FIRST_DATE.plusDays(30)));
        service.data().findVocabularyCard(second.id()).orElseThrow().updateProgress(
                Mode.TYPING, new ModeProgress(3, 4, 2,
                FIRST_DATE.minusDays(2), FIRST_DATE.plusDays(10)));
        Deck deck = service.createDeck("動物 \"\\");
        service.addCardToDeck(deck.id(), second.id());
        service.addCardToDeck(deck.id(), first.id());
        int savesBeforeExport = storage.saveInvocations;
        KokoData dataBeforeExport = service.data();
        Deck currentDeck = service.data().findDeckById(deck.id()).orElseThrow();
        VocabularyCard currentFirst = service.data().findVocabularyCard(first.id()).orElseThrow();
        KokoData valuesBeforeExport = copyOf(dataBeforeExport);
        byte[] bytesBeforeExport = Files.readAllBytes(storagePath);
        Path destination = temporaryDirectory.resolve("export with spaces.json");

        service.exportDeck(deck.id(), destination);

        assertEquals(savesBeforeExport, storage.saveInvocations);
        assertSame(dataBeforeExport, service.data());
        assertSame(currentDeck, service.data().findDeckById(deck.id()).orElseThrow());
        assertSame(currentFirst, service.data().findVocabularyCard(first.id()).orElseThrow());
        JsonNode root = new ObjectMapper().readTree(Files.readString(destination,
                StandardCharsets.UTF_8));
        assertEquals(Set.of("schemaVersion", "deckName", "cards"), fieldNames(root));
        assertEquals(List.of("dog", "cat"), root.get("cards").findValuesAsText("englishMeaning"));
        for (JsonNode card : root.get("cards")) {
            assertEquals(Set.of("hiragana", "romaji", "englishMeaning"), fieldNames(card));
        }
        assertFalse(Files.readString(destination, StandardCharsets.UTF_8).contains(unrelated.id()
                .toString()));
        assertFalse(Files.readString(destination, StandardCharsets.UTF_8).contains("progress"));
        assertDataEquals(valuesBeforeExport, service.data());
        assertArrayEquals(bytesBeforeExport, Files.readAllBytes(storagePath));

        Path importedStorage = temporaryDirectory.resolve("other-library.json");
        KokoService recipient = new KokoService(new JsonStorage(importedStorage), FIRST_CLOCK);
        Deck imported = recipient.importDeck(destination);
        assertNotEquals(deck.id(), imported.id());
        assertEquals(deck.name(), imported.name());
        assertEquals(List.of("dog", "cat"), imported.cardIds().stream()
                .map(id -> recipient.data().findVocabularyCard(id).orElseThrow().englishMeaning())
                .toList());
        for (VocabularyCard importedCard : recipient.data().vocabularyCards()) {
            assertFalse(dataBeforeExport.findVocabularyCard(importedCard.id()).isPresent());
            assertFreshProgress(importedCard, FIRST_DATE);
        }
        for (int index = 0; index < currentDeck.cardIds().size(); index++) {
            VocabularyCard expected = dataBeforeExport
                    .findVocabularyCard(currentDeck.cardIds().get(index))
                    .orElseThrow();
            VocabularyCard actual = recipient.data().findVocabularyCard(imported.cardIds().get(index))
                    .orElseThrow();
            assertEquals(expected.hiragana(), actual.hiragana());
            assertEquals(expected.romaji(), actual.romaji());
            assertEquals(expected.englishMeaning(), actual.englishMeaning());
        }
        assertDataEquals(recipient.data(), new JsonStorage(importedStorage).load());
    }

    @Test
    void exportRejectsUnknownDeckAndProtectsExistingDestination() throws Exception {
        RecordingStorage storage = new RecordingStorage();
        KokoService service = new KokoService(storage, FIRST_CLOCK);
        Deck deck = service.createDeck("Empty");
        Path unknownDestination = temporaryDirectory.resolve("unknown.json");

        assertThrows(IllegalArgumentException.class, () ->
                service.exportDeck(UUID.randomUUID(), unknownDestination));
        assertTrue(Files.notExists(unknownDestination));
        Path existingDestination = temporaryDirectory.resolve("existing.json");
        byte[] sentinel = "sentinel".getBytes(StandardCharsets.UTF_8);
        Files.write(existingDestination, sentinel);

        assertThrows(DeckTransferException.class, () ->
                service.exportDeck(deck.id(), existingDestination));
        assertArrayEquals(sentinel, Files.readAllBytes(existingDestination));
        assertEquals(1, storage.saveInvocations);
    }

    @Test
    void emptyDeckExportsThroughServiceWithoutSaving() throws Exception {
        RecordingStorage storage = new RecordingStorage();
        KokoService service = new KokoService(storage, FIRST_CLOCK);
        Deck deck = service.createDeck("Empty");
        Path destination = temporaryDirectory.resolve("empty.json");

        service.exportDeck(deck.id(), destination);

        assertEquals(1, storage.saveInvocations);
        assertTrue(new ObjectMapper()
                .readTree(Files.readString(destination, StandardCharsets.UTF_8))
                .get("cards").isEmpty());
    }

    private Path writeSource(String json) throws IOException {
        Path source = temporaryDirectory.resolve("source-" + UUID.randomUUID() + ".json");
        Files.writeString(source, json, StandardCharsets.UTF_8);
        return source;
    }

    private static Set<String> fieldNames(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return Set.copyOf(names);
    }

    private static void assertFreshProgress(VocabularyCard card, LocalDate date) {
        assertNotSame(card.progressFor(Mode.FLASHCARD), card.progressFor(Mode.TYPING));
        for (Mode mode : Mode.values()) {
            ModeProgress progress = card.progressFor(mode);
            assertEquals(0, progress.mastery());
            assertEquals(0, progress.attempts());
            assertEquals(0, progress.correctAttempts());
            assertNull(progress.lastReviewedDate());
            assertEquals(date, progress.nextDueDate());
        }
    }

    private static void assertCardEquals(VocabularyCard expected, VocabularyCard actual) {
        assertEquals(expected.id(), actual.id());
        assertEquals(expected.hiragana(), actual.hiragana());
        assertEquals(expected.romaji(), actual.romaji());
        assertEquals(expected.englishMeaning(), actual.englishMeaning());
        for (Mode mode : Mode.values()) {
            assertProgressEquals(expected.progressFor(mode), actual.progressFor(mode));
        }
    }

    private static void assertDataEquals(KokoData expected, KokoData actual) {
        assertEquals(expected.vocabularyCards().stream().map(VocabularyCard::id).toList(),
                actual.vocabularyCards().stream().map(VocabularyCard::id).toList());
        assertEquals(expected.decks().stream().map(Deck::id).toList(),
                actual.decks().stream().map(Deck::id).toList());
        for (VocabularyCard card : expected.vocabularyCards()) {
            assertCardEquals(card, actual.findVocabularyCard(card.id()).orElseThrow());
        }
        for (Deck deck : expected.decks()) {
            Deck actualDeck = actual.findDeckById(deck.id()).orElseThrow();
            assertEquals(deck.name(), actualDeck.name());
            assertEquals(deck.cardIds(), actualDeck.cardIds());
        }
    }

    private static void assertProgressEquals(ModeProgress expected, ModeProgress actual) {
        assertEquals(expected.mastery(), actual.mastery());
        assertEquals(expected.attempts(), actual.attempts());
        assertEquals(expected.correctAttempts(), actual.correctAttempts());
        assertEquals(expected.lastReviewedDate(), actual.lastReviewedDate());
        assertEquals(expected.nextDueDate(), actual.nextDueDate());
    }

    private static KokoData copyOf(KokoData source) {
        List<VocabularyCard> cards = source.vocabularyCards().stream()
                .map(card -> VocabularyCard.restore(card.id(), card.hiragana(), card.romaji(),
                        card.englishMeaning(), copyProgress(card.progressFor(Mode.FLASHCARD)),
                        copyProgress(card.progressFor(Mode.TYPING))))
                .toList();
        List<Deck> decks = source.decks().stream()
                .map(deck -> Deck.restore(deck.id(), deck.name(), deck.cardIds()))
                .toList();
        return KokoData.restore(cards, decks);
    }

    private static ModeProgress copyProgress(ModeProgress progress) {
        return new ModeProgress(progress.mastery(), progress.attempts(),
                progress.correctAttempts(), progress.lastReviewedDate(), progress.nextDueDate());
    }

    /** Records save calls and retains detached snapshots for assertions. */
    private static final class RecordingStorage implements Storage {

        private KokoData loadedData = new KokoData();
        private int saveInvocations;

        @Override
        public KokoData load() {
            return loadedData;
        }

        @Override
        public void save(KokoData data) {
            saveInvocations++;
            loadedData = copyOf(data);
        }
    }

    /** Fails a requested save before delegating to actual JSON persistence. */
    private static final class FailOnceStorage implements Storage {

        private final JsonStorage delegate;
        private int saveInvocations;
        private boolean failNextSave;

        private FailOnceStorage(JsonStorage delegate) {
            this.delegate = delegate;
        }

        @Override
        public KokoData load() throws StorageException {
            return delegate.load();
        }

        @Override
        public void save(KokoData data) throws StorageException {
            saveInvocations++;
            if (failNextSave) {
                failNextSave = false;
                throw new StorageException("forced save failure", null);
            }
            delegate.save(data);
        }
    }

    /** Supplies controllable dates without sleeping or changing the system clock. */
    private static final class ReferenceClock extends Clock {

        private final AtomicReference<Instant> instant;
        private final ZoneId zone;

        private ReferenceClock(AtomicReference<Instant> instant, ZoneId zone) {
            this.instant = instant;
            this.zone = zone;
        }

        @Override
        public ZoneId getZone() {
            return zone;
        }

        @Override
        public Clock withZone(ZoneId newZone) {
            return new ReferenceClock(instant, newZone);
        }

        @Override
        public Instant instant() {
            return instant.get();
        }
    }
}
