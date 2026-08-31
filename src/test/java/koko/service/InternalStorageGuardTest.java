package koko.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import koko.transfer.DeckTransferException;

/**
 * Tests the guard that keeps portable exports away from Koko's own storage file.
 */
class InternalStorageGuardTest {

    @TempDir
    Path directory;

    @Test
    void storageWithoutAFileTargetProtectsNothing() {
        Path anywhere = directory.resolve("koko-data.json");

        assertDoesNotThrow(() -> InternalStorageGuard.rejectAlias(Optional.empty(), anywhere));
    }

    @Test
    void exactStoragePathIsRejected() {
        Path storage = directory.resolve("koko-data.json");

        assertThrows(DeckTransferException.class, () ->
                InternalStorageGuard.rejectAlias(Optional.of(storage), storage));
    }

    @Test
    void unrelatedDestinationInTheSameDirectoryIsAllowed() {
        Path storage = directory.resolve("koko-data.json");
        Path destination = directory.resolve("exported-deck.json");

        assertDoesNotThrow(() ->
                InternalStorageGuard.rejectAlias(Optional.of(storage), destination));
    }

    @Test
    void relativeAndAbsoluteFormsOfTheStoragePathAreRejected() {
        Path storage = Path.of("data", "koko-data.json");

        assertThrows(DeckTransferException.class, () -> InternalStorageGuard.rejectAlias(
                Optional.of(storage), storage.toAbsolutePath()));
        assertThrows(DeckTransferException.class, () -> InternalStorageGuard.rejectAlias(
                Optional.of(storage.toAbsolutePath()), storage));
    }

    @Test
    void aHardLinkToStorageIsRejected() throws IOException {
        Path storage = Files.writeString(directory.resolve("koko-data.json"), "{}");
        Path alias = directory.resolve("innocent-name.json");
        try {
            Files.createLink(alias, storage);
        } catch (IOException | UnsupportedOperationException exception) {
            Assumptions.assumeTrue(false, "Hard links are unsupported: " + exception);
        }

        assertThrows(DeckTransferException.class, () ->
                InternalStorageGuard.rejectAlias(Optional.of(storage), alias));
    }

    @Test
    void aCaseAliasOfAnAbsentStorageFileIsRejected() {
        Path storage = directory.resolve("koko-data.json");
        Path alias = directory.resolve("KOKO-DATA.JSON");

        assertThrows(DeckTransferException.class, () ->
                InternalStorageGuard.rejectAlias(Optional.of(storage), alias));
    }

    @Test
    void aDestinationReachedThroughALinkedParentIsRejected() throws IOException {
        Path realDirectory = Files.createDirectory(directory.resolve("real"));
        Path storage = Files.writeString(realDirectory.resolve("koko-data.json"), "{}");
        Path linkedDirectory = directory.resolve("linked");
        try {
            Files.createSymbolicLink(linkedDirectory, realDirectory);
        } catch (IOException | UnsupportedOperationException exception) {
            Assumptions.assumeTrue(false, "Symbolic links are unsupported: " + exception);
        }

        assertThrows(DeckTransferException.class, () -> InternalStorageGuard.rejectAlias(
                Optional.of(storage), linkedDirectory.resolve("koko-data.json")));
    }
}
