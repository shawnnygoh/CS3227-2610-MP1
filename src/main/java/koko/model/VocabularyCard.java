package koko.model;

import java.text.Normalizer;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * A globally stored vocabulary entry with independent progress per learning mode.
 *
 * <p>Hiragana content accepts Hiragana characters, spaces, and the Japanese
 * prolonged sound mark. Romaji and English meaning accept Latin characters,
 * digits, spaces, and common punctuation. Accepted text is normalized to
 * Unicode NFC before validation and storage.
 */
public final class VocabularyCard {

    private final UUID id;
    private final Map<Mode, ModeProgress> progressByMode;
    private String hiragana;
    private String romaji;
    private String englishMeaning;

    /**
     * Creates a card with a new stable identity and fresh progress in both modes.
     *
     * @param hiragana Hiragana text stored on this card
     * @param romaji romaji pronunciation stored on this card
     * @param englishMeaning English meaning stored on this card
     * @param creationDate date used as the initial due date
     * @throws IllegalArgumentException if any text is blank or uses invalid characters
     * @throws NullPointerException if any text or creationDate is null
     */
    public VocabularyCard(String hiragana, String romaji, String englishMeaning,
            LocalDate creationDate) {
        this(UUID.randomUUID(), hiragana, romaji, englishMeaning,
                createFreshProgress(creationDate));
    }

    private VocabularyCard(UUID id, String hiragana, String romaji, String englishMeaning,
            Map<Mode, ModeProgress> progressByMode) {
        this.id = Objects.requireNonNull(id, "Card ID cannot be null");
        this.progressByMode = new EnumMap<>(progressByMode);
        editContent(hiragana, romaji, englishMeaning);
    }

    /**
     * Restores a card with its persisted identity and mode-specific progress.
     *
     * @param id stable card UUID
     * @param hiragana Hiragana text stored on this card
     * @param romaji romaji pronunciation stored on this card
     * @param englishMeaning English meaning stored on this card
     * @param flashcardProgress persisted flashcard progress
     * @param typingProgress persisted typing progress
     * @return the restored vocabulary card
     * @throws IllegalArgumentException if any text is blank or uses invalid characters
     * @throws NullPointerException if an argument is null
     */
    public static VocabularyCard restore(UUID id, String hiragana, String romaji,
            String englishMeaning, ModeProgress flashcardProgress,
            ModeProgress typingProgress) {
        Objects.requireNonNull(flashcardProgress, "Flashcard progress cannot be null");
        Objects.requireNonNull(typingProgress, "Typing progress cannot be null");
        Map<Mode, ModeProgress> progress = new EnumMap<>(Mode.class);
        progress.put(Mode.FLASHCARD, flashcardProgress);
        progress.put(Mode.TYPING, typingProgress);
        return new VocabularyCard(id, hiragana, romaji, englishMeaning, progress);
    }

    /**
     * Changes the card text while preserving identity and both progress records.
     *
     * @param newHiragana replacement Hiragana text
     * @param newRomaji replacement romaji pronunciation
     * @param newEnglishMeaning replacement English meaning
     * @throws IllegalArgumentException if any text is blank or uses invalid characters
     * @throws NullPointerException if any text is null
     */
    void editContent(String newHiragana, String newRomaji, String newEnglishMeaning) {
        validateContent(newHiragana, newRomaji, newEnglishMeaning);
        String normalizedHiragana = normalizeHiragana(newHiragana);
        String normalizedRomaji = normalizeRomaji(newRomaji);
        String normalizedEnglishMeaning = normalizeEnglishMeaning(newEnglishMeaning);

        hiragana = normalizedHiragana;
        romaji = normalizedRomaji;
        englishMeaning = normalizedEnglishMeaning;
    }

