package koko.review;

import java.text.Normalizer;
import java.util.Objects;

/**
 * Compares a submitted typing answer with the card's expected Hiragana.
 */
public final class TypingAnswerEvaluator {

    private TypingAnswerEvaluator() {
    }

    /**
     * Returns whether an answer exactly matches after trimming and NFC normalization.
     *
     * @param enteredAnswer answer submitted by the learner.
     * @param expectedHiragana expected Hiragana stored on the card.
     * @return true only when the normalized strings are exactly equal.
     * @throws NullPointerException if either answer is null.
     */
    public static boolean isCorrect(String enteredAnswer, String expectedHiragana) {
        Objects.requireNonNull(enteredAnswer, "Entered answer cannot be null");
        Objects.requireNonNull(expectedHiragana, "Expected Hiragana cannot be null");
        return normalize(enteredAnswer).equals(normalize(expectedHiragana));
    }

    private static String normalize(String value) {
        return Normalizer.normalize(value.strip(), Normalizer.Form.NFC);
    }
}
