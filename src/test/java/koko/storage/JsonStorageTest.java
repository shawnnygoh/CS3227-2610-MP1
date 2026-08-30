package koko.storage;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import koko.model.Deck;
import koko.model.KokoData;
import koko.model.Mode;
import koko.model.ModeProgress;
import koko.model.VocabularyCard;

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
        JsonStorage storage = new JsonStorage(temporaryDirectory.resolve("missing.json"));

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
        assertNotEquals(first, loadedFirst);
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
    void duplicateJsonPropertiesAreRejected() throws IOException {
        Path path = writeDocument("{\"schemaVersion\":1,\"schemaVersion\":1,"
                + "\"cards\":[],\"decks\":[]}");

        assertThrows(StorageException.class, () -> new JsonStorage(path).load());
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
}
