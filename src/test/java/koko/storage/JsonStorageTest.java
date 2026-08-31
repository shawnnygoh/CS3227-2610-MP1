package koko.storage;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.CopyOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import koko.model.Deck;
import koko.model.KokoData;
import koko.model.Mode;
import koko.model.ModeProgress;
import koko.model.VocabularyCard;
import koko.service.KokoService;
import koko.service.ReviewOutcome;

/**
 * Tests versioned JSON persistence and safe-save behavior.
 */
class JsonStorageTest {

    private static final LocalDate CREATION_DATE = LocalDate.of(2026, 8, 29);
    private static final String CARD_ID = "11111111-1111-1111-1111-111111111111";
    private static final String SECOND_CARD_ID = "22222222-2222-2222-2222-222222222222";
    private static final String DECK_ID = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    private static final String SECOND_DECK_ID = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";

    @TempDir
    Path temporaryDirectory;

    @Test
    void missingFileLoadsEmptyState() throws StorageException {
        Path path = temporaryDirectory.resolve("missing.json");
        JsonStorage storage = new JsonStorage(path);

        assertEquals(path.toAbsolutePath(), storage.configuredPath().orElseThrow());

        KokoData loaded = storage.load();

        assertTrue(loaded.vocabularyCards().isEmpty());
        assertTrue(loaded.decks().isEmpty());
    }

    @Test
    void roundTripPreservesTextProgressIdentityAndOrdering() throws StorageException {
        Path path = temporaryDirectory.resolve("koko-data.json");
        JsonStorage storage = new JsonStorage(path);
        KokoData original = new KokoData();
        VocabularyCard first = original.addVocabularyCard("ねこ", "neko", "cat", CREATION_DATE);
        VocabularyCard second = original.addVocabularyCard("いぬ", "inu", "dog", CREATION_DATE);
        first.updateProgress(Mode.FLASHCARD, new ModeProgress(4, 7, 5,
                CREATION_DATE.plusDays(1), CREATION_DATE.plusDays(15)));
        first.updateProgress(Mode.TYPING, new ModeProgress(2, 3, 1,
                CREATION_DATE.plusDays(2), CREATION_DATE.plusDays(4)));
        Deck firstDeck = original.createDeck("First");
        Deck secondDeck = original.createDeck("Second");
        original.addCardToDeck(firstDeck.id(), second.id());
        original.addCardToDeck(firstDeck.id(), first.id());
        original.addCardToDeck(secondDeck.id(), first.id());

        storage.save(original);
        KokoData loaded = storage.load();

        assertEquals(List.of(first.id(), second.id()), loaded.vocabularyCards().stream()
                .map(VocabularyCard::id).toList());
        assertEquals(List.of(firstDeck.id(), secondDeck.id()), loaded.decks().stream()
                .map(Deck::id).toList());
        VocabularyCard loadedFirst = loaded.findVocabularyCard(first.id()).orElseThrow();
        assertEquals("ねこ", loadedFirst.hiragana());
        assertEquals("neko", loadedFirst.romaji());
        assertEquals("cat", loadedFirst.englishMeaning());
        assertProgressEquals(first.progressFor(Mode.FLASHCARD),
                loadedFirst.progressFor(Mode.FLASHCARD));
        assertProgressEquals(first.progressFor(Mode.TYPING), loadedFirst.progressFor(Mode.TYPING));
        assertEquals(List.of(second.id(), first.id()), loaded.decks().get(0).cardIds());
        assertEquals(List.of(first.id()), loaded.decks().get(1).cardIds());
        assertNotSame(first, loadedFirst);
    }

    @Test
    void validSyntheticDocumentWithNullableDateRoundTrips() throws IOException, StorageException {
        Path path = writeDocument(validInternalDocument());

        KokoData loaded = new JsonStorage(path).load();

        VocabularyCard card = loaded.vocabularyCards().getFirst();
        assertEquals("neko", card.romaji());
        assertEquals("cat", card.englishMeaning());
        assertEquals(1, card.progressFor(Mode.FLASHCARD).mastery());
        assertEquals(2, card.progressFor(Mode.TYPING).attempts());
        assertNull(card.progressFor(Mode.FLASHCARD).lastReviewedDate());
        assertEquals("Core", loaded.decks().getFirst().name());
    }

