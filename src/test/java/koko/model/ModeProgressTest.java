package koko.model;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
        assertEquals(CREATION_DATE, progress.nextDueDate());
        assertFalse(progress.isDueOn(CREATION_DATE.minusDays(1)));
        assertTrue(progress.isDueOn(CREATION_DATE));
    }

    @Test
    void masteryBoundariesAreAcceptedAndInvalidValuesAreRejected() {
        assertDoesNotThrow(() -> new ModeProgress(0, CREATION_DATE));
        assertDoesNotThrow(() -> new ModeProgress(5, CREATION_DATE));
        assertThrows(IllegalArgumentException.class, () ->
                new ModeProgress(-1, CREATION_DATE));
        assertThrows(IllegalArgumentException.class, () ->
                new ModeProgress(6, CREATION_DATE));
    }

    @Test
    void progressRejectsMissingCreationDueDate() {
        assertThrows(NullPointerException.class, () -> ModeProgress.forCreationDate(null));
        assertThrows(NullPointerException.class, () ->
                new ModeProgress(0, null));
        assertThrows(NullPointerException.class, () ->
                ModeProgress.forCreationDate(CREATION_DATE).isDueOn(null));
    }
}
