package koko.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;

import org.junit.jupiter.api.Test;

/**
 * Tests the invariants of one mode's progress snapshot.
 */
class ModeProgressTest {

    private static final LocalDate CREATION_DATE = LocalDate.of(2026, 8, 29);

    @Test
    void freshProgressHasExpectedInitialState() {
        ModeProgress progress = ModeProgress.forCreationDate(CREATION_DATE);

        assertEquals(0, progress.mastery());
        assertEquals(0, progress.attempts());
        assertEquals(0, progress.correctAttempts());
        assertNull(progress.lastReviewedDate());
        assertEquals(CREATION_DATE, progress.nextDueDate());
        assertFalse(progress.isDueOn(CREATION_DATE.minusDays(1)));
        assertTrue(progress.isDueOn(CREATION_DATE));
    }

    @Test
    void masteryBoundariesAreAcceptedAndInvalidValuesAreRejected() {
        ModeProgress progress = ModeProgress.forCreationDate(CREATION_DATE);

        assertEquals(0, progress.withMastery(0).mastery());
        assertEquals(5, progress.withMastery(5).mastery());
        assertThrows(IllegalArgumentException.class, () -> progress.withMastery(-1));
        assertThrows(IllegalArgumentException.class, () -> progress.withMastery(6));
        assertThrows(IllegalArgumentException.class, () ->
                new ModeProgress(0, 1, 2, null, CREATION_DATE));
    }

    @Test
    void progressRejectsMissingCreationDueDate() {
        assertThrows(NullPointerException.class, () -> ModeProgress.forCreationDate(null));
        assertThrows(NullPointerException.class, () ->
                new ModeProgress(0, 0, 0, null, null));
        assertThrows(NullPointerException.class, () ->
                ModeProgress.forCreationDate(CREATION_DATE).isDueOn(null));
    }
}
