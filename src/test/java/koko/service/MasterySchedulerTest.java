package koko.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

import koko.model.Mode;
import koko.model.ModeProgress;
import koko.model.VocabularyCard;

/**
 * Tests mastery-based scheduling, review outcomes, and calendar boundaries.
 */
class MasterySchedulerTest {

    private static final LocalDate REVIEW_DATE = LocalDate.of(2026, 8, 30);

    private final ReviewScheduler scheduler = new MasteryScheduler();

    @Test
    void correctReviewsIncreaseMasteryAndUseResultingMasteryIntervals() {
        int[] expectedMasteries = {1, 2, 3, 4, 5, 5};
        int[] expectedIntervals = {1, 3, 7, 14, 30, 30};

        for (int currentMastery = 0; currentMastery <= 5; currentMastery++) {
            ModeProgress original = createProgressAt(currentMastery, 10, 6);

            ModeProgress scheduled = scheduler.schedule(original, ReviewOutcome.CORRECT,
                    REVIEW_DATE);

            assertEquals(expectedMasteries[currentMastery], scheduled.mastery());
            assertEquals(11, scheduled.attempts());
            assertEquals(7, scheduled.correctAttempts());
            assertEquals(REVIEW_DATE, scheduled.lastReviewedDate());
            assertEquals(REVIEW_DATE.plusDays(expectedIntervals[currentMastery]),
                    scheduled.nextDueDate());
        }
    }

    @Test
    void incorrectReviewsDecreaseMasteryAndScheduleTheFollowingDay() {
        int[] expectedMasteries = {0, 0, 1, 2, 3, 4};

        for (int currentMastery = 0; currentMastery <= 5; currentMastery++) {
            ModeProgress original = createProgressAt(currentMastery, 10, 6);

            ModeProgress scheduled = scheduler.schedule(original, ReviewOutcome.INCORRECT,
                    REVIEW_DATE);

            assertEquals(expectedMasteries[currentMastery], scheduled.mastery());
            assertEquals(11, scheduled.attempts());
            assertEquals(6, scheduled.correctAttempts());
            assertEquals(REVIEW_DATE, scheduled.lastReviewedDate());
            assertEquals(REVIEW_DATE.plusDays(1), scheduled.nextDueDate());
        }
    }

    @Test
    void skippedReviewsPreserveMasteryAndScheduleTheFollowingDay() {
        for (int currentMastery = 0; currentMastery <= 5; currentMastery++) {
            ModeProgress original = createProgressAt(currentMastery, 10, 6);

            ModeProgress scheduled = scheduler.schedule(original, ReviewOutcome.SKIPPED,
                    REVIEW_DATE);

            assertEquals(currentMastery, scheduled.mastery());
            assertEquals(11, scheduled.attempts());
            assertEquals(6, scheduled.correctAttempts());
            assertEquals(REVIEW_DATE, scheduled.lastReviewedDate());
            assertEquals(REVIEW_DATE.plusDays(1), scheduled.nextDueDate());
        }
    }

    @ParameterizedTest
    @CsvSource({
        "CORRECT, 1, 1",
        "INCORRECT, 0, 0",
        "SKIPPED, 0, 0"
    })
    void firstReviewUpdatesFreshProgress(ReviewOutcome outcome, int expectedMastery,
            int expectedCorrectAttempts) {
        ModeProgress original = ModeProgress.forCreationDate(REVIEW_DATE);

        ModeProgress scheduled = scheduler.schedule(original, outcome, REVIEW_DATE);

        assertEquals(expectedMastery, scheduled.mastery());
        assertEquals(1, scheduled.attempts());
        assertEquals(expectedCorrectAttempts, scheduled.correctAttempts());
        assertEquals(REVIEW_DATE, scheduled.lastReviewedDate());
        assertEquals(REVIEW_DATE.plusDays(1), scheduled.nextDueDate());
        assertNotSame(original, scheduled);
        assertEquals(0, original.attempts());
        assertEquals(0, original.correctAttempts());
        assertNull(original.lastReviewedDate());
    }

    @Test
    void consecutiveReviewsAccumulateCountersAndUseThePreviousSnapshot() {
        ReviewOutcome[] outcomes = {ReviewOutcome.CORRECT, ReviewOutcome.CORRECT,
            ReviewOutcome.INCORRECT, ReviewOutcome.SKIPPED, ReviewOutcome.CORRECT};
        int[] expectedMasteries = {1, 2, 1, 1, 2};
        int[] expectedAttempts = {1, 2, 3, 4, 5};
        int[] expectedCorrectAttempts = {1, 2, 2, 2, 3};
        int[] expectedIntervals = {1, 3, 1, 1, 3};
        ModeProgress progress = ModeProgress.forCreationDate(REVIEW_DATE);

        for (int reviewIndex = 0; reviewIndex < outcomes.length; reviewIndex++) {
            LocalDate reviewDate = REVIEW_DATE.plusDays(reviewIndex);
            progress = scheduler.schedule(progress, outcomes[reviewIndex], reviewDate);

            assertEquals(expectedMasteries[reviewIndex], progress.mastery());
            assertEquals(expectedAttempts[reviewIndex], progress.attempts());
            assertEquals(expectedCorrectAttempts[reviewIndex], progress.correctAttempts());
            assertEquals(reviewDate, progress.lastReviewedDate());
            assertEquals(reviewDate.plusDays(expectedIntervals[reviewIndex]), progress.nextDueDate());
        }
    }

