package koko.service;

import java.time.LocalDate;

import koko.model.ModeProgress;

/**
 * Calculates updated progress and the next review date for one reviewed mode.
 */
public interface ReviewScheduler {

    /**
     * Schedules one review without mutating the supplied progress.
     *
     * @param progress current progress for one learning mode.
     * @param outcome result of the review.
     * @param reviewDate actual date on which the review occurred.
     * @return a new immutable progress object containing the review result.
     * @throws NullPointerException if an argument is null.
     */
    ModeProgress schedule(ModeProgress progress, ReviewOutcome outcome, LocalDate reviewDate);
}