    /**
     * Validates every required text field before any card content is changed.
     *
     * @param hiragana Hiragana text to validate
     * @param romaji romaji text to validate
     * @param englishMeaning English meaning to validate
     * @throws IllegalArgumentException if a field is blank or uses invalid characters
     * @throws NullPointerException if a field is null
     */
    static void validateContent(String hiragana, String romaji, String englishMeaning) {
        Objects.requireNonNull(hiragana, "Hiragana cannot be null");
        Objects.requireNonNull(romaji, "Romaji cannot be null");
        Objects.requireNonNull(englishMeaning, "English meaning cannot be null");
        String normalizedHiragana = Normalizer.normalize(hiragana.strip(), Normalizer.Form.NFC);
        String normalizedRomaji = Normalizer.normalize(romaji.strip(), Normalizer.Form.NFC);
        String normalizedEnglishMeaning = Normalizer.normalize(englishMeaning.strip(),
                Normalizer.Form.NFC);
        List<String> errors = new ArrayList<>();
        addValidationError(errors, "Hiragana", normalizedHiragana, VocabularyCard::isHiragana);
        addValidationError(errors, "Romaji", normalizedRomaji, VocabularyCard::isLatinText);
        addValidationError(errors, "English meaning", normalizedEnglishMeaning,
                VocabularyCard::isLatinText);
        if (!errors.isEmpty()) {
            throw new IllegalArgumentException(String.join("; ", errors));
        }
    }

    private static void addValidationError(List<String> errors, String fieldName, String value,
            java.util.function.IntPredicate characterRule) {
        if (value.isEmpty()) {
            errors.add(fieldName + " cannot be blank");
        } else if (value.codePoints().anyMatch(characterRule.negate())) {
            errors.add(fieldName + " contains invalid characters");
        }
    }

    private static boolean isHiragana(int codePoint) {
        return Character.isWhitespace(codePoint)
                || Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HIRAGANA
                || codePoint == 0x30FC;
    }

    private static boolean isLatinText(int codePoint) {
        return Character.isWhitespace(codePoint)
                || Character.isDigit(codePoint)
                || (Character.isLetter(codePoint)
                        && Character.UnicodeScript.of(codePoint)
                                == Character.UnicodeScript.LATIN)
                || isCommonPunctuation(codePoint);
    }

    private static boolean isCommonPunctuation(int codePoint) {
        return switch (codePoint) {
            case '-', '\'', '\u2019', '.', ',', '!', '?', ':', ';', '/', '&', '(', ')', '+',
                    '=' -> true;
            default -> false;
        };
    }

    /**
     * Replaces one mode's complete progress with another validated immutable snapshot.
     *
     * @param mode mode whose progress is replaced
     * @param progress replacement progress
     * @throws NullPointerException if mode or progress is null
     */
    public void updateProgress(Mode mode, ModeProgress progress) {
        progressByMode.put(Objects.requireNonNull(mode, "Mode cannot be null"),
                Objects.requireNonNull(progress, "Progress cannot be null"));
    }

    public UUID id() {
        return id;
    }

    public String hiragana() {
        return hiragana;
    }

    public String romaji() {
        return romaji;
    }

    public String englishMeaning() {
        return englishMeaning;
    }

    /**
     * Returns the progress belonging to one learning mode.
     *
     * @param mode mode to inspect
     * @return the mode's progress snapshot
     * @throws NullPointerException if mode is null
     */
    public ModeProgress progressFor(Mode mode) {
        return progressByMode.get(Objects.requireNonNull(mode, "Mode cannot be null"));
    }

    private static Map<Mode, ModeProgress> createFreshProgress(LocalDate creationDate) {
        ModeProgress fresh = ModeProgress.forCreationDate(creationDate);
        Map<Mode, ModeProgress> progress = new EnumMap<>(Mode.class);
        progress.put(Mode.FLASHCARD, fresh);
        progress.put(Mode.TYPING, ModeProgress.forCreationDate(creationDate));
        return progress;
    }

    static String normalizeHiragana(String value) {
        return Normalizer.normalize(requireNonBlank(value, "Hiragana"), Normalizer.Form.NFC);
    }

    static String normalizeRomaji(String value) {
        return Normalizer.normalize(requireNonBlank(value, "Romaji"), Normalizer.Form.NFC);
    }

    static String normalizeEnglishMeaning(String value) {
        return Normalizer.normalize(requireNonBlank(value, "English meaning"),
                Normalizer.Form.NFC);
    }

    private static String requireNonBlank(String value, String fieldName) {
        Objects.requireNonNull(value, fieldName + " cannot be null");
        String trimmed = value.strip();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException(fieldName + " cannot be blank");
        }
        return trimmed;
    }
}
