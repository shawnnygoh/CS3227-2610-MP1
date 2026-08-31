package koko.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import koko.transfer.DeckTransfer;
import koko.transfer.DeckTransferException;
import koko.transfer.PortableDeck;

/**
 * Tests the headless filename policy used by portable transfer controls.
 */
class TransferFileNamesTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void suggestionsPreserveOrdinarySpacesAndUnicode() {
        assertEquals("Animals.json", TransferFileNames.suggestExportFileName("Animals"));
        assertEquals("Japanese Basics.json",
                TransferFileNames.suggestExportFileName("Japanese Basics"));
        assertEquals("日本語.json", TransferFileNames.suggestExportFileName("日本語"));
    }

    @Test
    void suggestionsReplaceInvalidCharactersAndTrimTheStem() {
        assertEquals("Travel_Food.json",
                TransferFileNames.suggestExportFileName("Travel/Food"));
        assertEquals("Name_______.json",
                TransferFileNames.suggestExportFileName("Name:*?\"<>|"));
        assertEquals("Back_Slash.json",
                TransferFileNames.suggestExportFileName("Back\\Slash"));
        assertEquals("Name.json", TransferFileNames.suggestExportFileName("Name.  "));
        assertEquals("Backup.json", TransferFileNames.suggestExportFileName("Backup.JSON..."));
    }

    @Test
    void suggestionsUseFallbackForUnusableStems() {
        assertEquals("koko-deck.json", TransferFileNames.suggestExportFileName("..."));
        assertEquals("koko-deck.json", TransferFileNames.suggestExportFileName("///"));
        assertEquals("koko-deck.json", TransferFileNames.suggestExportFileName("\t"));
    }

    @Test
    void suggestionsProtectReservedDeviceBasenamesAndExistingSuffixes() {
        assertEquals("_CON.json", TransferFileNames.suggestExportFileName("CON"));
        assertEquals("_LPT1.txt.json", TransferFileNames.suggestExportFileName("LPT1.txt"));
        assertEquals("Backup.json", TransferFileNames.suggestExportFileName("Backup.JSON"));
        assertEquals("Backup.txt.json", TransferFileNames.suggestExportFileName("Backup.txt"));
    }

    @Test
    void chosenNamesReceiveOnlyTheJsonSuffixAndKeepTheirParent() {
        Path parent = Path.of("folder with spaces", "日本語");
        assertEquals(parent.resolve("backup.json"),
                TransferFileNames.normalizeDestination(parent.resolve("backup")));
        assertEquals(parent.resolve("backup.txt.json"),
                TransferFileNames.normalizeDestination(parent.resolve("backup.txt")));
        assertEquals(parent.resolve("backup.json"),
                TransferFileNames.normalizeDestination(parent.resolve("backup.JSON")));
        assertEquals(parent, TransferFileNames.normalizeDestination(parent.resolve("backup"))
                .getParent());
    }

    @Test
    void cancelingTheNativeChooserReturnsNoDestination() throws Exception {
        assertNull(TransferFileNames.chooseExportDestination("日本語 Basics", suggestion -> {
            assertEquals(Path.of("日本語 Basics.json"), suggestion);
            return null;
        }));
        try (var files = Files.list(temporaryDirectory)) {
            assertEquals(0, files.count());
        }
    }

    @Test
    void resolvedNativeDestinationNeedsOnlyOneChooserAndKeepsTheEmbeddedName() throws Exception {
        Path path = Files.writeString(temporaryDirectory.resolve("edited.json"), "sentinel");
        List<Path> suggestions = new ArrayList<>();
        var confirmed = TransferFileNames.chooseExportDestination("日本語 Basics", suggestion -> {
            suggestions.add(suggestion);
            return path;
        });

        assertEquals(List.of(Path.of("日本語 Basics.json")), suggestions);
        assertEquals("sentinel", Files.readString(path));
        new DeckTransfer().write(new PortableDeck(1, "日本語 Basics", List.of()), confirmed);
        assertEquals("日本語 Basics", new DeckTransfer().read(path).deckName());
    }

    @Test
    void differentExistingFinalDestinationIsPresentedToNativeChooserBeforeReplacement()
            throws Exception {
        Path raw = Files.writeString(temporaryDirectory.resolve("backup"), "raw sentinel");
        Path target = Files.writeString(temporaryDirectory.resolve("backup.json"), "final sentinel");
        List<Path> suggestions = new ArrayList<>();
        var confirmed = TransferFileNames.chooseExportDestination("Animals", suggestion -> {
            suggestions.add(suggestion);
            return suggestions.size() == 1 ? raw : target;
        });

        assertEquals(List.of(Path.of("Animals.json"), target), suggestions);
        assertEquals("final sentinel", Files.readString(target));
        new DeckTransfer().write(new PortableDeck(1, "Animals", List.of()), confirmed);
        assertEquals("Animals", new DeckTransfer().read(target).deckName());
        assertEquals("raw sentinel", Files.readString(raw));
    }

    @Test
    void cancelingTheSecondNativeChooserPreservesBothDestinations() throws Exception {
        Path raw = Files.writeString(temporaryDirectory.resolve("backup.txt"), "raw sentinel");
        Path target = Files.writeString(temporaryDirectory.resolve("backup.txt.json"), "sentinel");
        List<Path> suggestions = new ArrayList<>();
        assertNull(TransferFileNames.chooseExportDestination("Animals", suggestion -> {
            suggestions.add(suggestion);
            return suggestions.size() == 1 ? raw : null;
        }));

        assertEquals(List.of(Path.of("Animals.json"), target), suggestions);
        assertEquals("raw sentinel", Files.readString(raw));
        assertEquals("sentinel", Files.readString(target));
    }

    @Test
    void newNormalizedDestinationsNeedNoExtraChooserAndCannotUpgradeToReplacement()
            throws Exception {
        for (String name : List.of("backup", "notes.txt", "other.JSON")) {
            Path raw = temporaryDirectory.resolve(name);
            List<Path> suggestions = new ArrayList<>();
            var confirmed = TransferFileNames.chooseExportDestination("Animals", suggestion -> {
                suggestions.add(suggestion);
                return raw;
            });
            assertEquals(1, suggestions.size());
            Path target = TransferFileNames.normalizeDestination(raw);
            assertEquals(target, confirmed.path());
            Files.writeString(target, "appeared after selection");

            assertThrows(DeckTransferException.class, () -> new DeckTransfer().write(
                    new PortableDeck(1, "Animals", List.of()), confirmed));
            assertEquals("appeared after selection", Files.readString(target));
        }
    }

    @Test
    void caseInsensitiveAliasDoesNotRequireAnotherNativeChooser() throws Exception {
        Path lower = Files.writeString(temporaryDirectory.resolve("backup.json"), "sentinel");
        Path upper = lower.resolveSibling("backup.JSON");
        Assumptions.assumeTrue(Files.exists(upper) && Files.isSameFile(lower, upper),
                "Requires a case-insensitive filesystem");
        List<Path> suggestions = new ArrayList<>();
        var confirmed = TransferFileNames.chooseExportDestination("Animals", suggestion -> {
            suggestions.add(suggestion);
            return upper;
        });

        assertEquals(1, suggestions.size());
        new DeckTransfer().write(new PortableDeck(1, "Animals", List.of()), confirmed);
        assertEquals("Animals", new DeckTransfer().read(lower).deckName());
    }

    @Test
    void distinctCaseSensitiveFilesRequireTheirOwnNativeSelection() throws Exception {
        Path lower = Files.writeString(temporaryDirectory.resolve("backup.json"), "lower sentinel");
        Path upper = lower.resolveSibling("backup.JSON");
        Assumptions.assumeTrue(Files.notExists(upper), "Requires a case-sensitive filesystem");
        Files.writeString(upper, "upper sentinel");
        List<Path> suggestions = new ArrayList<>();
        var confirmed = TransferFileNames.chooseExportDestination("Animals", suggestion -> {
            suggestions.add(suggestion);
            return suggestions.size() == 1 ? upper : lower;
        });

        assertEquals(List.of(Path.of("Animals.json"), lower), suggestions);
        assertTrue(Files.isSameFile(confirmed.path(), lower));
        new DeckTransfer().write(new PortableDeck(1, "Animals", List.of()), confirmed);
        assertEquals("upper sentinel", Files.readString(upper));
    }
}