    @Test
    void malformedStoredDeckNameFailsLoadingWithoutRewritingBytes() throws Exception {
        Path path = writeDocument(validInternalDocument().replace("\"name\":\"Core\"",
                "\"name\":\"\\uD800\""));
        byte[] originalBytes = Files.readAllBytes(path);

        assertThrows(StorageException.class, () -> new JsonStorage(path).load());

        assertArrayEquals(originalBytes, Files.readAllBytes(path));
    }

    @Test
    void validSupplementaryDeckNameSurvivesSaveAndLoad() throws StorageException {
        Path path = temporaryDirectory.resolve("supplementary-name.json");
        String name = "Animals \uD83D\uDC3B";
        KokoData original = new KokoData();
        original.createDeck(name);

        JsonStorage storage = new JsonStorage(path);
        storage.save(original);

        assertEquals(name, storage.load().decks().getFirst().name());
    }

    @Test
    void persistedFlashcardOutcomeRoundTripsThroughJsonStorage() throws StorageException {
        Path path = temporaryDirectory.resolve("outcome.json");
        Clock clock = Clock.fixed(Instant.parse("2026-08-29T01:00:00Z"),
                ZoneId.of("Asia/Singapore"));
        JsonStorage storage = new JsonStorage(path);
        KokoService service = new KokoService(storage, clock);
        VocabularyCard card = service.addVocabularyCard("ねこ", "neko", "cat");
        Deck deck = service.createDeck("Animals");
        service.addCardToDeck(deck.id(), card.id());

        service.recordFlashcardOutcome(card.id(), ReviewOutcome.CORRECT);

        KokoService restoredService = new KokoService(storage, clock);
        restoredService.load();
        VocabularyCard restored = restoredService.data().findVocabularyCard(card.id())
                .orElseThrow();
        ModeProgress progress = restored.progressFor(Mode.FLASHCARD);
        assertEquals(card.id(), restored.id());
        assertEquals("ねこ", restored.hiragana());
        assertEquals("neko", restored.romaji());
        assertEquals("cat", restored.englishMeaning());
        assertEquals(1, progress.mastery());
        assertEquals(1, progress.attempts());
        assertEquals(1, progress.correctAttempts());
        assertEquals(CREATION_DATE, progress.lastReviewedDate());
        assertEquals(CREATION_DATE.plusDays(1), progress.nextDueDate());
        assertEquals(List.of(deck.id()), restoredService.data().decks().stream()
                .map(Deck::id).toList());
        assertEquals(List.of(card.id()), restoredService.data().decks().get(0).cardIds());
    }

    @Test
    void roundTripNormalizesCanonicallyEquivalentRomaji() throws StorageException {
        Path path = temporaryDirectory.resolve("decomposed-romaji.json");
        JsonStorage storage = new JsonStorage(path);
        KokoData original = new KokoData();
        original.addVocabularyCard("こーひー", "ko\u0304hi\u0304", "coffee", CREATION_DATE);

        storage.save(original);
        KokoData loaded = storage.load();

        assertEquals("kōhī", loaded.vocabularyCards().get(0).romaji());
    }

    @Test
    void saveCreatesMissingParentDirectories() throws StorageException {
        Path path = temporaryDirectory.resolve("nested/data/koko-data.json");

        new JsonStorage(path).save(new KokoData());

        assertTrue(Files.isRegularFile(path));
    }

    @Test
    void initialSaveAndSuccessfulReplacementCanBeReloaded() throws StorageException {
        Path path = temporaryDirectory.resolve("replacement.json");
        JsonStorage storage = new JsonStorage(path);
        KokoData initial = new KokoData();
        initial.addVocabularyCard("ねこ", "neko", "cat", CREATION_DATE);
        KokoData replacement = new KokoData();
        VocabularyCard replacementCard = replacement.addVocabularyCard("いぬ", "inu", "dog",
                CREATION_DATE);
        Deck replacementDeck = replacement.createDeck("Animals");
        replacement.addCardToDeck(replacementDeck.id(), replacementCard.id());

        storage.save(initial);
        assertEquals(List.of(initial.vocabularyCards().getFirst().id()), storage.load().vocabularyCards().stream()
                .map(VocabularyCard::id).toList());
        storage.save(replacement);

        KokoData loaded = storage.load();
        assertEquals(List.of(replacementCard.id()), loaded.vocabularyCards().stream()
                .map(VocabularyCard::id).toList());
        assertEquals(List.of(replacementDeck.id()), loaded.decks().stream()
                .map(Deck::id).toList());
        assertEquals(List.of(replacementCard.id()), loaded.decks().get(0).cardIds());
    }

