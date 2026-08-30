package koko.transfer;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.FilterOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Tests strict parsing and create-new writing of portable deck documents.
 */
class DeckTransferTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void roundTripSupportsJapaneseEscapesWhitespaceAndPathsWithSpaces() throws Exception {
        Path directory = temporaryDirectory.resolve("portable decks");
        Files.createDirectory(directory);
        Path path = directory.resolve("animals export.json");
        PortableDeck original = new PortableDeck(1, "動物 \uD83D\uDC08 \"\\", List.of(
                new PortableCard("ね\tこ", "neko\n", "cat\t"),
                new PortableCard("いぬ", "inu", "dog")));

        DeckTransfer transfer = new DeckTransfer();
        transfer.write(original, path);
        PortableDeck restored = transfer.read(path);

        assertEquals(original, restored);
        String json = Files.readString(path, StandardCharsets.UTF_8);
        JsonNode root = new ObjectMapper().readTree(json);
        assertEquals(3, root.size());
        assertTrue(root.get("deckName").asText().contains("\""));
        assertTrue(json.contains("\\t"));
        assertTrue(json.contains("\\n"));
    }

    @Test
    void emptyDeckWritesAnEmptyCardsArray() throws Exception {
        Path path = temporaryDirectory.resolve("empty.json");

        new DeckTransfer().write(new PortableDeck(1, "Empty", List.of()), path);

        assertEquals("Empty", new DeckTransfer().read(path).deckName());
        assertTrue(new DeckTransfer().read(path).cards().isEmpty());
    }

    @Test
    void invalidDocumentsAreRejectedAcrossShapeVersionTypesAndContent() throws IOException {
        String valid = "{\"schemaVersion\":1,\"deckName\":\"Animals\",\"cards\":[]}";
        String validCard = "{\"hiragana\":\"ねこ\",\"romaji\":\"neko\",\"englishMeaning\":\"cat\"}";
        String[] invalidDocuments = {
            "",
            " ",
            "{",
            "{}",
            "null",
            "[]",
            valid.replace("\"schemaVersion\":1,", ""),
            valid.replace("\"deckName\":\"Animals\",", ""),
            valid.replace(",\"cards\":[]", ""),
            valid.replace("\"schemaVersion\":1", "\"schemaVersion\":null"),
            valid.replace("\"schemaVersion\":1", "\"schemaVersion\":true"),
            "{\"schemaVersion\":1.0,\"deckName\":\"Animals\",\"cards\":[]}",
            "{\"schemaVersion\":\"1\",\"deckName\":\"Animals\",\"cards\":[]}",
            "{\"schemaVersion\":2,\"deckName\":\"Animals\",\"cards\":[]}",
            valid.replace("\"Animals\"", "null"),
            valid.replace("\"Animals\"", "123"),
            "{\"schemaVersion\":1,\"deckName\":\" \",\"cards\":[]}",
            valid.replace("\"cards\":[]", "\"cards\":null"),
            "{\"schemaVersion\":1,\"deckName\":\"Animals\",\"cards\":{}}",
            "{\"schemaVersion\":1,\"deckName\":\"Animals\",\"cards\":[null]}",
            "{\"schemaVersion\":1,\"deckName\":\"Animals\",\"cards\":[{"
                    + "\"hiragana\":\"ねこ\",\"romaji\":\"neko\"}]}",
            "{\"schemaVersion\":1,\"deckName\":\"Animals\",\"cards\":[{"
                    + "\"hiragana\":\"猫\",\"romaji\":\"neko\","
                    + "\"englishMeaning\":\"cat\"}]}",
            valid.replace("\"cards\":[]", "\"cards\":[],\"extra\":true"),
            valid + "{}",
            "{\"schemaVersion\":1,\"schemaVersion\":1,\"deckName\":\"Animals\","
                    + "\"cards\":[]}",
            "{\"schemaVersion\":1,\"deckName\":\"Animals\",\"cards\":[] ,"
                    + "\"decks\":[]}",
            "{\"schemaVersion\":1,\"cards\":[],\"decks\":[]}",
            valid.replace("[]", "[" + validCard.replace("\"neko\"", "123") + "]"),
            valid.replace("[]", "[" + validCard.replace("\"cat\"", "null") + "]"),
            valid.replace("[]", "[" + validCard.replace("\"cat\"", "\" \"") + "]"),
            valid.replace("[]", "[" + validCard.replace("\"neko\"", "\"ねこ\"") + "]"),
            valid.replace("[]", "[" + validCard.replace("\"cat\"", "\"猫\"") + "]"),
            valid.replace("[]", "[" + validCard.replace("\"hiragana\":",
                    "\"hiragana\":\"いぬ\",\"hiragana\":") + "]"),
            valid.replace("[]", "[" + validCard.replace("{", "{\"id\":\"not-portable\",") + "]"),
            valid.replace("[]", "[" + validCard.replace("{", "{\"progress\":{},") + "]"),
        };

        for (String invalidDocument : invalidDocuments) {
            assertInvalid(invalidDocument);
        }
    }

    @Test
    void normalizedAndRomajiOnlyDuplicatesAreRejected() throws IOException {
        String document = "{\"schemaVersion\":1,\"deckName\":\"Animals\",\"cards\":["
                + "{\"hiragana\":\" か\\u3099 \",\"romaji\":\"ga\","
                + "\"englishMeaning\":\" Cat \"},"
                + "{\"hiragana\":\"が\",\"romaji\":\"different\","
                + "\"englishMeaning\":\"cAT\"}]}";

        assertInvalid(document);
    }

    @Test
    void malformedUtf8IsRejected() throws IOException {
        Path path = temporaryDirectory.resolve("invalid-utf8.json");
        Files.write(path, new byte[] {(byte) 0xc3, (byte) 0x28});

        DeckTransferException exception = assertThrows(DeckTransferException.class, () ->
                new DeckTransfer().read(path));

        assertTrue(exception.getMessage().contains("UTF-8"));
        assertTrue(exception.getCause() != null);
    }

    @Test
    void unpairedUnicodeSurrogatesAreRejectedBeforeImportOrExport() throws Exception {
        for (String escapedName : List.of("\\uD800", "\\uDC00")) {
            assertInvalid("{\"schemaVersion\":1,\"deckName\":\"" + escapedName
                    + "\",\"cards\":[]}");
        }
        Path destination = temporaryDirectory.resolve("invalid-unicode.json");
        PortableDeck invalid = new PortableDeck(1, String.valueOf((char) 0xd800), List.of());

        assertThrows(DeckTransferException.class, () -> new DeckTransfer().write(invalid, destination));
        assertFalse(Files.exists(destination));
    }

    @Test
    void existingFileIsNeverReplaced() throws Exception {
        Path path = temporaryDirectory.resolve("sentinel.json");
        byte[] sentinel = "do not replace".getBytes(StandardCharsets.UTF_8);
        Files.write(path, sentinel);

        DeckTransferException exception = assertThrows(DeckTransferException.class, () ->
                new DeckTransfer().write(new PortableDeck(1, "Animals", List.of()), path));

        assertTrue(exception.getMessage().contains("new filename"));
        assertArrayEquals(sentinel, Files.readAllBytes(path));
    }

    @Test
    void missingOrNonFileSourcesReportIoFailureWithoutCreatingFiles() {
        Path missing = temporaryDirectory.resolve("missing.json");
        for (Path source : List.of(missing, temporaryDirectory)) {
            DeckTransferException failure = assertThrows(DeckTransferException.class, () ->
                    new DeckTransfer().read(source));
            assertTrue(failure.getCause() instanceof IOException);
        }
        assertFalse(Files.exists(missing));
    }

    @Test
    void invalidDestinationsDoNotCreateParentsOrReplaceDirectories() throws Exception {
        Path directory = Files.createDirectory(temporaryDirectory.resolve("existing directory"));
        Path sentinel = Files.writeString(directory.resolve("keep.txt"), "keep",
                StandardCharsets.UTF_8);
        Path missingParent = temporaryDirectory.resolve("missing parent");
        for (Path destination : List.of(directory, missingParent.resolve("deck.json"),
                sentinel.resolve("deck.json"))) {
            assertThrows(DeckTransferException.class, () ->
                    new DeckTransfer().write(new PortableDeck(1, "Empty", List.of()), destination));
        }
        assertTrue(Files.isDirectory(directory));
        assertEquals("keep", Files.readString(sentinel, StandardCharsets.UTF_8));
        assertFalse(Files.exists(missingParent));
    }

    @Test
    void serializationFailureOccursBeforeDestinationCreation() {
        ObjectMapper failingMapper = new ObjectMapper() {
            @Override
            public String writeValueAsString(Object value) throws JsonProcessingException {
                throw new JsonProcessingException("forced serialization failure") {
                };
            }
        };
        DeckTransfer transfer = new DeckTransfer(failingMapper, destination -> {
            throw new AssertionError("destination must not be opened");
        });
        Path path = temporaryDirectory.resolve("not-created.json");

        assertThrows(DeckTransferException.class, () ->
                transfer.write(new PortableDeck(1, "Animals", List.of()), path));

        assertFalse(Files.exists(path));
    }

    @Test
    void failedWriteCleansUpOnlyTheFileCreatedByThatAttempt() throws Exception {
        DeckTransfer transfer = new DeckTransfer(new ObjectMapper(), destination ->
                new FilterOutputStream(Files.newOutputStream(destination,
                        StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                @Override
                public void write(int value) throws IOException {
                    out.write(value);
                    throw new IOException("forced write failure");
                }
            });
        Path path = temporaryDirectory.resolve("incomplete.json");

        assertThrows(DeckTransferException.class, () ->
                transfer.write(new PortableDeck(1, "Animals", List.of()), path));

        assertFalse(Files.exists(path));
    }

    @Test
    void failedCloseIsNotReportedAsSuccessfulExport() {
        DeckTransfer transfer = new DeckTransfer(new ObjectMapper(), destination ->
                new FilterOutputStream(Files.newOutputStream(destination,
                        StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                @Override
                public void close() throws IOException {
                    super.close();
                    throw new IOException("forced close failure");
                }
            });
        Path path = temporaryDirectory.resolve("not-closed.json");

        assertThrows(DeckTransferException.class, () ->
                transfer.write(new PortableDeck(1, "Animals", List.of()), path));
        assertFalse(Files.exists(path));
    }

    private void assertInvalid(String json) throws IOException {
        Path path = temporaryDirectory.resolve("document-" + UUID.randomUUID() + ".json");
        Files.writeString(path, json, StandardCharsets.UTF_8);
        assertThrows(DeckTransferException.class, () -> new DeckTransfer().read(path), json);
        assertEquals(json, Files.readString(path, StandardCharsets.UTF_8));
    }
}
