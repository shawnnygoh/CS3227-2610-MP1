package koko.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;

/**
 * Tests the headless filename policy used by portable transfer controls.
 */
class TransferFileNamesTest {

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
}