    @Test
    void malformedJsonIsRejectedAsStorageError() throws IOException {
        Path path = writeDocument("{\"schemaVersion\":1");

        assertThrows(StorageException.class, () -> new JsonStorage(path).load());
    }

    @Test
    void nullJsonDocumentIsRejectedAsStorageError() throws IOException {
        Path path = writeDocument("null");

        assertThrows(StorageException.class, () -> new JsonStorage(path).load());
    }

    @Test
    void unsupportedSchemaVersionIsRejected() throws IOException {
        Path path = writeDocument(document("99", "[]", "[]"));

        assertThrows(StorageException.class, () -> new JsonStorage(path).load());
    }

    @Test
    void duplicateCardIdentitiesAreRejected() throws IOException {
        String firstCard = cardJson(CARD_ID, 0);
        String duplicateCards = document("1", "[" + firstCard + "," + cardJson(CARD_ID, 0) + "]",
                "[]");

        assertRejected(duplicateCards);
    }

    @Test
    void invalidProgressValuesAreRejected() throws IOException {
        String invalidMastery = document("1", "[" + cardJson(CARD_ID, 6) + "]", "[]");
        String negativeAttempts = document("1", "["
                + cardJson(CARD_ID, 0, -1, 0, null, "2026-08-29") + "]", "[]");
        String tooManyCorrectAttempts = document("1", "["
                + cardJson(CARD_ID, 0, 1, 2, null, "2026-08-29") + "]", "[]");

        assertRejected(invalidMastery);
        assertRejected(negativeAttempts);
        assertRejected(tooManyCorrectAttempts);
    }

    @Test
    void danglingReferencesAreRejected() throws IOException {
        String danglingReference = document("1", "[" + cardJson(CARD_ID, 0) + "]",
                "[{\"id\":\"" + DECK_ID + "\",\"name\":\"Core\","
                        + "\"cardIds\":[\"" + SECOND_CARD_ID + "\"]}]");

        assertRejected(danglingReference);
    }

    @Test
    void duplicateMembershipAndDuplicateDeckIdentityAreRejected() throws IOException {
        String card = cardJson(CARD_ID, 0);
        String duplicateMembership = document("1", "[" + card + "]",
                "[{\"id\":\"" + DECK_ID + "\",\"name\":\"Core\",\"cardIds\":[\""
                        + CARD_ID + "\",\"" + CARD_ID + "\"]}]");
        String duplicateDecks = document("1", "[" + card + "]", "[{\"id\":\"" + DECK_ID
                + "\",\"name\":\"Core\",\"cardIds\":[]},{\"id\":\"" + DECK_ID
                + "\",\"name\":\"Other\",\"cardIds\":[]}]");

        assertRejected(duplicateMembership);
        assertRejected(duplicateDecks);
    }

    @Test
    void duplicateCardContentAndDeckNamesAreRejected() throws IOException {
        String duplicateContent = document("1", "[" + cardJson(CARD_ID, 0) + ","
                + cardJson(SECOND_CARD_ID, 0) + "]", "[]");
        String duplicateNames = document("1", "[" + cardJson(CARD_ID, 0) + "]",
                "[{\"id\":\"" + DECK_ID + "\",\"name\":\"Core\",\"cardIds\":[]},"
                        + "{\"id\":\"" + SECOND_DECK_ID
                        + "\",\"name\":\" core \",\"cardIds\":[]}]");

        assertRejected(duplicateContent);
        assertRejected(duplicateNames);
    }

