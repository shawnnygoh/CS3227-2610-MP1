package koko.service;

import java.time.LocalDate;
import java.util.Objects;

import koko.model.ModeProgress;

/**
 * Calculates updated progress and the next review date using mastery-based
 * spaced repetition.
 *
 * <p>Returns a new immutable {@link ModeProgress} for one learning mode.
 * Does not change the supplied progress, update cards, or save data.
 * Callers decide where to apply the returned progress.
 */
public final class MasteryScheduler implements ReviewScheduler {

    private static final int MAXIMUM_MASTERY = 5;

    /**
     * Computes progress after one review outcome.
     *
     * <p>Every outcome adds one attempt; only a correct answer adds one correct
     * attempt. Correct answers increase mastery, capped at five, and schedule
     * 1, 3, 7, 14, or 30 days for resulting mastery levels one through five.
     * Incorrect answers decrease mastery, floored at zero, while skipped
     * answers preserve mastery. Both are due the following day.
     *
     * <p>All dates are based on the actual review date, which becomes the last
     * reviewed date. Overdue progress receives no automatic mastery decay.
     *
     * @param progress current progress for one learning mode.
     * @param outcome result of the review.
     * @param reviewDate actual date on which the review occurred.
     * @return a new immutable progress object.
     * @throws NullPointerException if an argument is null.
     * @throws IllegalArgumentException if incrementing the attempt count exceeds the supported integer range.
     * @throws java.time.DateTimeException if the next due date exceeds the range supported by {@link LocalDate}.
     */
    @Override
    public ModeProgress schedule(ModeProgress progress, ReviewOutcome outcome,
            LocalDate reviewDate) {
        Objects.requireNonNull(progress, "Progress cannot be null");
        Objects.requireNonNull(outcome, "Review outcome cannot be null");
        Objects.requireNonNull(reviewDate, "Review date cannot be null");

        int mastery = computeMasteryAfter(progress.mastery(), outcome);
        int attempts = progress.attempts() + 1;
        int correctAttempts = progress.correctAttempts()
                + (outcome == ReviewOutcome.CORRECT ? 1 : 0);
        LocalDate nextDueDate = reviewDate.plusDays(computeDaysUntilNextReview(mastery, outcome));
        return new ModeProgress(mastery, attempts, correctAttempts, reviewDate, nextDueDate);
    }

    /**
     * Computes the outcome's mastery change while keeping mastery between zero and five.
     * Skipped reviews preserve the current mastery.
     *
     * @param currentMastery validated mastery before the review.
     * @param outcome non-null result of the review.
     * @return the bounded mastery after applying the outcome.
     */
    private static int computeMasteryAfter(int currentMastery, ReviewOutcome outcome) {
        return switch (outcome) {
            case CORRECT -> Math.min(MAXIMUM_MASTERY, currentMastery + 1);
            case INCORRECT -> Math.max(0, currentMastery - 1);
            case SKIPPED -> currentMastery;
        };
    }

    /**
     * Computes the correct-answer interval from the resulting mastery.
     * Incorrect and skipped reviews always use a one-day interval.
     *
     * @param resultingMastery mastery after applying the outcome.
     * @param outcome non-null result of the review.
     * @return the number of days from the actual review date to the next due date.
     * @throws IllegalArgumentException if a correct answer's resulting mastery is outside one through five.
     */
    private static int computeDaysUntilNextReview(int resultingMastery, ReviewOutcome outcome) {
        if (outcome != ReviewOutcome.CORRECT) {
            return 1;
        }
        return switch (resultingMastery) {
            case 1 -> 1;
            case 2 -> 3;
            case 3 -> 7;
            case 4 -> 14;
            case 5 -> 30;
            default -> throw new IllegalArgumentException("Resulting mastery must be between 1 and 5");
        };
    }
}
