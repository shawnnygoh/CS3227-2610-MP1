package koko.service;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.InstantSource;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

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
import koko.testutil.KokoDataSnapshots;
import koko.transfer.DeckTransfer;
import koko.transfer.DeckTransferException;
import koko.transfer.PortableCard;
import koko.transfer.PortableDeck;

/**
 * Tests transactional service coordination for portable deck transfers.
 */
class KokoServiceTransferTest {

    private static final LocalDate FIRST_DATE = LocalDate.of(2026, 8, 30);
    private static final Clock FIRST_CLOCK = Clock.fixed(
            Instant.parse("2026-08-30T01:00:00Z"), ZoneId.of("Asia/Singapore"));

    @TempDir
    Path temporaryDirectory;

    @ParameterizedTest
    @ValueSource(strings = {"\n", "\r", "\t", "\u2028", "\u2029"})
    void embeddedControlsRejectPreparedAndDirectImportsWithoutSaving(String separator) throws Exception {
        JsonStorage storage = new JsonStorage(temporaryDirectory.resolve("library.json"));
        KokoService service = new KokoService(storage, FIRST_CLOCK);
        service.addVocabularyCard("いぬ", "inu", "dog");
        KokoData original = service.data();
        byte[] originalBytes = Files.readAllBytes(storage.configuredPath().orElseThrow());
        PortableDeck document = new PortableDeck(1, "Invalid", List.of(
                new PortableCard("ね" + separator + "こ", "neko", "cat")));
        Path source = writeSource(new ObjectMapper().writeValueAsString(document));

        assertThrows(DeckTransferException.class, () -> service.prepareImport(source));
        assertThrows(DeckTransferException.class, () -> service.importDeck(document, "Invalid"));

        assertSame(original, service.data());
        assertEquals(1, service.data().vocabularyCards().size());
        assertTrue(service.data().decks().isEmpty());
        assertArrayEquals(originalBytes, Files.readAllBytes(storage.configuredPath().orElseThrow()));
    }

    @Test
    void mixedImportReusesGlobalCardsAndPreservesOrderingProgressAndText() throws Exception {
        RecordingStorage storage = new RecordingStorage();
        KokoData initial = new KokoData();
        VocabularyCard shared = initial.addVocabularyCard("ねこ", "neko", "cat", FIRST_DATE);
        VocabularyCard unassigned = initial.addVocabularyCard("いぬ", "inu", "dog", FIRST_DATE);
        KokoService service = new KokoService(storage, FIRST_CLOCK);
        ModeProgress sharedFlashcard = new ModeProgress(4, FIRST_DATE.plusDays(8));
        ModeProgress sharedTyping = new ModeProgress(2, FIRST_DATE.plusDays(4));
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

        PortableDeck document = service.prepareImport(source);
        Deck imported = service.importDeck(document, document.deckName());

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

        PortableDeck document = service.prepareImport(source);
        Deck imported = service.importDeck(document, document.deckName());

        assertEquals(1, storage.saveInvocations);
        assertTrue(imported.cardIds().isEmpty());
        assertEquals(1, service.data().decks().size());
        assertTrue(service.data().vocabularyCards().isEmpty());
    }

    @Test
    void preparationReadsWithoutMutationAndApplicationUsesConfirmedName() throws Exception {
        RecordingStorage storage = new RecordingStorage();
        KokoService service = new KokoService(storage, FIRST_CLOCK);
        Path source = writeSource("{\"schemaVersion\":1,\"deckName\":\"Animals\","
                + "\"cards\":[{\"hiragana\":\"ねこ\",\"romaji\":\"neko\","
                + "\"englishMeaning\":\"cat\"}]}");
        KokoData originalData = service.data();

        PortableDeck document = service.prepareImport(source);

        assertEquals("Animals", document.deckName());
        assertSame(originalData, service.data());
        assertEquals(0, storage.saveInvocations);

        Deck imported = service.importDeck(document, "Animals Practice");

        assertEquals("Animals Practice", imported.name());
        assertEquals(1, storage.saveInvocations);
        assertEquals("Animals", document.deckName());
    }