    @Test
    void missingFieldsUnknownFieldsAndTrailingJsonAreRejected() throws IOException {
        String missingCards = "{\"schemaVersion\":1,\"decks\":[]}";
        String missingModeProgress = "{\"schemaVersion\":1,\"cards\":[{"
                + "\"id\":\"" + CARD_ID + "\",\"hiragana\":\"ねこ\","
                + "\"romaji\":\"neko\",\"englishMeaning\":\"cat\","
                + "\"progress\":{}}],\"decks\":[]}";
        String unknownField = document("1", "[]", "[]").replace("\"decks\"", "\"extra\":true,\"decks\"");
        String trailingJson = document("1", "[]", "[]") + "{}";

        assertRejected(missingCards);
        assertRejected(missingModeProgress);
        assertRejected(unknownField);
        assertRejected(trailingJson);
    }

    @Test
    void malformedIdentifiersAndDatesAreRejected() throws IOException {
        String badCardId = document("1", "[" + cardJson("not-a-uuid", 0) + "]", "[]");
        String badDate = document("1", "["
                + cardJson(CARD_ID, 0, 0, 0, "not-a-date", "2026-08-29") + "]", "[]");

        assertRejected(badCardId);
        assertRejected(badDate);
    }

    @Test
    void schemaOneDataWithOutOfPolicyCharactersIsRejected() throws IOException {
        String invalidRomaji = cardJson(CARD_ID, 0).replace(
                "\"romaji\":\"neko\"", "\"romaji\":\"ねこ\"");
        assertRejected(document("1", "[" + invalidRomaji + "]", "[]"));
    }

    @Test
    void failedSaveLeavesExistingValidFileUnchanged() throws IOException, StorageException {
        Path path = temporaryDirectory.resolve("koko-data.json");
        JsonStorage storage = new JsonStorage(path);
        storage.save(new KokoData());
        byte[] originalBytes = Files.readAllBytes(path);
        ObjectMapper failingMapper = new ObjectMapper() {
            @Override
            public String writeValueAsString(Object value) throws JsonProcessingException {
                throw new JsonProcessingException("forced test failure") {
                };
            }
        };

        JsonStorage failingStorage = new JsonStorage(path, failingMapper);

        assertThrows(StorageException.class, () -> failingStorage.save(new KokoData()));
        assertTrue(Files.exists(path));
        assertArrayEquals(originalBytes, Files.readAllBytes(path));
    }

    @Test
    void unsupportedAtomicReplacementDoesNotFallbackOrChangeExistingFile()
            throws IOException, StorageException {
        Path path = temporaryDirectory.resolve("atomic-unsupported.json");
        JsonStorage initialStorage = new JsonStorage(path);
        initialStorage.save(new KokoData());
        byte[] originalBytes = Files.readAllBytes(path);
        AtomicMoveNotSupportedException unsupported = new AtomicMoveNotSupportedException(
                "temporary", path.toString(), "forced test failure");
        MoveFailure moveFailure = new MoveFailure(unsupported);
        JsonStorage storage = new JsonStorage(path, new ObjectMapper(), moveFailure);
        KokoData replacement = new KokoData();
        VocabularyCard card = replacement.addVocabularyCard("ねこ", "neko", "cat", CREATION_DATE);

        StorageException failure = assertThrows(StorageException.class, () ->
                storage.save(replacement));

        assertTrue(failure.getMessage().contains("atomic replacement is not supported"));
        assertSame(unsupported, failure.getCause());
        assertEquals(1, moveFailure.invocationCount);
        assertArrayEquals(originalBytes, Files.readAllBytes(path));
        assertTrue(storage.load().vocabularyCards().isEmpty());
        assertTrue(storage.load().decks().isEmpty());
        assertNoTemporaryFile(path);

        moveFailure.failMoves = false;
        storage.save(replacement);

        assertEquals(List.of(card.id()), storage.load().vocabularyCards().stream()
                .map(VocabularyCard::id).toList());
        assertEquals(2, moveFailure.invocationCount);
        assertNoTemporaryFile(path);
    }

