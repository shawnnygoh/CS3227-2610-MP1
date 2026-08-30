package koko.review;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Tests exact, trimmed, canonically normalized typing answers.
 */
class TypingAnswerEvaluatorTest {

    @Test
    void acceptsExactTrimmedAndNfcEquivalentHiragana() {
        assertTrue(TypingAnswerEvaluator.isCorrect("ねこ", "ねこ"));
        assertTrue(TypingAnswerEvaluator.isCorrect("  ねこ\t", "ねこ"));
        assertTrue(TypingAnswerEvaluator.isCorrect("は\u3099", "ば"));
    }

    @Test
    void rejectsWrongBlankRomajiAndExtraOrMissingCharacters() {
        assertFalse(TypingAnswerEvaluator.isCorrect("いぬ", "ねこ"));
        assertFalse(TypingAnswerEvaluator.isCorrect("   ", "ねこ"));
        assertFalse(TypingAnswerEvaluator.isCorrect("neko", "ねこ"));
        assertFalse(TypingAnswerEvaluator.isCorrect("ねこね", "ねこ"));
        assertFalse(TypingAnswerEvaluator.isCorrect("ね", "ねこ"));
        assertFalse(TypingAnswerEvaluator.isCorrect("ね こ", "ねこ"));
    }

    @Test
    void rejectsNullApiArguments() {
        assertThrows(NullPointerException.class, () ->
                TypingAnswerEvaluator.isCorrect(null, "ねこ"));
        assertThrows(NullPointerException.class, () ->
                TypingAnswerEvaluator.isCorrect("ねこ", null));
    }
}