    @Test
    void blankAndConflictingConfirmedNamesCanBeCorrectedWithoutRepreparing() throws Exception {
        RecordingStorage storage = new RecordingStorage();
        KokoService service = new KokoService(storage, FIRST_CLOCK);
        Deck existing = service.createDeck("Animals");
        Path source = writeSource("{\"schemaVersion\":1,\"deckName\":\"Imported\","
                + "\"cards\":[]}");
        PortableDeck document = service.prepareImport(source);
        KokoData originalData = service.data();

        assertThrows(IllegalArgumentException.class, () -> service.importDeck(document, "  "));
        assertThrows(IllegalArgumentException.class, () ->
                service.importDeck(document, "aNiMaLs"));

        assertEquals(1, storage.saveInvocations);
        assertSame(originalData, service.data());
        assertSame(existing, service.data().findDeckById(existing.id()).orElseThrow());
        assertEquals(1, service.data().decks().size());

        Deck corrected = service.importDeck(document, " 日本語 🐈 Practice ");

        assertEquals("日本語 🐈 Practice", corrected.name());
        assertEquals(2, storage.saveInvocations);
        assertEquals(2, service.data().decks().size());
        assertEquals("Imported", document.deckName());
    }

    @Test
    void applicationCannotUseRenamingToRepairInvalidPortableDocuments() throws Exception {
        RecordingStorage storage = new RecordingStorage();
        KokoService service = new KokoService(storage, FIRST_CLOCK);
        PortableDeck invalid = new PortableDeck(1, "Embedded", List.of(
                new PortableCard("ねこ", " ", "cat")));
        PortableDeck unsupported = new PortableDeck(2, "Embedded", List.of());
        PortableDeck duplicate = new PortableDeck(1, "Embedded", List.of(
                new PortableCard("ねこ", "neko", "cat"),
                new PortableCard(" ねこ ", "different", "CAT")));
        PortableDeck invalidEmbeddedName = new PortableDeck(1,
                "Embedded " + String.valueOf((char) 0xd800), List.of());

        assertThrows(DeckTransferException.class, () ->
                service.importDeck(invalid, "Replacement"));
        assertThrows(DeckTransferException.class, () ->
                service.importDeck(unsupported, "Replacement"));
        assertThrows(DeckTransferException.class, () ->
                service.importDeck(duplicate, "Replacement"));
        assertThrows(DeckTransferException.class, () ->
                service.importDeck(invalidEmbeddedName, "Replacement"));

        assertEquals(0, storage.saveInvocations);
        assertTrue(service.data().decks().isEmpty());
        assertTrue(service.data().vocabularyCards().isEmpty());
    }