    @Test
    void replacementIoFailurePreservesTargetCleansUpAndCanBeRetried()
            throws IOException, StorageException {
        Path path = temporaryDirectory.resolve("replacement-failure.json");
        JsonStorage initialStorage = new JsonStorage(path);
        KokoData initial = new KokoData();
        initial.addVocabularyCard("ねこ", "neko", "cat", CREATION_DATE);
        initialStorage.save(initial);
        byte[] originalBytes = Files.readAllBytes(path);
        MoveFailure moveFailure = new MoveFailure(new IOException("forced test failure"));
        JsonStorage storage = new JsonStorage(path, new ObjectMapper(), moveFailure);
        KokoData replacement = new KokoData();
        replacement.addVocabularyCard("いぬ", "inu", "dog", CREATION_DATE);

        StorageException failure = assertThrows(StorageException.class, () ->
                storage.save(replacement));

        assertSame(moveFailure.failure, failure.getCause());
        assertEquals(1, moveFailure.invocationCount);
        assertArrayEquals(originalBytes, Files.readAllBytes(path));
        assertEquals("ねこ", storage.load().vocabularyCards().get(0).hiragana());
        assertNoTemporaryFile(path);

        moveFailure.failMoves = false;
        storage.save(replacement);

        assertEquals("いぬ", storage.load().vocabularyCards().get(0).hiragana());
        assertEquals(2, moveFailure.invocationCount);
        assertNoTemporaryFile(path);
    }

    @Test
    void failedReplacementLeavesAbsentTargetAbsent() throws IOException, StorageException {
        Path path = temporaryDirectory.resolve("absent-target.json");
        MoveFailure moveFailure = new MoveFailure(new AtomicMoveNotSupportedException(
                "temporary", path.toString(), "forced test failure"));
        JsonStorage storage = new JsonStorage(path, new ObjectMapper(), moveFailure);

        assertThrows(StorageException.class, () -> storage.save(new KokoData()));

        assertTrue(Files.notExists(path));
        assertNoTemporaryFile(path);
    }

    @Test
    void failedImportAtReplacementPublishesNothingAndRetryCreatesOneDeck()
            throws Exception {
        Path path = temporaryDirectory.resolve("import-replacement-failure.json");
        new JsonStorage(path).save(new KokoData());
        byte[] originalBytes = Files.readAllBytes(path);
        MoveFailure moveFailure = new MoveFailure(new IOException("forced test failure"));
        JsonStorage storage = new JsonStorage(path, new ObjectMapper(), moveFailure);
        Clock clock = Clock.fixed(Instant.parse("2026-08-29T01:00:00Z"), ZoneId.of("Asia/Singapore"));
        KokoService service = new KokoService(storage, clock);
        service.load();
        KokoData originalData = service.data();
        Path source = writeDocument("{\"schemaVersion\":1,\"deckName\":\"Imported\","
                + "\"cards\":[{\"hiragana\":\"ねこ\",\"romaji\":\"neko\","
                + "\"englishMeaning\":\"cat\"}]}");

        assertThrows(StorageException.class, () -> service.importDeck(source));

        assertSame(originalData, service.data());
        assertTrue(service.data().decks().isEmpty());
        assertTrue(service.data().vocabularyCards().isEmpty());
        assertArrayEquals(originalBytes, Files.readAllBytes(path));
        assertEquals(1, moveFailure.invocationCount);
        assertNoTemporaryFile(path);

        moveFailure.failMoves = false;
        Deck imported = service.importDeck(source);

        assertEquals(1, service.data().decks().size());
        assertSame(imported, service.data().findDeckById(imported.id()).orElseThrow());
        assertEquals(1, imported.cardIds().size());
        assertEquals(imported.cardIds(), service.data().vocabularyCards().stream()
                .map(VocabularyCard::id).toList());
        assertEquals(2, moveFailure.invocationCount);
        KokoData reloaded = storage.load();
        assertEquals(List.of(imported.id()), reloaded.decks().stream().map(Deck::id).toList());
        assertEquals(imported.cardIds(), reloaded.decks().getFirst().cardIds());
        assertEquals(imported.cardIds(), reloaded.vocabularyCards().stream()
                .map(VocabularyCard::id).toList());
        assertNoTemporaryFile(path);
    }

    @Test
    void duplicateJsonPropertiesAreRejected() throws IOException {
        Path path = writeDocument("{\"schemaVersion\":1,\"schemaVersion\":1,"
                + "\"cards\":[],\"decks\":[]}");

        assertThrows(StorageException.class, () -> new JsonStorage(path).load());
    }

