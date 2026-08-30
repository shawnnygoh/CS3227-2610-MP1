package koko.transfer;

import java.util.Objects;

/**
 * Card text in the portable deck format, without application identity or progress.
 *
 * @param hiragana Hiragana text.
 * @param romaji romaji pronunciation.
 * @param englishMeaning English meaning.
 */
public record PortableCard(String hiragana, String romaji, String englishMeaning) {

    /**
     * Creates a portable card and rejects absent field values.
     *
     * @param hiragana Hiragana text.
     * @param romaji romaji pronunciation.
     * @param englishMeaning English meaning.
     * @throws NullPointerException if a field is null.
     */
    public PortableCard {
        Objects.requireNonNull(hiragana, "Hiragana cannot be null");
        Objects.requireNonNull(romaji, "Romaji cannot be null");
        Objects.requireNonNull(englishMeaning, "English meaning cannot be null");
    }
}
