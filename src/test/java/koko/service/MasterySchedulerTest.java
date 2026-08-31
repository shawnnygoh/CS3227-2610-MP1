package koko.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.DateTimeException;
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
            ModeProgress original = new ModeProgress(currentMastery, REVIEW_DATE);

            ModeProgress scheduled = scheduler.schedule(original, ReviewOutcome.CORRECT,
                    REVIEW_DATE);

            assertEquals(expectedMasteries[currentMastery], scheduled.mastery());
            assertEquals(REVIEW_DATE.plusDays(expectedIntervals[currentMastery]),
                    scheduled.nextDueDate());
        }
    }

    @Test
    void incorrectReviewsDecreaseMasteryAndScheduleTheFollowingDay() {
        int[] expectedMasteries = {0, 0, 1, 2, 3, 4};

        for (int currentMastery = 0; currentMastery <= 5; currentMastery++) {
            ModeProgress original = new ModeProgress(currentMastery, REVIEW_DATE);

            ModeProgress scheduled = scheduler.schedule(original, ReviewOutcome.INCORRECT,
                    REVIEW_DATE);

            assertEquals(expectedMasteries[currentMastery], scheduled.mastery());
            assertEquals(REVIEW_DATE.plusDays(1), scheduled.nextDueDate());
        }
    }

    @Test
    void skippedReviewsPreserveMasteryAndScheduleTheFollowingDay() {
        for (int currentMastery = 0; currentMastery <= 5; currentMastery++) {
            ModeProgress original = new ModeProgress(currentMastery, REVIEW_DATE);

            ModeProgress scheduled = scheduler.schedule(original, ReviewOutcome.SKIPPED,
                    REVIEW_DATE);

            assertEquals(currentMastery, scheduled.mastery());
            assertEquals(REVIEW_DATE.plusDays(1), scheduled.nextDueDate());
        }
    }

    @ParameterizedTest
    @CsvSource({
        "CORRECT, 1",
        "INCORRECT, 0",
        "SKIPPED, 0"
    })
    void firstReviewUpdatesFreshProgress(ReviewOutcome outcome, int expectedMastery) {
        ModeProgress original = ModeProgress.forCreationDate(REVIEW_DATE);

        ModeProgress scheduled = scheduler.schedule(original, outcome, REVIEW_DATE);

        assertEquals(expectedMastery, scheduled.mastery());
        assertEquals(REVIEW_DATE.plusDays(1), scheduled.nextDueDate());
        assertNotSame(original, scheduled);
        assertEquals(new ModeProgress(0, REVIEW_DATE), original);
    }

    @Test
    void consecutiveReviewsUseThePreviousMasteryAndCurrentReviewDate() {
        ReviewOutcome[] outcomes = {ReviewOutcome.CORRECT, ReviewOutcome.CORRECT,
            ReviewOutcome.INCORRECT, ReviewOutcome.SKIPPED, ReviewOutcome.CORRECT};
        int[] expectedMasteries = {1, 2, 1, 1, 2};
        int[] expectedIntervals = {1, 3, 1, 1, 3};
        ModeProgress progress = ModeProgress.forCreationDate(REVIEW_DATE);

        for (int reviewIndex = 0; reviewIndex < outcomes.length; reviewIndex++) {
            LocalDate reviewDate = REVIEW_DATE.plusDays(reviewIndex);
            progress = scheduler.schedule(progress, outcomes[reviewIndex], reviewDate);

            assertEquals(expectedMasteries[reviewIndex], progress.mastery());
            assertEquals(reviewDate.plusDays(expectedIntervals[reviewIndex]), progress.nextDueDate());
        }
    }

    @ParameterizedTest
    @CsvSource({
        "CORRECT, 5, 30",
        "INCORRECT, 3, 1",
        "SKIPPED, 4, 1"
    })
    void overdueReviewsUseActualReviewDateWithoutMasteryDecay(ReviewOutcome outcome,
            int expectedMastery, int expectedInterval) {
        LocalDate reviewDate = LocalDate.of(2026, 1, 10);
        ModeProgress overdue = new ModeProgress(4, reviewDate.minusDays(30));

        ModeProgress scheduled = scheduler.schedule(overdue, outcome, reviewDate);

        assertEquals(expectedMastery, scheduled.mastery());
        assertEquals(reviewDate.plusDays(expectedInterval), scheduled.nextDueDate());
    }

    @Test
    void dueDateIsInclusiveAtTheBoundaryAndDoesNotChangeProgress() {
        ModeProgress due = new ModeProgress(2, REVIEW_DATE);

        assertTrue(due.isDueOn(REVIEW_DATE));
        assertTrue(due.isDueOn(REVIEW_DATE.plusDays(1)));
        assertEquals(REVIEW_DATE, due.nextDueDate());
    }

    @Test
    void schedulingHandlesMonthYearAndLeapDayTransitions() {
        ModeProgress masteryFour = new ModeProgress(4, REVIEW_DATE);
        ModeProgress endOfYear = scheduler.schedule(masteryFour, ReviewOutcome.CORRECT,
                LocalDate.of(2026, 12, 15));
        ModeProgress leapDay = scheduler.schedule(new ModeProgress(0, REVIEW_DATE), ReviewOutcome.CORRECT,
                LocalDate.of(2028, 2, 28));
        ModeProgress afterLeapDay = scheduler.schedule(new ModeProgress(0, REVIEW_DATE),
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
    void dueDateOverflowIsRejectedWithoutChangingProgress(ReviewOutcome outcome) {
        ModeProgress original = new ModeProgress(3, REVIEW_DATE);

        assertThrows(DateTimeException.class, () ->
                scheduler.schedule(original, outcome, LocalDate.MAX));

        assertEquals(new ModeProgress(3, REVIEW_DATE), original);
    }

    @ParameterizedTest
    @EnumSource(ReviewOutcome.class)
    void schedulingDoesNotMutateTheInputProgress(ReviewOutcome outcome) {
        ModeProgress original = new ModeProgress(3, REVIEW_DATE.minusDays(1));

        ModeProgress scheduled = scheduler.schedule(original, outcome, REVIEW_DATE);

        assertNotSame(original, scheduled);
        assertEquals(3, original.mastery());
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
        assertEquals(1, scheduledFlashcard.mastery());
    }
}