    @ParameterizedTest(name = "rejects invalid {0} JSON type")
    @MethodSource("invalidJsonTypes")
    void invalidJsonTypesAreRejectedWithoutChangingFileOrServiceState(
            String field, String invalidJson) throws Exception {
        Path path = temporaryDirectory.resolve(field + ".json");
        JsonStorage storage = new JsonStorage(path);
        KokoData initial = new KokoData();
        VocabularyCard card = initial.addVocabularyCard("ねこ", "neko", "cat", CREATION_DATE);
        Deck deck = initial.createDeck("Core");
        initial.addCardToDeck(deck.id(), card.id());
        storage.save(initial);

        KokoService service = new KokoService(storage, Clock.systemUTC());
        service.load();
        KokoData preservedState = service.data();
        Files.writeString(path, invalidJson, StandardCharsets.UTF_8);
        byte[] invalidBytes = Files.readAllBytes(path);

        assertThrows(StorageException.class, service::load);

        assertSame(preservedState, service.data());
        assertEquals(List.of(card.id()), service.data().vocabularyCards().stream()
                .map(VocabularyCard::id).toList());
        assertEquals(List.of(deck.id()), service.data().decks().stream().map(Deck::id).toList());
        assertArrayEquals(invalidBytes, Files.readAllBytes(path));
    }

    private static Stream<Arguments> invalidJsonTypes() {
        String valid = validInternalDocument();
        return Stream.of(
                Arguments.of("schemaVersion", replace(valid, "\"schemaVersion\":1",
                        "\"schemaVersion\":1.9")),
                Arguments.of("flashcardMastery", replace(valid,
                        "\"FLASHCARD\":{\"mastery\":1", "\"FLASHCARD\":{\"mastery\":5.9")),
                Arguments.of("typingMastery", replace(valid,
                        "\"TYPING\":{\"mastery\":2", "\"TYPING\":{\"mastery\":2.9")),
                Arguments.of("flashcardAttempts", replace(valid,
                        "\"FLASHCARD\":{\"mastery\":1,\"attempts\":2",
                        "\"FLASHCARD\":{\"mastery\":1,\"attempts\":0.9")),
                Arguments.of("typingAttempts", replace(valid,
                        "\"TYPING\":{\"mastery\":2,\"attempts\":2",
                        "\"TYPING\":{\"mastery\":2,\"attempts\":2.9")),
                Arguments.of("flashcardCorrectAttempts", replace(valid,
                        "\"FLASHCARD\":{\"mastery\":1,\"attempts\":2,\"correctAttempts\":1",
                        "\"FLASHCARD\":{\"mastery\":1,\"attempts\":2,\"correctAttempts\":1.9")),
                Arguments.of("typingCorrectAttempts", replace(valid,
                        "\"TYPING\":{\"mastery\":2,\"attempts\":2,\"correctAttempts\":1",
                        "\"TYPING\":{\"mastery\":2,\"attempts\":2,\"correctAttempts\":1.9")),
                Arguments.of("cardId", replace(valid, "\"id\":\"" + CARD_ID + "\"",
                        "\"id\":123")),
                Arguments.of("hiragana", replace(valid, "\"hiragana\":\"ねこ\"",
                        "\"hiragana\":123")),
                Arguments.of("romaji", replace(valid, "\"romaji\":\"neko\"",
                        "\"romaji\":123")),
                Arguments.of("englishMeaning", replace(valid, "\"englishMeaning\":\"cat\"",
                        "\"englishMeaning\":true")),
                Arguments.of("lastReviewedDate", replace(valid, "\"lastReviewedDate\":null",
                        "\"lastReviewedDate\":123")),
                Arguments.of("nextDueDate", replace(valid, "\"nextDueDate\":\"2026-08-29\"",
                        "\"nextDueDate\":false")),
                Arguments.of("deckId", replace(valid, "\"id\":\"" + DECK_ID + "\"",
                        "\"id\":true")),
                Arguments.of("deckName", replace(valid, "\"name\":\"Core\"",
                        "\"name\":123")),
                Arguments.of("deckCardId", replace(valid, "\"cardIds\":[\"" + CARD_ID + "\"]",
                        "\"cardIds\":[123]")));
    }

