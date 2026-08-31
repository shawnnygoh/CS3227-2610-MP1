package koko.model;

import java.time.LocalDate;
import java.util.Objects;

/**
 * Immutable progress snapshot for one learning mode.
 *
 * @param mastery mastery level from zero to five, inclusive.
 * @param attempts total number of attempts.
 * @param correctAttempts number of correct attempts.
 * @param lastReviewedDate date of the latest review, or null if never reviewed.
 * @param nextDueDate date on which the mode is next due.
 */
public record ModeProgress(int mastery, int attempts, int correctAttempts,
        LocalDate lastReviewedDate, LocalDate nextDueDate) {

    /** Lowest mastery level the domain allows. */
    public static final int MINIMUM_MASTERY = 0;

    /** Highest mastery level the domain allows. */
    public static final int MAXIMUM_MASTERY = 5;

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
    public ModeProgress {
        validateMastery(mastery);
        if (attempts < 0 || correctAttempts < 0 || correctAttempts > attempts) {
            throw new IllegalArgumentException(
                    "Attempts must be non-negative and correct attempts cannot exceed attempts");
        }
        Objects.requireNonNull(nextDueDate, "Next due date cannot be null");
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
     * Returns whether this progress is due on the supplied date.
     *
     * @param date date against which due status is checked.
     * @return true when the next due date is on or before date.
     */
    public boolean isDueOn(LocalDate date) {
        return !nextDueDate.isAfter(Objects.requireNonNull(date, "Date cannot be null"));
    }

    private static void validateMastery(int mastery) {
        if (mastery < MINIMUM_MASTERY || mastery > MAXIMUM_MASTERY) {
            throw new IllegalArgumentException(
                    "Mastery must be between " + MINIMUM_MASTERY + " and " + MAXIMUM_MASTERY);
        }
    }
}
