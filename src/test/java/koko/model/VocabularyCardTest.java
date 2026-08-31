package koko.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Tests card text boundaries, identity, and independent mode progress.
 */
class VocabularyCardTest {

    private static final LocalDate CREATION_DATE = LocalDate.of(2026, 8, 29);

    @ParameterizedTest
    @ValueSource(strings = {"\n", "\r", "\t", "\u000B", "\u2028", "\u2029"})
    void embeddedControlsAreRejectedWithoutChangingExistingText(String separator) {
        VocabularyCard card = new VocabularyCard("ねこ", "neko", "cat", CREATION_DATE);
        assertInvalidCard(IllegalArgumentException.class, "ね" + separator + "こ", "neko", "cat");
        assertInvalidCard(IllegalArgumentException.class, "ねこ", "ne" + separator + "ko", "cat");
        assertInvalidCard(IllegalArgumentException.class, "ねこ", "neko", "ca" + separator + "t");

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () ->
                card.editContent("いぬ", "inu", "do" + separator + "g"));

        assertTrue(failure.getMessage().contains("single line"));
        assertEquals("ねこ", card.hiragana());
        assertEquals("neko", card.romaji());
        assertEquals("cat", card.englishMeaning());
    }

    @Test
    void inlineSpacesAndSurroundingWhitespaceRemainValid() {
        VocabularyCard card = new VocabularyCard("\tか\u3099 こ\u3000ね\n",
                "\rga ko ne\t", "\ncat animal\r", CREATION_DATE);

        assertEquals("が こ\u3000ね", card.hiragana());
        assertEquals("ga ko ne", card.romaji());
        assertEquals("cat animal", card.englishMeaning());
    }

    @Test
    void cardRejectsNullEmptyAndWhitespaceOnlyText() {
        assertInvalidCard(NullPointerException.class, null, "neko", "cat");
        assertInvalidCard(IllegalArgumentException.class, "", "neko", "cat");
        assertInvalidCard(IllegalArgumentException.class, "   ", "neko", "cat");
        assertInvalidCard(NullPointerException.class, "ねこ", null, "cat");
        assertInvalidCard(IllegalArgumentException.class, "ねこ", "", "cat");
        assertInvalidCard(IllegalArgumentException.class, "ねこ", "   ", "cat");
        assertInvalidCard(NullPointerException.class, "ねこ", "neko", null);
        assertInvalidCard(IllegalArgumentException.class, "ねこ", "neko", "");
        assertInvalidCard(IllegalArgumentException.class, "ねこ", "neko", "   ");
    }

    @Test
    void cardTrimsTextAndNormalizesHiraganaToNfc() {
        VocabularyCard card = new VocabularyCard("  か\u3099  ", " neko ", " cat ", CREATION_DATE);

        assertEquals("が", card.hiragana());
        assertEquals("neko", card.romaji());
        assertEquals("cat", card.englishMeaning());
    }

    @Test
    void cardNormalizesDecomposedRomajiBeforeValidationAndStorage() {
        VocabularyCard card = new VocabularyCard("こーひー", "ko\u0304hi\u0304",
                "coffee", CREATION_DATE);

        assertEquals("kōhī", card.romaji());
    }

    @Test
    void identityUsesNormalizedHiraganaAndCaseInsensitiveMeaningButNotRomaji() {
        assertTrue(VocabularyCard.sameIdentity("  か\u3099  ", "Cat",
                "が", " cat "));
        assertFalse(VocabularyCard.sameIdentity("が", "cat", "が", "kitten"));
    }

    @Test
    void identityValidatesBothMeaningsEvenWhenHiraganaDiffers() {
        assertThrows(NullPointerException.class, () ->
                VocabularyCard.sameIdentity("ねこ", null, "いぬ", "dog"));
        assertThrows(NullPointerException.class, () ->
                VocabularyCard.sameIdentity("ねこ", "cat", "いぬ", null));
        assertThrows(IllegalArgumentException.class, () ->
                VocabularyCard.sameIdentity("ねこ", " ", "いぬ", "dog"));
        assertThrows(IllegalArgumentException.class, () ->
                VocabularyCard.sameIdentity("ねこ", "cat", "いぬ", " "));
    }

    @Test
    void cardValidationRejectsJsonQuoteAndBackslashCharacters() {
        assertThrows(IllegalArgumentException.class, () ->
                VocabularyCard.validateContent("ねこ", "ne\"ko", "cat"));
        assertThrows(IllegalArgumentException.class, () ->
                VocabularyCard.validateContent("ねこ", "neko", "cat\\"));
    }

    @Test
    void cardHasFreshProgressForEachMode() {
        VocabularyCard card = new VocabularyCard("ねこ", "neko", "cat", CREATION_DATE);
        ModeProgress expected = new ModeProgress(0, CREATION_DATE);

        for (Mode mode : Mode.values()) {
            assertEquals(expected, card.progressFor(mode));
        }
    }

    @ParameterizedTest
    @EnumSource(Mode.class)
    void cardProgressUpdatesDoNotAffectOtherModeWithSharedInitialProgress(Mode updatedMode) {
        ModeProgress initial = new ModeProgress(0, CREATION_DATE);
        VocabularyCard card = VocabularyCard.restore(UUID.randomUUID(), "ねこ", "neko", "cat",
                initial, initial);
        ModeProgress updated = new ModeProgress(2, CREATION_DATE.plusDays(4));

        card.updateProgress(updatedMode, updated);

        for (Mode mode : Mode.values()) {
            assertEquals(mode == updatedMode ? updated : initial, card.progressFor(mode));
        }
    }

    @Test
    void editingPreservesIdentityAndBothProgressRecords() {
        VocabularyCard card = new VocabularyCard("ねこ", "neko", "cat", CREATION_DATE);
        ModeProgress flashcard = new ModeProgress(3, CREATION_DATE.plusDays(8));
        ModeProgress typing = new ModeProgress(1, CREATION_DATE.plusDays(3));
        card.updateProgress(Mode.FLASHCARD, flashcard);
        card.updateProgress(Mode.TYPING, typing);
        var originalId = card.id();

        card.editContent("  ねこ  ", " neko2 ", " animal ");

        assertEquals(originalId, card.id());
        assertEquals("ねこ", card.hiragana());
        assertEquals("neko2", card.romaji());
        assertEquals("animal", card.englishMeaning());
        assertEquals(flashcard, card.progressFor(Mode.FLASHCARD));
        assertEquals(typing, card.progressFor(Mode.TYPING));
    }

    @Test
    void failedEditDoesNotPartiallyChangeCard() {
        VocabularyCard card = new VocabularyCard("ねこ", "neko", "cat", CREATION_DATE);

        assertThrows(IllegalArgumentException.class, () ->
                card.editContent("いぬ", "   ", "dog"));

        assertEquals("ねこ", card.hiragana());
        assertEquals("neko", card.romaji());
        assertEquals("cat", card.englishMeaning());
    }

    @Test
    void cardRejectsNullProgressArguments() {
        VocabularyCard card = new VocabularyCard("ねこ", "neko", "cat", CREATION_DATE);

        assertThrows(NullPointerException.class, () -> card.updateProgress(null,
                card.progressFor(Mode.FLASHCARD)));
        assertThrows(NullPointerException.class, () -> card.updateProgress(Mode.FLASHCARD, null));
        assertThrows(NullPointerException.class, () -> card.progressFor(null));
    }

    @Test
    void blankCardFieldsAreReportedTogether() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () ->
                new VocabularyCard("", "neko", "", CREATION_DATE));

        assertTrue(exception.getMessage().contains("Hiragana"));
        assertTrue(exception.getMessage().contains("English meaning"));
    }

    @Test
    void cardFieldsRejectTheWrongCharacterScript() {
        assertThrows(IllegalArgumentException.class, () ->
                new VocabularyCard("猫", "neko", "cat", CREATION_DATE));
        assertThrows(IllegalArgumentException.class, () ->
                new VocabularyCard("ねこ", "ネコ", "cat", CREATION_DATE));
        assertThrows(IllegalArgumentException.class, () ->
                new VocabularyCard("ねこ", "neko", "猫", CREATION_DATE));
    }

    private static void assertInvalidCard(Class<? extends Throwable> expectedException,
            String hiragana, String romaji, String meaning) {
        assertThrows(expectedException, () ->
                new VocabularyCard(hiragana, romaji, meaning, CREATION_DATE));
    }
}