    private void assertRejected(String json) throws IOException {
        Path path = writeDocument(json);

        assertThrows(StorageException.class, () -> new JsonStorage(path).load());
    }

    private Path writeDocument(String json) throws IOException {
        Path path = temporaryDirectory.resolve(UUID.randomUUID() + ".json");
        Files.writeString(path, json, StandardCharsets.UTF_8);
        return path;
    }

    private static String document(String version, String cards, String decks) {
        return "{\"schemaVersion\":" + version + ",\"cards\":" + cards
                + ",\"decks\":" + decks + "}";
    }

    private static String validInternalDocument() {
        String flashcardProgress = "\"mastery\":1,\"attempts\":2,"
                + "\"correctAttempts\":1,\"lastReviewedDate\":null,"
                + "\"nextDueDate\":\"2026-08-29\"";
        String typingProgress = "\"mastery\":2,\"attempts\":2,"
                + "\"correctAttempts\":1,\"lastReviewedDate\":\"2026-08-29\","
                + "\"nextDueDate\":\"2026-08-31\"";
        String card = "{\"id\":\"" + CARD_ID + "\",\"hiragana\":\"ねこ\","
                + "\"romaji\":\"neko\",\"englishMeaning\":\"cat\","
                + "\"progress\":{\"FLASHCARD\":{" + flashcardProgress + "},"
                + "\"TYPING\":{" + typingProgress + "}}}";
        String deck = "{\"id\":\"" + DECK_ID + "\",\"name\":\"Core\","
                + "\"cardIds\":[\"" + CARD_ID + "\"]}";
        return document("1", "[" + card + "]", "[" + deck + "]");
    }

    private static String replace(String source, String target, String replacement) {
        String result = source.replace(target, replacement);
        if (result.equals(source)) {
            throw new IllegalStateException("Test fixture target was not found: " + target);
        }
        return result;
    }

    private static String cardJson(String id, int mastery) {
        return cardJson(id, mastery, 2, 1, null, "2026-08-29");
    }

    private static String cardJson(String id, int mastery, int attempts, int correctAttempts,
            String lastReviewedDate, String nextDueDate) {
        String progress = "\"mastery\":" + mastery + ",\"attempts\":" + attempts
                + ",\"correctAttempts\":" + correctAttempts
                + ",\"lastReviewedDate\":" + jsonStringOrNull(lastReviewedDate)
                + ",\"nextDueDate\":" + jsonStringOrNull(nextDueDate);
        return "{\"id\":\"" + id + "\",\"hiragana\":\"ねこ\",\"romaji\":\"neko\","
                + "\"englishMeaning\":\"cat\",\"progress\":{\"FLASHCARD\":{"
                + progress + "},\"TYPING\":{" + progress + "}}}";
    }

    private static String jsonStringOrNull(String value) {
        return value == null ? "null" : "\"" + value + "\"";
    }

    private static void assertProgressEquals(ModeProgress expected, ModeProgress actual) {
        assertEquals(expected.mastery(), actual.mastery());
        assertEquals(expected.attempts(), actual.attempts());
        assertEquals(expected.correctAttempts(), actual.correctAttempts());
        assertEquals(expected.lastReviewedDate(), actual.lastReviewedDate());
        assertEquals(expected.nextDueDate(), actual.nextDueDate());
    }

    private static void assertNoTemporaryFile(Path target) throws IOException {
        String prefix = target.getFileName() + ".tmp-";
        try (var files = Files.list(target.getParent())) {
            assertTrue(files.noneMatch(file -> file.getFileName().toString().startsWith(prefix)));
        }
    }

    /** Deterministic move seam double that can fail before replacement. */
    private static final class MoveFailure implements JsonStorage.MoveOperation {

        private final IOException failure;
        private int invocationCount;
        private boolean failMoves = true;

        private MoveFailure(IOException failure) {
            this.failure = failure;
        }

        @Override
        public Path move(Path source, Path target, CopyOption... options) throws IOException {
            invocationCount++;
            assertEquals(List.of(StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING),
                    List.of(options));
            if (failMoves) {
                throw failure;
            }
            return Files.move(source, target, options);
        }
    }
}
