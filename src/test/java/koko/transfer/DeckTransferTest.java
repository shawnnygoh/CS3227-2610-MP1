package koko.transfer;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.CopyOption;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Tests strict parsing and safe writing of portable deck documents.
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
    void publicValidationRejectsPortableDtosThatTheConstructorCannotFullyValidate() {
        PortableDeck invalid = new PortableDeck(2, "Animals", List.of(
                new PortableCard("ねこ", "neko", "cat")));

        assertThrows(DeckTransferException.class, () -> new DeckTransfer().validate(invalid));
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
    void nativeConfirmedReplacementReplacesOnlyTheConfirmedFinalFile() throws Exception {
        Path path = temporaryDirectory.resolve("confirmed.json");
        Files.writeString(path, "sentinel", StandardCharsets.UTF_8);
        PortableDeck document = new PortableDeck(1, "Animals", List.of());

        new DeckTransfer().write(document, new DeckTransfer.ConfirmedDestination(path));

        assertEquals(document, new DeckTransfer().read(path));
        assertFalse(Files.readString(path, StandardCharsets.UTF_8).equals("sentinel"));
    }

    @Test
    void confirmationForAnotherFinalFileIsRejectedWithoutReplacingEitherFile() throws Exception {
        Path chooserPath = temporaryDirectory.resolve("chooser.json");
        Path finalPath = temporaryDirectory.resolve("backup.json");
        byte[] chooserBytes = "chooser sentinel".getBytes(StandardCharsets.UTF_8);
        byte[] finalBytes = "final sentinel".getBytes(StandardCharsets.UTF_8);
        Files.write(chooserPath, chooserBytes);
        Files.write(finalPath, finalBytes);

        assertNull(DeckTransfer.ConfirmedDestination.fromNativeSelection(chooserPath, finalPath));

        assertArrayEquals(chooserBytes, Files.readAllBytes(chooserPath));
        assertArrayEquals(finalBytes, Files.readAllBytes(finalPath));
    }

    @Test
    void confirmedReplacementRequiresAtomicMoveAndCanBeRetried() throws Exception {
        Path path = temporaryDirectory.resolve("atomic-confirmed.json");
        Files.writeString(path, "sentinel", StandardCharsets.UTF_8);
        MoveFailure moveFailure = new MoveFailure(new AtomicMoveNotSupportedException(
                "temporary", path.toString(), "forced test failure"));
        DeckTransfer transfer = new DeckTransfer(new ObjectMapper(),
                destination -> Files.newOutputStream(destination, StandardOpenOption.CREATE_NEW,
                        StandardOpenOption.WRITE), moveFailure);
        PortableDeck document = new PortableDeck(1, "Animals", List.of());

        assertThrows(DeckTransferException.class, () -> transfer.write(document,
                new DeckTransfer.ConfirmedDestination(path)));
        assertEquals("sentinel", Files.readString(path, StandardCharsets.UTF_8));
        assertNoTemporaryFile(path);

        moveFailure.failMoves = false;
        transfer.write(document, new DeckTransfer.ConfirmedDestination(path));
        assertEquals(document, transfer.read(path));
        assertNoTemporaryFile(path);
    }

    @Test
    void changedApprovedTargetIsNotReplaced() throws Exception {
        Path path = temporaryDirectory.resolve("changed-target.json");
        Files.writeString(path, "sentinel", StandardCharsets.UTF_8);
        DeckTransfer transfer = new DeckTransfer(new ObjectMapper(), destination ->
                new FilterOutputStream(Files.newOutputStream(destination,
                        StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                    @Override
                    public void close() throws IOException {
                        super.close();
                        Files.writeString(path, "changed", StandardCharsets.UTF_8);
                    }
                }, (source, target, options) -> {
                    throw new AssertionError("changed target must not be moved");
                });

        assertThrows(DeckTransferException.class, () -> transfer.write(
                new PortableDeck(1, "Animals", List.of()),
                new DeckTransfer.ConfirmedDestination(path)));
        assertEquals("changed", Files.readString(path, StandardCharsets.UTF_8));
        assertNoTemporaryFile(path);
    }

    @Test
    void symbolicLinkDestinationIsRejectedIncludingDanglingLink() throws Exception {
        Path target = temporaryDirectory.resolve("target.json");
        Files.writeString(target, "sentinel", StandardCharsets.UTF_8);
        Path link = temporaryDirectory.resolve("link.json");
        try {
            Files.createSymbolicLink(link, target.getFileName());
            assertThrows(DeckTransferException.class, () -> new DeckTransfer().write(
                    new PortableDeck(1, "Animals", List.of()),
                    new DeckTransfer.ConfirmedDestination(link)));

            Files.delete(link);
            Path dangling = temporaryDirectory.resolve("dangling.json");
            Files.createSymbolicLink(dangling, Path.of("missing-target.json"));
            assertThrows(DeckTransferException.class, () -> new DeckTransfer().write(
                    new PortableDeck(1, "Animals", List.of()),
                    new DeckTransfer.ConfirmedDestination(dangling)));
        } catch (UnsupportedOperationException | IOException | SecurityException exception) {
            Assumptions.assumeTrue(false, "Symbolic links are unsupported: " + exception);
        }
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
        Path path = temporaryDirectory.resolve("incomplete.json");
        DeckTransfer transfer = new DeckTransfer(new ObjectMapper(), destination ->
                new FilterOutputStream(Files.newOutputStream(destination,
                        StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                @Override
                public void write(int value) throws IOException {
                    out.write(value);
                    throw new IOException("forced write failure");
                }
            });

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

    @Test
    void absentDestinationThatAppearsDuringExportIsNotReplaced() throws Exception {
        Path path = temporaryDirectory.resolve("appeared.json");
        DeckTransfer transfer = new DeckTransfer(new ObjectMapper(), destination -> {
            Files.writeString(destination, "appeared", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            throw new IOException("destination appeared after inspection");
        });

        assertThrows(DeckTransferException.class, () -> transfer.write(
                new PortableDeck(1, "Animals", List.of()), path));
        assertEquals("appeared", Files.readString(path, StandardCharsets.UTF_8));
    }

    @Test
    void destinationAppearingDuringSerializationCannotAcquireReplacementConsent() throws Exception {
        Path path = temporaryDirectory.resolve("appeared-during-serialization.json");
        var confirmation = new DeckTransfer.ConfirmedDestination(path);
        ObjectMapper mapper = new ObjectMapper() {
            @Override
            public String writeValueAsString(Object value) throws JsonProcessingException {
                try {
                    Files.writeString(path, "unconfirmed sentinel", StandardOpenOption.CREATE_NEW);
                } catch (IOException exception) {
                    throw new UncheckedIOException(exception);
                }
                return super.writeValueAsString(value);
            }
        };
        DeckTransfer transfer = new DeckTransfer(mapper, destination -> Files.newOutputStream(
                destination, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE));

        assertThrows(DeckTransferException.class, () -> transfer.write(
                new PortableDeck(1, "Animals", List.of()), confirmation));

        assertEquals("unconfirmed sentinel", Files.readString(path));
        assertNoTemporaryFile(path);
    }

    @Test
    void changedTargetDuringSerializationIsNotReplaced() throws Exception {
        Path path = Files.writeString(temporaryDirectory.resolve("serialization-change.json"), "old");
        var confirmation = new DeckTransfer.ConfirmedDestination(path);
        ObjectMapper mapper = new ObjectMapper() {
            @Override
            public String writeValueAsString(Object value) throws JsonProcessingException {
                try {
                    Files.writeString(path, "changed during serialization");
                } catch (IOException exception) {
                    throw new UncheckedIOException(exception);
                }
                return super.writeValueAsString(value);
            }
        };
        DeckTransfer transfer = new DeckTransfer(mapper, destination -> Files.newOutputStream(
                destination, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE));

        assertThrows(DeckTransferException.class, () -> transfer.write(
                new PortableDeck(1, "Animals", List.of()), confirmation));

        assertEquals("changed during serialization", Files.readString(path));
        assertNoTemporaryFile(path);
    }

    @Test
    void capturedExistingIdentityCannotBeReusedForAnotherFileWithTheSameMetadata() throws Exception {
        Path path = Files.writeString(temporaryDirectory.resolve("identity.json"), "old");
        // Size and modification time are matched deliberately below, so only a file key
        // can separate the two files. Providers without one, such as the Windows default
        // provider, cannot make this distinction and allow the replacement.
        Assumptions.assumeTrue(Files.readAttributes(path, BasicFileAttributes.class)
                .fileKey() != null, "Requires provider file keys");
        var confirmation = new DeckTransfer.ConfirmedDestination(path);
        var originalTime = Files.getLastModifiedTime(path);
        Path replacement = Files.writeString(temporaryDirectory.resolve("different-file.json"), "new");
        Files.setLastModifiedTime(replacement, originalTime);
        Files.move(replacement, path, StandardCopyOption.REPLACE_EXISTING);

        assertThrows(DeckTransferException.class, () -> new DeckTransfer().write(
                new PortableDeck(1, "Animals", List.of()), confirmation));

        assertEquals("new", Files.readString(path));
        assertNoTemporaryFile(path);
    }

    @ParameterizedTest(name = "{0} failure preserves bytes, cleans owned temps, and allows retry")
    @EnumSource(FailureStage.class)
    void replacementFailurePreservesBytesCleansOwnedTempsAndAllowsRetry(FailureStage stage)
            throws Exception {
        Path path = Files.writeString(temporaryDirectory.resolve(stage + ".json"), "sentinel");
        Path unrelated = Files.writeString(path.resolveSibling(path.getFileName() + ".tmp-other"),
                "unrelated temporary file");
        AtomicBoolean fail = new AtomicBoolean(true);
        ObjectMapper mapper = new ObjectMapper() {
            @Override
            public String writeValueAsString(Object value) throws JsonProcessingException {
                if (fail.get() && stage == FailureStage.SERIALIZATION) {
                    throw new JsonProcessingException("forced serialization failure") { };
                }
                return super.writeValueAsString(value);
            }
        };
        DeckTransfer transfer = new DeckTransfer(mapper, destination -> {
            if (fail.get() && stage == FailureStage.OPEN) {
                throw new IOException("forced open failure");
            }
            return new FilterOutputStream(Files.newOutputStream(destination,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                @Override
                public void write(int value) throws IOException {
                    out.write(value);
                    if (fail.get() && stage == FailureStage.WRITE) {
                        throw new IOException("forced write failure");
                    }
                }

                @Override
                public void close() throws IOException {
                    super.close();
                    if (fail.get() && stage == FailureStage.CLOSE) {
                        throw new IOException("forced close failure");
                    }
                }
            };
        }, (source, target, options) -> {
            if (fail.get() && stage == FailureStage.MOVE) {
                throw new IOException("forced generic move failure");
            }
            return Files.move(source, target, options);
        });
        PortableDeck document = new PortableDeck(1, "Animals", List.of());
        var confirmation = new DeckTransfer.ConfirmedDestination(path);

        assertThrows(DeckTransferException.class, () -> transfer.write(document, confirmation));
        assertEquals("sentinel", Files.readString(path));
        assertEquals("unrelated temporary file", Files.readString(unrelated));
        try (var files = Files.list(temporaryDirectory)) {
            assertEquals(1, files.filter(file -> file.getFileName().toString()
                    .startsWith(path.getFileName() + ".tmp-")).count());
        }

        fail.set(false);
        transfer.write(document, confirmation);
        assertEquals(document, transfer.read(path));
        Files.delete(unrelated);
        assertNoTemporaryFile(path);
    }

    @Test
    void failedTemporaryCreateDoesNotDeleteTheFileThatPreventedCreation() throws Exception {
        Path target = Files.writeString(temporaryDirectory.resolve("target.json"), "original");
        AtomicReference<Path> collision = new AtomicReference<>();
        DeckTransfer transfer = new DeckTransfer(new ObjectMapper(), temporary -> {
            collision.set(temporary);
            Files.writeString(temporary, "not owned by the export", StandardOpenOption.CREATE_NEW);
            return Files.newOutputStream(temporary, StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE);
        });

        assertThrows(DeckTransferException.class, () -> transfer.write(
                new PortableDeck(1, "Animals", List.of()),
                new DeckTransfer.ConfirmedDestination(target)));

        assertEquals("original", Files.readString(target));
        assertEquals("not owned by the export", Files.readString(collision.get()));
    }

    @Test
    void linkedDirectoryParentTraversalDoesNotRedirectReplacement() throws Exception {
        Path visible = Files.createDirectory(temporaryDirectory.resolve("visible"));
        Path actualParent = Files.createDirectory(temporaryDirectory.resolve("actual"));
        Path child = Files.createDirectory(actualParent.resolve("child"));
        Path link = visible.resolve("link");
        try {
            Files.createSymbolicLink(link, child);
        } catch (UnsupportedOperationException | IOException | SecurityException exception) {
            Assumptions.assumeTrue(false, "Symbolic links are unsupported: " + exception);
        }
        Path unrelated = Files.writeString(visible.resolve("export.json"), "unconfirmed sentinel");
        Path actual = Files.writeString(actualParent.resolve("export.json"), "confirmed sentinel");
        Path chosen = link.resolve("../export.json");
        Assumptions.assumeTrue(Files.isSameFile(chosen, actual),
                "Requires Unix-style symbolic-link parent traversal");
        var confirmation = new DeckTransfer.ConfirmedDestination(chosen);
        PortableDeck document = new PortableDeck(1, "Animals", List.of());

        new DeckTransfer().write(document, confirmation);

        assertEquals(document, new DeckTransfer().read(actual));
        assertEquals("unconfirmed sentinel", Files.readString(unrelated));
    }

    @Test
    void providerWithoutFileIdentityStillReplacesAConfirmedDestination() throws Exception {
        try (var fileSystem = FileSystems.newFileSystem(temporaryDirectory.resolve("provider.zip"),
                Map.of("create", "true"))) {
            Path path = fileSystem.getPath("/export.json");
            new DeckTransfer().write(new PortableDeck(1, "Animals", List.of()), path);
            Assumptions.assumeTrue(Files.readAttributes(path, BasicFileAttributes.class)
                    .fileKey() == null, "Requires a provider without file keys");
            PortableDeck replacement = new PortableDeck(1, "Plants", List.of());

            new DeckTransfer().write(replacement, new DeckTransfer.ConfirmedDestination(path));

            assertEquals(replacement, new DeckTransfer().read(path));
            assertNoTemporaryFile(path);
        }
    }

    @Test
    void providerWithoutFileIdentityAllowsSameMetadataSwapAtConfirmedPath() throws Exception {
        try (var fileSystem = FileSystems.newFileSystem(temporaryDirectory.resolve("provider.zip"),
                Map.of("create", "true"))) {
            Path path = Files.writeString(fileSystem.getPath("/export.json"), "old");
            var attributes = Files.readAttributes(path, BasicFileAttributes.class);
            Assumptions.assumeTrue(attributes.fileKey() == null, "Requires a provider without file keys");
            var confirmation = new DeckTransfer.ConfirmedDestination(path);
            Path swapped = Files.writeString(fileSystem.getPath("/other.json"), "new");
            Files.move(swapped, path, StandardCopyOption.REPLACE_EXISTING);
            Files.setLastModifiedTime(path, attributes.lastModifiedTime());
            assertEquals("new", Files.readString(path));
            assertEquals(attributes.size(), Files.size(path));
            assertEquals(attributes.lastModifiedTime(), Files.getLastModifiedTime(path));
            PortableDeck document = new PortableDeck(1, "Animals", List.of());

            // Consent applies to this path; matching metadata cannot reveal the swap without a file key.
            new DeckTransfer().write(document, confirmation);

            assertEquals(document, new DeckTransfer().read(path));
            assertNoTemporaryFile(path);
        }
    }

    @Test
    void providerWithoutFileIdentityStillDetectsAChangedTarget() throws Exception {
        try (var fileSystem = FileSystems.newFileSystem(temporaryDirectory.resolve("provider.zip"),
                Map.of("create", "true"))) {
            Path path = fileSystem.getPath("/export.json");
            new DeckTransfer().write(new PortableDeck(1, "Animals", List.of()), path);
            Assumptions.assumeTrue(Files.readAttributes(path, BasicFileAttributes.class)
                    .fileKey() == null, "Requires a provider without file keys");
            var confirmation = new DeckTransfer.ConfirmedDestination(path);
            long confirmedSize = Files.size(path);
            String changed = "changed by another writer after the destination was confirmed";
            Files.writeString(path, changed, StandardCharsets.UTF_8);
            // Zip timestamps are coarse, so size must carry detection on its own here.
            assertNotEquals(confirmedSize, Files.size(path));

            assertThrows(DeckTransferException.class, () -> new DeckTransfer()
                    .write(new PortableDeck(1, "Plants", List.of()), confirmation));

            assertEquals(changed, Files.readString(path, StandardCharsets.UTF_8));
            assertNoTemporaryFile(path);
        }
    }

    private void assertInvalid(String json) throws IOException {
        Path path = temporaryDirectory.resolve("document-" + UUID.randomUUID() + ".json");
        Files.writeString(path, json, StandardCharsets.UTF_8);
        assertThrows(DeckTransferException.class, () -> new DeckTransfer().read(path), json);
        assertEquals(json, Files.readString(path, StandardCharsets.UTF_8));
    }

    private static void assertNoTemporaryFile(Path target) throws IOException {
        String prefix = target.getFileName() + ".tmp-";
        try (var files = Files.list(target.getParent())) {
            assertTrue(files.noneMatch(file -> file.getFileName().toString().startsWith(prefix)));
        }
    }

    /** Independently reported failure stages for the replacement and retry contract. */
    private enum FailureStage {
        SERIALIZATION,
        OPEN,
        WRITE,
        CLOSE,
        MOVE
    }

    /** Deterministic move seam double for atomic replacement tests. */
    private static final class MoveFailure implements TransferMoveOperation {

        private final IOException failure;
        private boolean failMoves = true;

        private MoveFailure(IOException failure) {
            this.failure = failure;
        }

        @Override
        public Path move(Path source, Path target, CopyOption... options) throws IOException {
            assertEquals(List.of(StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING),
                    List.of(options));
            if (failMoves) {
                throw failure;
            }
            return Files.move(source, target, options);
        }
    }
}