    @ParameterizedTest
    @CsvSource({
        "CORRECT, 5, 6, 30",
        "INCORRECT, 3, 5, 1",
        "SKIPPED, 4, 5, 1"
    })
    void overdueReviewsUseActualReviewDateWithoutMasteryDecay(ReviewOutcome outcome,
            int expectedMastery, int expectedCorrectAttempts, int expectedInterval) {
        LocalDate reviewDate = LocalDate.of(2026, 1, 10);
        ModeProgress overdue = new ModeProgress(4, 8, 5,
                reviewDate.minusDays(60), reviewDate.minusDays(30));

        ModeProgress scheduled = scheduler.schedule(overdue, outcome, reviewDate);

        assertEquals(expectedMastery, scheduled.mastery());
        assertEquals(9, scheduled.attempts());
        assertEquals(expectedCorrectAttempts, scheduled.correctAttempts());
        assertEquals(reviewDate, scheduled.lastReviewedDate());
        assertEquals(reviewDate.plusDays(expectedInterval), scheduled.nextDueDate());
    }

    @Test
    void dueDateIsInclusiveAtTheBoundaryAndDoesNotChangeProgress() {
        ModeProgress due = new ModeProgress(2, 3, 2,
                REVIEW_DATE.minusDays(3), REVIEW_DATE);

        assertTrue(due.isDueOn(REVIEW_DATE));
        assertTrue(due.isDueOn(REVIEW_DATE.plusDays(1)));
        assertEquals(REVIEW_DATE, due.nextDueDate());
    }

    @Test
    void schedulingHandlesMonthYearAndLeapDayTransitions() {
        ModeProgress masteryFour = createProgressAt(4, 0, 0);
        ModeProgress endOfYear = scheduler.schedule(masteryFour, ReviewOutcome.CORRECT,
                LocalDate.of(2026, 12, 15));
        ModeProgress leapDay = scheduler.schedule(createProgressAt(0, 0, 0), ReviewOutcome.CORRECT,
                LocalDate.of(2028, 2, 28));
        ModeProgress afterLeapDay = scheduler.schedule(createProgressAt(0, 0, 0),
                ReviewOutcome.INCORRECT, LocalDate.of(2028, 2, 29));

        assertEquals(LocalDate.of(2027, 1, 14), endOfYear.nextDueDate());
        assertEquals(LocalDate.of(2028, 2, 29), leapDay.nextDueDate());
        assertEquals(LocalDate.of(2028, 3, 1), afterLeapDay.nextDueDate());
    }

    @Test
    void nullArgumentsAreRejected() {
        ModeProgress progress = ModeProgress.forCreationDate(REVIEW_DATE);

        assertThrows(NullPointerException.class, () ->
                scheduler.schedule(null, ReviewOutcome.CORRECT, REVIEW_DATE));
        assertThrows(NullPointerException.class, () ->
                scheduler.schedule(progress, null, REVIEW_DATE));
        assertThrows(NullPointerException.class, () ->
                scheduler.schedule(progress, ReviewOutcome.CORRECT, null));
    }

    @ParameterizedTest
    @EnumSource(ReviewOutcome.class)
    void schedulingDoesNotMutateTheInputProgress(ReviewOutcome outcome) {
        ModeProgress original = new ModeProgress(3, 4, 2,
                REVIEW_DATE.minusDays(2), REVIEW_DATE.minusDays(1));

        ModeProgress scheduled = scheduler.schedule(original, outcome, REVIEW_DATE);

        assertNotSame(original, scheduled);
        assertEquals(3, original.mastery());
        assertEquals(4, original.attempts());
        assertEquals(2, original.correctAttempts());
        assertEquals(REVIEW_DATE.minusDays(2), original.lastReviewedDate());
        assertEquals(REVIEW_DATE.minusDays(1), original.nextDueDate());
    }

    @Test
    void schedulingOneCardModeCannotAffectTheOtherMode() {
        VocabularyCard card = new VocabularyCard("ねこ", "neko", "cat", REVIEW_DATE);
        ModeProgress flashcardBefore = card.progressFor(Mode.FLASHCARD);
        ModeProgress typingBefore = card.progressFor(Mode.TYPING);

        ModeProgress scheduledFlashcard = scheduler.schedule(flashcardBefore,
                ReviewOutcome.CORRECT, REVIEW_DATE);

        assertSame(flashcardBefore, card.progressFor(Mode.FLASHCARD));
        assertSame(typingBefore, card.progressFor(Mode.TYPING));
        assertEquals(0, card.progressFor(Mode.TYPING).mastery());
        assertEquals(0, card.progressFor(Mode.TYPING).attempts());
        assertEquals(1, scheduledFlashcard.mastery());
        assertNull(typingBefore.lastReviewedDate());
    }

    private static ModeProgress createProgressAt(int mastery, int attempts, int correctAttempts) {
        return new ModeProgress(mastery, attempts, correctAttempts,
                REVIEW_DATE.minusDays(1), REVIEW_DATE);
    }
}
