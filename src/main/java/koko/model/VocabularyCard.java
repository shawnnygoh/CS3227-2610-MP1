package koko.model;

import java.text.Normalizer;
import java.time.LocalDate;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * A globally stored vocabulary entry with independent progress per learning mode.
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
     * @throws IllegalArgumentException if any text is blank
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
     * Changes the card text while preserving identity and both progress records.
     *
     * @param newHiragana replacement Hiragana text
     * @param newRomaji replacement romaji pronunciation
     * @param newEnglishMeaning replacement English meaning
     * @throws IllegalArgumentException if any text is blank
     * @throws NullPointerException if any text is null
     */
    void editContent(String newHiragana, String newRomaji, String newEnglishMeaning) {
        String normalisedHiragana = normaliseHiragana(newHiragana);
        String trimmedRomaji = requireNonBlank(newRomaji, "Romaji");
        String trimmedEnglishMeaning = requireNonBlank(newEnglishMeaning, "English meaning");

        hiragana = normalisedHiragana;
        romaji = trimmedRomaji;
        englishMeaning = trimmedEnglishMeaning;
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

    static String normaliseHiragana(String value) {
        return Normalizer.normalize(requireNonBlank(value, "Hiragana"), Normalizer.Form.NFC);
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
