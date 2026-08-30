package koko.model;

import java.time.LocalDate;
import java.util.Objects;

/**
 * Immutable progress snapshot for one learning mode.
 */
public final class ModeProgress {

    private static final int MINIMUM_MASTERY = 0;
    private static final int MAXIMUM_MASTERY = 5;

    private final int mastery;
    private final int attempts;
    private final int correctAttempts;
    private final LocalDate lastReviewedDate;
    private final LocalDate nextDueDate;

    /**
     * Creates a validated progress snapshot.
     *
     * @param mastery mastery level from zero to five, inclusive.
     * @param attempts total number of attempts.
     * @param correctAttempts number of correct attempts.
     * @param lastReviewedDate date of the latest review, or null if never reviewed.
     * @param nextDueDate date on which the mode is next due.
     * @throws IllegalArgumentException if a numeric invariant is violated.
     * @throws NullPointerException if nextDueDate is null.
     */
    public ModeProgress(int mastery, int attempts, int correctAttempts,
            LocalDate lastReviewedDate, LocalDate nextDueDate) {
        validateMastery(mastery);
        if (attempts < 0 || correctAttempts < 0 || correctAttempts > attempts) {
            throw new IllegalArgumentException(
                    "Attempts must be non-negative and correct attempts cannot exceed attempts");
        }
        this.mastery = mastery;
        this.attempts = attempts;
        this.correctAttempts = correctAttempts;
        this.lastReviewedDate = lastReviewedDate;
        this.nextDueDate = Objects.requireNonNull(nextDueDate, "Next due date cannot be null");
    }

    /**
     * Creates the initial progress for a newly created card.
     *
     * @param creationDate date on which the card is created and first due.
     * @return fresh initial progress.
     * @throws NullPointerException if creationDate is null.
     */
    public static ModeProgress forCreationDate(LocalDate creationDate) {
        return new ModeProgress(0, 0, 0, null,
                Objects.requireNonNull(creationDate, "Creation date cannot be null"));
    }

    /**
     * Returns a copy with a different validated mastery value.
     *
     * @param newMastery replacement mastery from zero to five, inclusive.
     * @return progress with the replacement mastery.
     * @throws IllegalArgumentException if newMastery is outside the valid range.
     */
    public ModeProgress withMastery(int newMastery) {
        return new ModeProgress(newMastery, attempts, correctAttempts,
                lastReviewedDate, nextDueDate);
    }

    /**
     * Returns whether this progress is due on the supplied date.
     *
     * @param date date against which due status is checked.
     * @return true when the next due date is on or before date.
     */
    public boolean isDueOn(LocalDate date) {
        return !nextDueDate.isAfter(Objects.requireNonNull(date, "Date cannot be null"));
    }

    public int mastery() {
        return mastery;
    }

    public int attempts() {
        return attempts;
    }

    public int correctAttempts() {
        return correctAttempts;
    }

    public LocalDate lastReviewedDate() {
        return lastReviewedDate;
    }

    public LocalDate nextDueDate() {
        return nextDueDate;
    }

    private static void validateMastery(int mastery) {
        if (mastery < MINIMUM_MASTERY || mastery > MAXIMUM_MASTERY) {
            throw new IllegalArgumentException("Mastery must be between 0 and 5");
        }
    }
}