    @Test
    void confirmedNamesRejectUnpairedSurrogatesBeforeChangingState() throws Exception {
        RecordingStorage storage = new RecordingStorage();
        KokoService service = new KokoService(storage, FIRST_CLOCK);
        VocabularyCard existingCard = service.addVocabularyCard("ねこ", "neko", "cat");
        Deck existingDeck = service.createDeck("Existing");
        service.addCardToDeck(existingDeck.id(), existingCard.id());
        existingCard = service.data().findVocabularyCard(existingCard.id()).orElseThrow();
        existingDeck = service.data().findDeckById(existingDeck.id()).orElseThrow();
        KokoData originalData = service.data();
        int savesBeforeImport = storage.saveInvocations;
        PortableDeck document = new PortableDeck(1, "Imported", List.of());

        for (char surrogate : new char[] {'\uD800', '\uDC00'}) {
            String confirmedName = "Confirmed " + surrogate;
            Executable importAttempt = () -> service.importDeck(document, confirmedName);
            IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                    importAttempt);

            assertTrue(exception.getMessage().contains("valid Unicode"));
            assertEquals(savesBeforeImport, storage.saveInvocations);
            assertSame(originalData, service.data());
            assertSame(existingCard,
                    service.data().findVocabularyCard(existingCard.id()).orElseThrow());
            assertSame(existingDeck, service.data().findDeckById(existingDeck.id()).orElseThrow());
            assertEquals(1, service.data().decks().size());
        }
    }

    @Test
    void preparationKeepsTheDocumentSnapshotWhenTheSourceChanges() throws Exception {
        RecordingStorage storage = new RecordingStorage();
        KokoService service = new KokoService(storage, FIRST_CLOCK);
        Path source = writeSource("{\"schemaVersion\":1,\"deckName\":\"Original\","
                + "\"cards\":[{\"hiragana\":\"ねこ\",\"romaji\":\"neko\","
                + "\"englishMeaning\":\"cat\"}]}");
        PortableDeck document = service.prepareImport(source);
        Files.writeString(source, "{\"schemaVersion\":1,\"deckName\":\"Changed\","
                + "\"cards\":[{\"hiragana\":\"いぬ\",\"romaji\":\"inu\","
                + "\"englishMeaning\":\"dog\"}]}");

        Deck imported = service.importDeck(document, document.deckName());

        assertEquals("Original", imported.name());
        VocabularyCard importedCard = service.data()
                .findVocabularyCard(imported.cardIds().get(0)).orElseThrow();
        assertEquals("ねこ", importedCard.hiragana());
        assertEquals("cat", importedCard.englishMeaning());
    }

    @Test
    void importUsesTheDomainNfcNormalizationRules() throws Exception {
        RecordingStorage storage = new RecordingStorage();
        KokoService service = new KokoService(storage, FIRST_CLOCK);
        Path source = writeSource("{\"schemaVersion\":1,\"deckName\":\"NFC\","
                + "\"cards\":[{\"hiragana\":\"か\\u3099\","
                + "\"romaji\":\"ko\\u0304hi\\u0304\","
                + "\"englishMeaning\":\"cafe\\u0301\"}]}");

        PortableDeck document = service.prepareImport(source);
        Deck imported = service.importDeck(document, document.deckName());
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
        KokoData originalValues = KokoDataSnapshots.copyOf(originalData);
        byte[] originalBytes = Files.readAllBytes(storagePath);
        ModeProgress originalProgress = card.progressFor(Mode.FLASHCARD);
        int savesBefore = storage.saveInvocations();
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
            assertThrows(DeckTransferException.class, () -> service.prepareImport(rejected));
            assertEquals(savesBefore, storage.saveInvocations());
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

        PortableDeck conflictingDocument = service.prepareImport(conflict);
        assertThrows(IllegalArgumentException.class, () ->
                service.importDeck(conflictingDocument, conflictingDocument.deckName()));
        assertEquals(1, storage.saveInvocations());
        assertSame(originalData, service.data());
        assertSame(existing, service.data().findDeckById(existing.id()).orElseThrow());
        assertTrue(service.data().vocabularyCards().isEmpty());
        assertArrayEquals(originalBytes, Files.readAllBytes(storagePath));

        Path source = writeSource(Files.readString(conflict, StandardCharsets.UTF_8)
                .replace(" aNiMaLs ", "Imported"));
        PortableDeck document = service.prepareImport(source);
        Deck imported = service.importDeck(document, document.deckName());
        KokoData importedData = service.data();
        KokoData importedValues = KokoDataSnapshots.copyOf(importedData);
        byte[] importedBytes = Files.readAllBytes(storagePath);
        byte[] sourceBytes = Files.readAllBytes(source);

        assertThrows(IllegalArgumentException.class, () ->
                service.importDeck(document, document.deckName()));
        assertEquals(2, storage.saveInvocations());
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
        existing.updateProgress(Mode.FLASHCARD, new ModeProgress(4, FIRST_DATE.plusDays(14)));
        existing.updateProgress(Mode.TYPING, new ModeProgress(2, FIRST_DATE.plusDays(3)));
        Deck existingDeck = initial.createDeck("Existing");
        initial.addCardToDeck(existingDeck.id(), existing.id());
        jsonStorage.save(initial);
        byte[] originalBytes = Files.readAllBytes(storagePath);

        FailOnceStorage storage = new FailOnceStorage(jsonStorage);
        AtomicReference<Instant> now = new AtomicReference<>(
                Instant.parse("2026-08-30T01:00:00Z"));
        InstantSource timeSource = now::get;
        Clock clock = timeSource.withZone(ZoneId.of("Asia/Singapore"));
        KokoService service = new KokoService(storage, clock);
        service.load();
        KokoData originalData = service.data();
        KokoData originalValues = KokoDataSnapshots.copyOf(originalData);
        VocabularyCard originalCard = service.data().findVocabularyCard(existing.id()).orElseThrow();
        Deck originalDeck = service.data().findDeckById(existingDeck.id()).orElseThrow();
        Path source = writeSource("{\"schemaVersion\":1,\"deckName\":\"Retry\","
                + "\"cards\":[{\"hiragana\":\"とり\",\"romaji\":\"tori\","
                + "\"englishMeaning\":\"bird\"},{\"hiragana\":\"ねこ\","
                + "\"romaji\":\"changed\",\"englishMeaning\":\"CAT\"},"
                + "{\"hiragana\":\"いぬ\",\"romaji\":\"inu\",\"englishMeaning\":\"dog\"}]}");
        PortableDeck document = service.prepareImport(source);

        storage.failNextSave();
        assertThrows(StorageException.class, () -> service.importDeck(document, "Retry"));
        assertEquals(1, storage.saveInvocations());
        assertArrayEquals(originalBytes, Files.readAllBytes(storagePath));
        assertSame(originalData, service.data());
        assertSame(originalCard, service.data().findVocabularyCard(existing.id()).orElseThrow());
        assertSame(originalDeck, service.data().findDeckById(existingDeck.id()).orElseThrow());
        assertDataEquals(originalValues, originalData);
        assertEquals(1, service.data().vocabularyCards().size());
        assertEquals(1, service.data().decks().size());

        Files.delete(source);
        now.set(Instant.parse("2026-09-01T01:00:00Z"));
        int savesBeforeRetry = storage.saveInvocations();
        Deck imported = service.importDeck(document, "Retry Edited");

        assertEquals(savesBeforeRetry + 1, storage.saveInvocations());
        assertEquals("Retry Edited", imported.name());
        assertEquals("Retry", document.deckName());
        assertTrue(Files.notExists(source));
        assertEquals(2, service.data().decks().size());
        assertEquals(3, imported.cardIds().size());
        assertEquals(3, imported.cardIds().stream().distinct().count());
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
        PortableDeck document = service.prepareImport(source);
        Deck imported = service.importDeck(document, document.deckName());
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
        assertProgressEquals(new ModeProgress(1, FIRST_DATE.plusDays(1)),
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
                Mode.FLASHCARD, new ModeProgress(5, FIRST_DATE.plusDays(30)));
        service.data().findVocabularyCard(second.id()).orElseThrow().updateProgress(
                Mode.TYPING, new ModeProgress(3, FIRST_DATE.plusDays(10)));
        Deck deck = service.createDeck("動物 \"\\");
        service.addCardToDeck(deck.id(), second.id());
        service.addCardToDeck(deck.id(), first.id());
        int savesBeforeExport = storage.saveInvocations();
        KokoData dataBeforeExport = service.data();
        Deck currentDeck = service.data().findDeckById(deck.id()).orElseThrow();
        VocabularyCard currentFirst = service.data().findVocabularyCard(first.id()).orElseThrow();
        KokoData valuesBeforeExport = KokoDataSnapshots.copyOf(dataBeforeExport);
        byte[] bytesBeforeExport = Files.readAllBytes(storagePath);
        Path destination = temporaryDirectory.resolve("export with spaces.json");

        service.exportDeck(deck.id(), destination);

        assertEquals(savesBeforeExport, storage.saveInvocations());
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
        PortableDeck document = recipient.prepareImport(destination);
        Deck imported = recipient.importDeck(document, document.deckName());
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
    void nativeConfirmedExportReplacesTheSelectedFileWithoutSavingApplicationState()
            throws Exception {
        RecordingStorage storage = new RecordingStorage();
        KokoService service = new KokoService(storage, FIRST_CLOCK);
        Deck deck = service.createDeck("日本語 Basics");
        Path destination = temporaryDirectory.resolve("export.json");
        Files.writeString(destination, "sentinel", StandardCharsets.UTF_8);
        KokoData beforeExport = service.data();
        int savesBeforeExport = storage.saveInvocations;

        service.exportDeck(deck.id(),
                new koko.transfer.DeckTransfer.ConfirmedDestination(destination));

        assertEquals(savesBeforeExport, storage.saveInvocations);
        assertSame(beforeExport, service.data());
        JsonNode exported = new ObjectMapper().readTree(
                Files.readString(destination, StandardCharsets.UTF_8));
        assertEquals("日本語 Basics", exported.get("deckName").textValue());
    }

    @Test
    void configuredStorageFileAndSupportedAliasesAreProtectedAtServiceBoundary() throws Exception {
        Path libraryDirectory = temporaryDirectory.resolve("library");
        Files.createDirectory(libraryDirectory);
        Path storagePath = libraryDirectory.resolve("koko-data.json");
        JsonStorage jsonStorage = new JsonStorage(storagePath);
        jsonStorage.save(new KokoData());
        KokoService service = new KokoService(jsonStorage, FIRST_CLOCK);
        service.load();
        Deck deck = service.createDeck("Empty");
        byte[] originalBytes = Files.readAllBytes(storagePath);

        assertProtectedDestination(service, deck, storagePath);
        assertProtectedDestination(service, deck, storagePath.toAbsolutePath());

        Path hardLink = libraryDirectory.resolve("hard-link.json");
        try {
            Files.createLink(hardLink, storagePath);
            assertProtectedDestination(service, deck, hardLink);
        } catch (UnsupportedOperationException | IOException | SecurityException exception) {
            Assumptions.assumeTrue(false, "Hard links are unsupported: " + exception);
        }

        Path caseAlias = storagePath.resolveSibling("KOKO-DATA.JSON");
        if (Files.exists(caseAlias)) {
            assertTrue(Files.isSameFile(caseAlias, storagePath));
            assertProtectedDestination(service, deck, caseAlias);
        }
        assertArrayEquals(originalBytes, Files.readAllBytes(storagePath));
    }

    @Test
    void relativeConfiguredStoragePathIsProtectedAtServiceBoundary() throws Exception {
        // Keep this disposable file on the working-directory drive, including on Windows CI.
        Path relative = Files.createTempFile(Path.of("."), "koko-test-library-", ".json");
        try {
            assertFalse(relative.isAbsolute());
            KokoService service = new KokoService(new JsonStorage(relative), FIRST_CLOCK);
            Deck deck = service.createDeck("Keep this library");
            byte[] original = Files.readAllBytes(relative);

            assertProtectedDestination(service, deck, relative);
            assertProtectedDestination(service, deck, relative.toAbsolutePath());

            assertArrayEquals(original, Files.readAllBytes(relative));
        } finally {
            Files.deleteIfExists(relative);
        }
    }

    @Test
    void parentDirectoryLinkToConfiguredStorageIsProtected() throws Exception {
        Path libraryDirectory = temporaryDirectory.resolve("library");
        Files.createDirectory(libraryDirectory);
        Path storagePath = libraryDirectory.resolve("koko-data.json");
        JsonStorage jsonStorage = new JsonStorage(storagePath);
        jsonStorage.save(new KokoData());
        KokoService service = new KokoService(jsonStorage, FIRST_CLOCK);
        service.load();
        Deck deck = service.createDeck("Empty");
        Path parentAlias = temporaryDirectory.resolve("library-alias");
        try {
            Files.createSymbolicLink(parentAlias, libraryDirectory);
            assertProtectedDestination(service, deck, parentAlias.resolve("koko-data.json"));
        } catch (UnsupportedOperationException | IOException | SecurityException exception) {
            Assumptions.assumeTrue(false, "Symbolic links are unsupported: " + exception);
        }
    }

    @Test
    void configuredStorageProtectsProviderResolvedParentTraversal() throws Exception {
        Path visible = Files.createDirectory(temporaryDirectory.resolve("visible"));
        Path actualParent = Files.createDirectory(temporaryDirectory.resolve("actual"));
        Path child = Files.createDirectory(actualParent.resolve("child"));
        Path link = visible.resolve("link");
        try {
            Files.createSymbolicLink(link, child);
        } catch (UnsupportedOperationException | IOException | SecurityException exception) {
            Assumptions.assumeTrue(false, "Symbolic links are unsupported: " + exception);
        }
        Path configured = link.resolve("../library.json");
        // Unix traverses the link first; Windows resolves .. against the visible parent.
        Path actual = configured.getParent().toRealPath().resolve("library.json");
        JsonStorage storage = new JsonStorage(configured);
        KokoService service = new KokoService(storage, FIRST_CLOCK);
        Deck deck = service.createDeck("Keep this library");
        KokoData originalState = service.data();
        byte[] originalBytes = Files.readAllBytes(actual);
        assertTrue(Files.isSameFile(configured, actual));

        assertProtectedDestination(service, deck, actual);
        assertProtectedDestination(service, deck, configured);

        assertArrayEquals(originalBytes, Files.readAllBytes(actual));
        assertSame(originalState, service.data());
        assertDataEquals(originalState, storage.load());

        Files.delete(actual);
        assertProtectedDestination(service, deck, actual);
        assertTrue(Files.notExists(actual));
        assertSame(originalState, service.data());
    }

    @Test
    void storageIdentityInspectionFailureRejectsExportBeforeWriting() throws Exception {
        Assumptions.assumeTrue(temporaryDirectory.getFileSystem().supportedFileAttributeViews()
                .contains("posix"), "Requires POSIX permissions");
        Path locked = Files.createDirectory(temporaryDirectory.resolve("locked-library"));
        Path storagePath = Files.writeString(locked.resolve("library.json"), "keep");
        KokoService service = new KokoService(new JsonStorage(storagePath), FIRST_CLOCK);
        Path destination = temporaryDirectory.resolve("export.json");
        var permissions = Files.getPosixFilePermissions(locked);
        try {
            Files.setPosixFilePermissions(locked, Set.of());
            Assumptions.assumeFalse(Files.isReadable(locked), "Requires enforced directory permissions");

            assertThrows(DeckTransferException.class, () -> service.exportDeck(UUID.randomUUID(),
                    new koko.transfer.DeckTransfer.ConfirmedDestination(destination)));
        } finally {
            Files.setPosixFilePermissions(locked, permissions);
        }

        assertTrue(Files.notExists(destination));
        assertEquals("keep", Files.readString(storagePath));
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
        for (Mode mode : Mode.values()) {
            ModeProgress progress = card.progressFor(mode);
            assertEquals(0, progress.mastery());
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
        assertEquals(expected.nextDueDate(), actual.nextDueDate());
    }

    /**
     * Checks service rejection without mistaking a confirmation failure for service protection.
     *
     * <p>The confirmation is prepared outside the assertion so that a failure to capture
     * the destination surfaces as an error rather than passing for the wrong reason.
     *
     * @throws DeckTransferException if the confirmation cannot be prepared.
     */
    private static void assertProtectedDestination(KokoService service, Deck deck,
            Path destination) throws DeckTransferException {
        assertThrows(DeckTransferException.class, () -> service.exportDeck(deck.id(), destination));
        var confirmation = new DeckTransfer.ConfirmedDestination(destination);
        assertThrows(DeckTransferException.class, () -> service.exportDeck(deck.id(), confirmation));
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
            loadedData = KokoDataSnapshots.copyOf(data);
        }
    }

}
