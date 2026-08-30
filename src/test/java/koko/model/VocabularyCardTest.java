package koko.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;

import org.junit.jupiter.api.Test;

/**
 * Tests card text boundaries, identity, and independent mode progress.
 */
class VocabularyCardTest {

    private static final LocalDate CREATION_DATE = LocalDate.of(2026, 8, 29);

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
    void cardTrimsTextAndNormalisesHiraganaToNfc() {
        VocabularyCard card = new VocabularyCard("  か\u3099  ", " neko ", " cat ", CREATION_DATE);

        assertEquals("が", card.hiragana());
        assertEquals("neko", card.romaji());
        assertEquals("cat", card.englishMeaning());
    }

    @Test
    void cardNormalisesDecomposedRomajiBeforeValidationAndStorage() {
        VocabularyCard card = new VocabularyCard("こーひー", "ko\u0304hi\u0304",
                "coffee", CREATION_DATE);

        assertEquals("kōhī", card.romaji());
    }

    @Test
    void cardHasIndependentFreshProgressForEachMode() {
        VocabularyCard card = new VocabularyCard("ねこ", "neko", "cat", CREATION_DATE);

        ModeProgress flashcard = card.progressFor(Mode.FLASHCARD);
        ModeProgress typing = card.progressFor(Mode.TYPING);
        assertNotSame(flashcard, typing);
        assertEquals(0, flashcard.mastery());
        assertEquals(0, typing.attempts());
        assertEquals(CREATION_DATE, flashcard.nextDueDate());
        assertEquals(CREATION_DATE, typing.nextDueDate());
    }

    @Test
    void cardProgressUpdatesDoNotAffectTheOtherMode() {
        VocabularyCard card = new VocabularyCard("ねこ", "neko", "cat", CREATION_DATE);
        ModeProgress updatedFlashcard = new ModeProgress(2, 3, 2,
                CREATION_DATE.plusDays(1), CREATION_DATE.plusDays(4));

        card.updateProgress(Mode.FLASHCARD, updatedFlashcard);

        assertSame(updatedFlashcard, card.progressFor(Mode.FLASHCARD));
        assertEquals(0, card.progressFor(Mode.TYPING).mastery());
        assertEquals(0, card.progressFor(Mode.TYPING).attempts());
    }

    @Test
    void editingPreservesIdentityAndBothProgressRecords() {
        VocabularyCard card = new VocabularyCard("ねこ", "neko", "cat", CREATION_DATE);
        ModeProgress flashcard = new ModeProgress(3, 4, 3,
                CREATION_DATE.plusDays(1), CREATION_DATE.plusDays(8));
        ModeProgress typing = new ModeProgress(1, 2, 1,
                CREATION_DATE.plusDays(2), CREATION_DATE.plusDays(3));
        card.updateProgress(Mode.FLASHCARD, flashcard);
        card.updateProgress(Mode.TYPING, typing);
        var originalId = card.id();

        card.editContent("  ねこ  ", " neko2 ", " animal ");

        assertEquals(originalId, card.id());
        assertEquals("ねこ", card.hiragana());
        assertEquals("neko2", card.romaji());
        assertEquals("animal", card.englishMeaning());
        assertSame(flashcard, card.progressFor(Mode.FLASHCARD));
        assertSame(typing, card.progressFor(Mode.TYPING));
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
        assertNull(card.progressFor(Mode.FLASHCARD).lastReviewedDate());
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
