package koko.review;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.InstantSource;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import koko.model.Deck;
import koko.model.KokoData;
import koko.model.Mode;
import koko.model.ModeProgress;
import koko.model.VocabularyCard;
import koko.service.KokoService;
import koko.service.ReviewOutcome;
import koko.storage.JsonStorage;
import koko.storage.Storage;
import koko.storage.StorageException;

/**
 * Tests headless flashcard session coordination and its persistence boundary.
 */
class FlashcardSessionTest {

    private static final LocalDate START_DATE = LocalDate.of(2026, 8, 30);
    private static final Clock FIXED_CLOCK = Clock.fixed(
            Instant.parse("2026-08-30T01:00:00Z"), ZoneId.of("Asia/Singapore"));

    @Test
    void factoriesRejectUnknownAndNullSelectionsWithoutSaving() {
        TestContext context = new TestContext();
        UUID missingId = UUID.randomUUID();
        KokoData originalData = context.service.data();

        assertThrows(IllegalArgumentException.class, () ->
                FlashcardSession.forCard(context.service, missingId));
        assertThrows(IllegalArgumentException.class, () ->
                FlashcardSession.forDeck(context.service, missingId, FIXED_CLOCK));
        assertThrows(NullPointerException.class, () ->
                FlashcardSession.forCard(null, missingId));
        assertThrows(NullPointerException.class, () ->
                FlashcardSession.forDeck(null, missingId, FIXED_CLOCK));
        assertThrows(NullPointerException.class, () ->
                FlashcardSession.forCard(context.service, null));
        assertThrows(NullPointerException.class, () ->
                FlashcardSession.forDeck(context.service, null, FIXED_CLOCK));
        assertThrows(NullPointerException.class, () ->
                FlashcardSession.forDeck(context.service, missingId, null));
        assertThrows(NullPointerException.class, () ->
                FlashcardSession.forAllCardsInDeck(null, missingId));
        assertThrows(NullPointerException.class, () ->
                FlashcardSession.forAllCardsInDeck(context.service, null));
        assertThrows(IllegalArgumentException.class, () ->
                FlashcardSession.forAllCardsInDeck(context.service, missingId));

        assertSame(originalData, context.service.data());
        assertEquals(0, context.storage.saveInvocations);
    }

    @Test
    void emptyDeckStartsCompletedWithoutBeingStopped() throws StorageException {
        TestContext context = new TestContext();
        Deck deck = context.service.createDeck("Empty");

        FlashcardSession session = FlashcardSession.forDeck(
                context.service, deck.id(), FIXED_CLOCK);

        assertEquals(FlashcardSession.State.COMPLETED, session.state());
        assertEquals(new FlashcardSession.Summary(0, 0, 0, 0, 0, false), session.summary());
        assertTrue(session.currentPrompt().isEmpty());
    }

    @Test
    void allCardsFollowMembershipOrderAndIncludeFutureCardsAfterIncorrect()
            throws StorageException {
        TestContext context = new TestContext();
        VocabularyCard libraryFirst = context.addCard("あ", "a", "first");
        VocabularyCard librarySecond = context.addCard("い", "i", "second");
        VocabularyCard libraryThird = context.addCard("う", "u", "third");
        setDueDate(context.service, libraryFirst.id(), START_DATE.plusDays(5));
        setDueDate(context.service, librarySecond.id(), START_DATE.minusDays(1));
        setDueDate(context.service, libraryThird.id(), START_DATE);
        Deck deck = context.service.createDeck("Core");
        addToDeck(context.service, deck, libraryThird, libraryFirst, librarySecond);
        int savesBeforeSession = context.storage.saveInvocations;

        FlashcardSession session = FlashcardSession.forAllCardsInDeck(
                context.service, deck.id());
        List<UUID> order = new ArrayList<>();
        while (session.state() == FlashcardSession.State.PROMPT) {
            UUID cardId = session.currentCardId().orElseThrow();
            order.add(cardId);
            session.reveal(cardId);
            session.submit(cardId, cardId.equals(libraryFirst.id())
                    ? ReviewOutcome.INCORRECT : ReviewOutcome.CORRECT);
        }

        assertEquals(List.of(libraryThird.id(), libraryFirst.id(), librarySecond.id()), order);
        assertEquals(new FlashcardSession.Summary(3, 2, 1, 3, 0, false), session.summary());
        assertEquals(3, context.storage.saveInvocations - savesBeforeSession);
        assertEquals(3, context.storage.successfulSaveCount - savesBeforeSession);
    }

    @Test
    void allCardsIncludeFutureOnlyCardsAndEmptyDecksCompleteWithoutSaving()
            throws StorageException {
        TestContext context = new TestContext();
        VocabularyCard future = context.addCard("み", "mi", "future");
        setDueDate(context.service, future.id(), START_DATE.plusDays(10));
        Deck futureDeck = context.service.createDeck("Future");
        context.service.addCardToDeck(futureDeck.id(), future.id());
        FlashcardSession futureSession = FlashcardSession.forAllCardsInDeck(
                context.service, futureDeck.id());
        assertEquals(FlashcardSession.State.PROMPT, futureSession.state());
        futureSession.reveal(future.id());
        futureSession.submit(future.id(), ReviewOutcome.CORRECT);
        assertEquals(FlashcardSession.State.COMPLETED, futureSession.state());

        Deck emptyDeck = context.service.createDeck("Empty");
        int savesBeforeEmptySession = context.storage.saveInvocations;
        FlashcardSession emptySession = FlashcardSession.forAllCardsInDeck(
                context.service, emptyDeck.id());

        assertEquals(FlashcardSession.State.COMPLETED, emptySession.state());
        assertEquals(new FlashcardSession.Summary(0, 0, 0, 0, 0, false), emptySession.summary());
        assertFalse(emptySession.stopped());
        assertTrue(emptySession.currentPrompt().isEmpty());
        assertEquals(savesBeforeEmptySession, context.storage.saveInvocations);
        assertEquals(savesBeforeEmptySession, context.storage.successfulSaveCount);
    }

    @Test
    void dueCardsAreOldestFirstWithStableDeckTies() throws StorageException {
        TestContext context = new TestContext();
        VocabularyCard todayFirst = context.addCard("あ", "a", "a");
        VocabularyCard yesterday = context.addCard("い", "i", "i");
        VocabularyCard todaySecond = context.addCard("う", "u", "u");
        VocabularyCard tomorrow = context.addCard("え", "e", "e");
        setDueDate(context.service, todayFirst.id(), START_DATE);
        setDueDate(context.service, yesterday.id(), START_DATE.minusDays(1));
        setDueDate(context.service, todaySecond.id(), START_DATE);
        setDueDate(context.service, tomorrow.id(), START_DATE.plusDays(1));
        Deck deck = context.service.createDeck("Core");
        addToDeck(context.service, deck, todayFirst, yesterday, todaySecond, tomorrow);
        int savesBeforeSession = context.storage.saveInvocations;

        FlashcardSession session = FlashcardSession.forDeck(
                context.service, deck.id(), FIXED_CLOCK);
        List<UUID> order = new ArrayList<>();
        while (session.state() != FlashcardSession.State.COMPLETED) {
            UUID cardId = session.currentCardId().orElseThrow();
            order.add(cardId);
            session.reveal(cardId);
            session.submit(cardId, ReviewOutcome.CORRECT);
        }

        assertEquals(List.of(yesterday.id(), todayFirst.id(), todaySecond.id()), order);
        assertEquals(3, session.attempted());
        assertEquals(0, session.remaining());
        assertEquals(3, context.storage.saveInvocations - savesBeforeSession);
    }

    @Test
    void selectedGlobalCardIgnoresDueDateAndDeckMembership() throws StorageException {
        TestContext context = new TestContext();
        VocabularyCard unassignedTomorrow = context.addCard("そ", "so", "so");
        setDueDate(context.service, unassignedTomorrow.id(), START_DATE.plusDays(1));

        FlashcardSession session = FlashcardSession.forCard(
                context.service, unassignedTomorrow.id());

        assertEquals(FlashcardSession.State.PROMPT, session.state());
        assertEquals("そ", session.currentPrompt().orElseThrow().hiragana());
        session.reveal(unassignedTomorrow.id());
        assertEquals("so", session.currentAnswer().orElseThrow().romaji());
        session.submit(unassignedTomorrow.id(), ReviewOutcome.INCORRECT);
        assertEquals(FlashcardSession.State.COMPLETED, session.state());
    }

    @Test
    void queueIsFrozenButCurrentTextIsResolvedFromService() throws StorageException {
        TestContext context = new TestContext();
        VocabularyCard first = context.addCard("ね", "ne", "root");
        VocabularyCard second = context.addCard("こ", "ko", "child");
        Deck deck = context.service.createDeck("Words");
        addToDeck(context.service, deck, first, second);
        FlashcardSession session = FlashcardSession.forDeck(
                context.service, deck.id(), FIXED_CLOCK);

        setDueDate(context.service, first.id(), START_DATE.plusDays(10));
        VocabularyCard third = context.addCard("い", "i", "new");
        context.service.addCardToDeck(deck.id(), third.id());
        context.service.editVocabularyCard(first.id(), "ねー", "ne", "updated");

        assertEquals(2, session.summary().initialQueueSize());
        assertEquals("ねー", session.currentPrompt().orElseThrow().hiragana());
        session.reveal(first.id());
        session.submit(first.id(), ReviewOutcome.CORRECT);
        assertEquals(second.id(), session.currentCardId().orElseThrow());
    }

    @Test
    void allCardQueueStaysFrozenAfterMembershipChangesAndResolvesCurrentText()
            throws StorageException {
        TestContext context = new TestContext();
        VocabularyCard first = context.addCard("ね", "ne", "root");
        VocabularyCard second = context.addCard("こ", "ko", "child");
        VocabularyCard addedLater = context.addCard("い", "i", "new");
        Deck deck = context.service.createDeck("Words");
        addToDeck(context.service, deck, first, second);
        FlashcardSession session = FlashcardSession.forAllCardsInDeck(
                context.service, deck.id());

        context.service.addCardToDeck(deck.id(), addedLater.id());
        context.service.removeCardFromDeck(deck.id(), second.id());
        context.service.editVocabularyCard(first.id(), "ねー", "ne", "updated");

        assertEquals(2, session.summary().initialQueueSize());
        assertEquals("ねー", session.currentPrompt().orElseThrow().hiragana());
        session.reveal(first.id());
        session.submit(first.id(), ReviewOutcome.CORRECT);
        assertEquals(second.id(), session.currentCardId().orElseThrow());
        assertTrue(context.service.data().findVocabularyCard(second.id()).isPresent());
        session.reveal(second.id());
        session.submit(second.id(), ReviewOutcome.INCORRECT);
        assertEquals(FlashcardSession.State.COMPLETED, session.state());
        assertEquals(new FlashcardSession.Summary(2, 1, 1, 2, 0, false), session.summary());
    }

    @Test
    void transitionsRejectEarlyRepeatedStaleNullAndSkippedEvents() throws StorageException {
        TestContext context = new TestContext();
        VocabularyCard first = context.addCard("ね", "ne", "root");
        VocabularyCard second = context.addCard("こ", "ko", "child");
        Deck deck = context.service.createDeck("Words");
        addToDeck(context.service, deck, first, second);
        FlashcardSession session = FlashcardSession.forDeck(
                context.service, deck.id(), FIXED_CLOCK);
        int savesBeforeActions = context.storage.saveInvocations;

        assertThrows(IllegalStateException.class, () -> session.submit(first.id(),
                ReviewOutcome.CORRECT));
        assertThrows(NullPointerException.class, () -> session.reveal(null));
        assertThrows(IllegalStateException.class, () -> session.reveal(second.id()));
        session.reveal(first.id());
        assertThrows(IllegalStateException.class, () -> session.reveal(first.id()));
        assertThrows(NullPointerException.class, () -> session.submit(first.id(), null));
        assertThrows(IllegalArgumentException.class, () -> session.submit(first.id(),
                ReviewOutcome.SKIPPED));
        assertThrows(IllegalStateException.class, () -> session.submit(second.id(),
                ReviewOutcome.CORRECT));
        assertEquals(0, session.attempted());
        assertEquals(savesBeforeActions, context.storage.saveInvocations);

        session.submit(first.id(), ReviewOutcome.CORRECT);
        session.reveal(second.id());
        int savesBeforeStaleAnswer = context.storage.saveInvocations;
        assertThrows(IllegalStateException.class, () -> session.submit(first.id(),
                ReviewOutcome.CORRECT));
        assertEquals(FlashcardSession.State.ANSWER_REVEALED, session.state());
        assertEquals(second.id(), session.currentCardId().orElseThrow());
        assertEquals(savesBeforeStaleAnswer, context.storage.saveInvocations);
        session.submit(second.id(), ReviewOutcome.INCORRECT);
        assertEquals(FlashcardSession.State.COMPLETED, session.state());
        assertThrows(IllegalStateException.class, () -> session.reveal(first.id()));
        assertThrows(IllegalStateException.class, () -> session.submit(second.id(),
                ReviewOutcome.CORRECT));
        int correct = session.correct();
        session.stop();
        assertEquals(correct, session.correct());
        assertEquals(FlashcardSession.State.COMPLETED, session.state());
    }

    @ParameterizedTest
    @CsvSource({"false, false", "false, true", "true, false", "true, true"})
    void stopPreservesCurrentCardAndRepeatedStop(boolean reviewAll, boolean revealBeforeStop)
            throws StorageException {
        TestContext context = new TestContext();
        VocabularyCard first = context.addCard("ね", "ne", "root");
        VocabularyCard second = context.addCard("こ", "ko", "child");
        Deck deck = context.service.createDeck("Words");
        addToDeck(context.service, deck, first, second);
        int savesBeforeSession = context.storage.saveInvocations;
        FlashcardSession session = createDeckSession(context, deck, reviewAll);
        List<ModeProgress> originalProgress = progressSnapshots(context.service);
        int savesBeforeStops = context.storage.saveInvocations;

        if (revealBeforeStop) {
            session.reveal(first.id());
        }
        assertEquals(savesBeforeSession, context.storage.saveInvocations);
        session.stop();
        FlashcardSession.Summary stopped = session.summary();
        session.stop();

        assertEquals(FlashcardSession.State.STOPPED, session.state());
        assertEquals(first.id(), session.currentCardId().orElseThrow());
        assertEquals(stopped, session.summary());
        assertEquals(2, session.remaining());
        assertTrue(session.stopped());
        assertEquals(new FlashcardSession.Summary(2, 0, 0, 0, 2, true), stopped);
        assertEquals(originalProgress, progressSnapshots(context.service));
        assertEquals(savesBeforeStops, context.storage.saveInvocations);
        assertThrows(IllegalStateException.class, () -> session.reveal(first.id()));

        FlashcardSession revealedSession = FlashcardSession.forCard(
                context.service, second.id());
        revealedSession.reveal(second.id());
        revealedSession.stop();
        assertEquals(FlashcardSession.State.STOPPED, revealedSession.state());
        assertEquals(second.id(), revealedSession.currentCardId().orElseThrow());
        assertEquals(new FlashcardSession.Summary(1, 0, 0, 0, 1, true), revealedSession.summary());
        assertEquals(originalProgress, progressSnapshots(context.service));
        assertEquals(savesBeforeStops, context.storage.saveInvocations);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void stopAfterSuccessfulAnswerPreservesProgressAndDoesNotSave(boolean reviewAll)
            throws StorageException {
        TestContext context = new TestContext();
        VocabularyCard first = context.addCard("ね", "ne", "root");
        VocabularyCard second = context.addCard("こ", "ko", "child");
        Deck deck = context.service.createDeck("Words");
        addToDeck(context.service, deck, first, second);
        FlashcardSession session = createDeckSession(context, deck, reviewAll);
        session.reveal(first.id());
        session.submit(first.id(), ReviewOutcome.CORRECT);
        List<ModeProgress> progressBeforeStop = progressSnapshots(context.service);
        int savesBeforeStop = context.storage.saveInvocations;

        session.stop();

        assertEquals(FlashcardSession.State.STOPPED, session.state());
        assertEquals(second.id(), session.currentCardId().orElseThrow());
        assertEquals(new FlashcardSession.Summary(2, 1, 0, 1, 1, true), session.summary());
        assertEquals(progressBeforeStop, progressSnapshots(context.service));
        assertEquals(savesBeforeStop, context.storage.saveInvocations);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void stopAfterFailedSaveLeavesOutcomeUnrecordedAndDoesNotSaveAgain(boolean reviewAll)
            throws StorageException {
        TestContext context = new TestContext();
        VocabularyCard card = context.addCard("ね", "ne", "root");
        Deck deck = context.service.createDeck("Words");
        context.service.addCardToDeck(deck.id(), card.id());
        FlashcardSession session = reviewAll ? createDeckSession(context, deck, true)
                : FlashcardSession.forCard(context.service, card.id());
        session.reveal(card.id());
        List<ModeProgress> originalProgress = progressSnapshots(context.service);
        int savesBeforeFailure = context.storage.saveInvocations;
        context.storage.failNextSave = true;
        assertThrows(StorageException.class, () -> session.submit(card.id(), ReviewOutcome.CORRECT));
        assertEquals(FlashcardSession.State.ANSWER_REVEALED, session.state());

        session.stop();
        session.stop();

        assertEquals(FlashcardSession.State.STOPPED, session.state());
        assertEquals(card.id(), session.currentCardId().orElseThrow());
        assertEquals(new FlashcardSession.Summary(1, 0, 0, 0, 1, true), session.summary());
        assertEquals(originalProgress, progressSnapshots(context.service));
        assertEquals(savesBeforeFailure + 1, context.storage.saveInvocations);
        assertThrows(IllegalStateException.class, () -> session.submit(card.id(), ReviewOutcome.CORRECT));
        assertEquals(savesBeforeFailure + 1, context.storage.saveInvocations);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void saveFailuresAtFirstMiddleAndFinalCardCanBeRetried(boolean reviewAll) throws StorageException {
        TestContext context = new TestContext();
        VocabularyCard first = context.addCard("あ", "a", "a");
        VocabularyCard second = context.addCard("い", "i", "i");
        VocabularyCard third = context.addCard("う", "u", "u");
        Deck deck = context.service.createDeck("Core");
        addToDeck(context.service, deck, first, second, third);
        FlashcardSession session = createDeckSession(context, deck, reviewAll);
        int savesBeforeSession = context.storage.saveInvocations;

        answerWithRetry(context, session, first, ReviewOutcome.CORRECT);
        answerWithRetry(context, session, second, ReviewOutcome.INCORRECT);
        answerWithRetry(context, session, third, ReviewOutcome.CORRECT);

        assertEquals(FlashcardSession.State.COMPLETED, session.state());
        assertEquals(2, session.correct());
        assertEquals(1, session.incorrect());
        assertEquals(3, session.attempted());
        assertEquals(0, session.remaining());
        assertEquals(6, context.storage.saveInvocations - savesBeforeSession);
    }

    @Test
    void crossMidnightUsesStartDateForSelectionAndSubmissionDateForOutcome()
            throws StorageException {
        AtomicReference<Instant> currentInstant = new AtomicReference<>(
                Instant.parse("2026-08-30T01:00:00Z"));
        InstantSource source = currentInstant::get;
        Clock clock = source.withZone(ZoneId.of("Asia/Singapore"));
        TestContext context = new TestContext(clock);
        VocabularyCard card = context.addCard("ね", "ne", "root");
        setDueDate(context.service, card.id(), START_DATE);
        Deck deck = context.service.createDeck("Core");
        context.service.addCardToDeck(deck.id(), card.id());
        FlashcardSession session = FlashcardSession.forDeck(context.service, deck.id(), clock);

        currentInstant.set(Instant.parse("2026-08-31T16:00:00Z"));
        session.reveal(card.id());
        session.submit(card.id(), ReviewOutcome.CORRECT);

        ModeProgress progress = context.service.data().findVocabularyCard(card.id()).orElseThrow()
                .progressFor(Mode.FLASHCARD);
        assertEquals(LocalDate.of(2026, 9, 1), progress.lastReviewedDate());
        assertEquals(LocalDate.of(2026, 9, 2), progress.nextDueDate());
    }

    @Test
    void allCardEarlyOutcomesScheduleOnSubmissionDateAndLeaveTypingUnchanged()
            throws StorageException {
        AtomicReference<Instant> currentInstant = new AtomicReference<>(
                Instant.parse("2026-08-30T01:00:00Z"));
        InstantSource source = currentInstant::get;
        Clock clock = source.withZone(ZoneId.of("Asia/Singapore"));
        TestContext context = new TestContext(clock);
        VocabularyCard correct = context.addCard("か", "ka", "correct");
        VocabularyCard incorrect = context.addCard("き", "ki", "incorrect");
        ModeProgress correctTyping = new ModeProgress(2, 4, 3,
                START_DATE.minusDays(2), START_DATE.plusDays(5));
        ModeProgress incorrectTyping = new ModeProgress(3, 5, 4,
                START_DATE.minusDays(3), START_DATE.plusDays(6));
        currentCard(context.service, correct.id()).updateProgress(Mode.TYPING, correctTyping);
        currentCard(context.service, incorrect.id()).updateProgress(Mode.TYPING, incorrectTyping);
        setDueDate(context.service, correct.id(), START_DATE.plusDays(20));
        setDueDate(context.service, incorrect.id(), START_DATE.plusDays(20));
        Deck deck = context.service.createDeck("Early");
        addToDeck(context.service, deck, correct, incorrect);
        FlashcardSession session = FlashcardSession.forAllCardsInDeck(
                context.service, deck.id());

        currentInstant.set(Instant.parse("2026-08-31T16:00:00Z"));
        session.reveal(correct.id());
        session.submit(correct.id(), ReviewOutcome.CORRECT);
        session.reveal(incorrect.id());
        session.submit(incorrect.id(), ReviewOutcome.INCORRECT);

        VocabularyCard updatedCorrect = context.service.data()
                .findVocabularyCard(correct.id()).orElseThrow();
        VocabularyCard updatedIncorrect = context.service.data()
                .findVocabularyCard(incorrect.id()).orElseThrow();
        assertProgressEquals(new ModeProgress(1, 1, 1, LocalDate.of(2026, 9, 1),
                LocalDate.of(2026, 9, 2)), updatedCorrect.progressFor(Mode.FLASHCARD));
        assertProgressEquals(new ModeProgress(0, 1, 0, LocalDate.of(2026, 9, 1),
                LocalDate.of(2026, 9, 2)), updatedIncorrect.progressFor(Mode.FLASHCARD));
        assertProgressEquals(correctTyping, updatedCorrect.progressFor(Mode.TYPING));
        assertProgressEquals(incorrectTyping, updatedIncorrect.progressFor(Mode.TYPING));
    }

    @Test
    void sharedGlobalProgressAccumulatesAndTypingIsUnaffected() throws StorageException {
        TestContext context = new TestContext();
        VocabularyCard shared = context.addCard("ほ", "ho", "ear");
        shared.updateProgress(Mode.TYPING, new ModeProgress(2, 4, 3,
                START_DATE.minusDays(2), START_DATE.plusDays(5)));
        Deck first = context.service.createDeck("First");
        Deck second = context.service.createDeck("Second");
        context.service.addCardToDeck(first.id(), shared.id());
        context.service.addCardToDeck(second.id(), shared.id());
        FlashcardSession firstSession = FlashcardSession.forDeck(
                context.service, first.id(), FIXED_CLOCK);
        FlashcardSession secondSession = FlashcardSession.forDeck(
                context.service, second.id(), FIXED_CLOCK);

        firstSession.reveal(shared.id());
        firstSession.submit(shared.id(), ReviewOutcome.CORRECT);
        secondSession.reveal(shared.id());
        secondSession.submit(shared.id(), ReviewOutcome.INCORRECT);

        VocabularyCard updated = context.service.data().findVocabularyCard(shared.id()).orElseThrow();
        assertEquals(2, updated.progressFor(Mode.FLASHCARD).attempts());
        assertEquals(1, updated.progressFor(Mode.FLASHCARD).correctAttempts());
        assertEquals(4, updated.progressFor(Mode.TYPING).attempts());
        assertEquals(3, updated.progressFor(Mode.TYPING).correctAttempts());
    }

    @Test
    void repeatedAllCardSessionsStartFreshAndDueEligibilityUsesSharedProgress()
            throws StorageException {
        TestContext context = new TestContext();
        VocabularyCard shared = context.addCard("ほ", "ho", "ear");
        Deck first = context.service.createDeck("First");
        Deck second = context.service.createDeck("Second");
        context.service.addCardToDeck(first.id(), shared.id());
        context.service.addCardToDeck(second.id(), shared.id());

        FlashcardSession firstSession = FlashcardSession.forAllCardsInDeck(
                context.service, first.id());
        firstSession.reveal(shared.id());
        firstSession.submit(shared.id(), ReviewOutcome.CORRECT);

        FlashcardSession dueSession = FlashcardSession.forDeck(
                context.service, second.id(), FIXED_CLOCK);
        FlashcardSession secondSession = FlashcardSession.forAllCardsInDeck(
                context.service, second.id());

        assertEquals(FlashcardSession.State.COMPLETED, dueSession.state());
        assertEquals(new FlashcardSession.Summary(0, 0, 0, 0, 0, false), dueSession.summary());
        assertEquals(FlashcardSession.State.PROMPT, secondSession.state());
        assertEquals(new FlashcardSession.Summary(1, 0, 0, 0, 1, false), secondSession.summary());
        secondSession.reveal(shared.id());
        secondSession.submit(shared.id(), ReviewOutcome.INCORRECT);

        ModeProgress progress = context.service.data().findVocabularyCard(shared.id()).orElseThrow()
                .progressFor(Mode.FLASHCARD);
        assertProgressEquals(new ModeProgress(0, 2, 1, START_DATE, START_DATE.plusDays(1)), progress);
    }

    @Test
    void missingQueuedCardFailsWithoutSkippingOrChangingCounts() throws StorageException {
        TestContext context = new TestContext();
        VocabularyCard card = context.addCard("ね", "ne", "root");
        Deck deck = context.service.createDeck("Words");
        context.service.addCardToDeck(deck.id(), card.id());
        FlashcardSession session = FlashcardSession.forDeck(
                context.service, deck.id(), FIXED_CLOCK);
        context.service.deleteVocabularyCard(card.id());
        int savesBeforeAction = context.storage.saveInvocations;

        assertThrows(IllegalStateException.class, session::currentPrompt);
        assertThrows(IllegalStateException.class, () -> session.reveal(card.id()));
        assertEquals(FlashcardSession.State.PROMPT, session.state());
        assertEquals(0, session.attempted());
        assertEquals(1, session.remaining());
        assertEquals(savesBeforeAction, context.storage.saveInvocations);
    }

    @Test
    void missingRevealedCardRejectsSubmissionWithoutChangingStateOrSaving() throws StorageException {
        TestContext context = new TestContext();
        VocabularyCard card = context.addCard("ね", "ne", "root");
        FlashcardSession session = FlashcardSession.forCard(
                context.service, card.id());
        session.reveal(card.id());
        context.service.deleteVocabularyCard(card.id());
        FlashcardSession.Summary summaryBeforeSubmission = session.summary();
        int savesBeforeSubmission = context.storage.saveInvocations;

        assertThrows(IllegalStateException.class, session::currentAnswer);
        assertThrows(IllegalStateException.class, () -> session.submit(card.id(), ReviewOutcome.CORRECT));

        assertEquals(FlashcardSession.State.ANSWER_REVEALED, session.state());
        assertEquals(card.id(), session.currentCardId().orElseThrow());
        assertEquals(summaryBeforeSubmission, session.summary());
        assertEquals(savesBeforeSubmission, context.storage.saveInvocations);
    }

    @Test
    void sessionPersistsThroughRealJsonStorage(@TempDir Path temporaryDirectory)
            throws StorageException {
        JsonStorage storage = new JsonStorage(temporaryDirectory.resolve("koko.json"));
        KokoService service = new KokoService(storage, FIXED_CLOCK);
        VocabularyCard card = service.addVocabularyCard("ねこ", "neko", "cat");
        Deck deck = service.createDeck("Animals");
        service.addCardToDeck(deck.id(), card.id());
        FlashcardSession session = FlashcardSession.forDeck(service, deck.id(), FIXED_CLOCK);

        session.reveal(card.id());
        session.submit(card.id(), ReviewOutcome.CORRECT);

        KokoService restored = new KokoService(storage, FIXED_CLOCK);
        restored.load();
        VocabularyCard restoredCard = restored.data().findVocabularyCard(card.id()).orElseThrow();
        assertEquals(1, restoredCard.progressFor(Mode.FLASHCARD).attempts());
        assertEquals(0, restoredCard.progressFor(Mode.TYPING).attempts());
        assertEquals(List.of(card.id()), restored.data().findDeckById(deck.id()).orElseThrow()
                .cardIds());
    }

    private static void answerWithRetry(TestContext context, FlashcardSession session,
            VocabularyCard card, ReviewOutcome outcome) throws StorageException {
        session.reveal(card.id());
        KokoData dataBeforeFailure = context.service.data();
        List<ModeProgress> progressBeforeFailure = progressSnapshots(context.service);
        FlashcardSession.Answer answerBeforeFailure = session.currentAnswer().orElseThrow();
        FlashcardSession.Summary summaryBeforeFailure = session.summary();
        int saveInvocationsBeforeFailure = context.storage.saveInvocations;
        int successfulSavesBeforeFailure = context.storage.successfulSaveCount;
        ModeProgress typingBeforeFailure = context.service.data().findVocabularyCard(card.id())
                .orElseThrow().progressFor(Mode.TYPING);
        context.storage.failNextSave = true;
        assertThrows(StorageException.class, () -> session.submit(card.id(), outcome));
        assertSame(dataBeforeFailure, context.service.data());
        assertEquals(progressBeforeFailure, progressSnapshots(context.service));
        assertEquals(FlashcardSession.State.ANSWER_REVEALED, session.state());
        assertEquals(card.id(), session.currentCardId().orElseThrow());
        assertEquals(answerBeforeFailure, session.currentAnswer().orElseThrow());
        assertEquals(summaryBeforeFailure, session.summary());
        assertEquals(saveInvocationsBeforeFailure + 1, context.storage.saveInvocations);
        assertEquals(successfulSavesBeforeFailure, context.storage.successfulSaveCount);
        session.submit(card.id(), outcome);
        assertEquals(saveInvocationsBeforeFailure + 2, context.storage.saveInvocations);
        assertEquals(successfulSavesBeforeFailure + 1, context.storage.successfulSaveCount);
        assertEquals(summaryBeforeFailure.attempted() + 1, session.attempted());
        assertEquals(summaryBeforeFailure.remaining() - 1, session.remaining());
        VocabularyCard updated = context.service.data().findVocabularyCard(card.id()).orElseThrow();
        int correct = outcome == ReviewOutcome.CORRECT ? 1 : 0;
        assertProgressEquals(new ModeProgress(correct, 1, correct, START_DATE, START_DATE.plusDays(1)),
                updated.progressFor(Mode.FLASHCARD));
        assertProgressEquals(typingBeforeFailure, updated.progressFor(Mode.TYPING));
        assertThrows(IllegalStateException.class, () -> session.submit(card.id(), outcome));
        assertEquals(saveInvocationsBeforeFailure + 2, context.storage.saveInvocations);
    }

    /**
     * Starts a due session or an all-card session with future-due test cards.
     *
     * @param context service and storage used by the test.
     * @param deck deck whose members will be reviewed.
     * @param reviewAll whether to make the members future-due and review them early.
     * @return a session using the requested entry point.
     */
    private static FlashcardSession createDeckSession(TestContext context, Deck deck, boolean reviewAll) {
        if (reviewAll) {
            Deck currentDeck = context.service.data().findDeckById(deck.id()).orElseThrow();
            for (UUID cardId : currentDeck.cardIds()) {
                setDueDate(context.service, cardId, START_DATE.plusDays(10));
            }
            return FlashcardSession.forAllCardsInDeck(context.service, deck.id());
        }
        return FlashcardSession.forDeck(context.service, deck.id(), FIXED_CLOCK);
    }

    private static void addToDeck(KokoService service, Deck deck, VocabularyCard... cards)
            throws StorageException {
        for (VocabularyCard card : cards) {
            service.addCardToDeck(deck.id(), card.id());
        }
    }

    private static void setDueDate(KokoService service, UUID cardId, LocalDate dueDate) {
        VocabularyCard card = service.data().findVocabularyCard(cardId).orElseThrow();
        ModeProgress original = card.progressFor(Mode.FLASHCARD);
        card.updateProgress(Mode.FLASHCARD, new ModeProgress(original.mastery(), original.attempts(),
                original.correctAttempts(), original.lastReviewedDate(), dueDate));
    }

    private static VocabularyCard currentCard(KokoService service, UUID cardId) {
        return service.data().findVocabularyCard(cardId).orElseThrow();
    }

    private static void assertProgressEquals(ModeProgress expected, ModeProgress actual) {
        assertEquals(expected.mastery(), actual.mastery());
        assertEquals(expected.attempts(), actual.attempts());
        assertEquals(expected.correctAttempts(), actual.correctAttempts());
        assertEquals(expected.lastReviewedDate(), actual.lastReviewedDate());
        assertEquals(expected.nextDueDate(), actual.nextDueDate());
    }

    /**
     * Captures both immutable progress records for every card before an action.
     *
     * @param service service whose current progress is captured.
     * @return snapshots that remain unchanged if cards later replace their progress.
     */
    private static List<ModeProgress> progressSnapshots(KokoService service) {
        List<ModeProgress> snapshots = new ArrayList<>();
        for (VocabularyCard card : service.data().vocabularyCards()) {
            snapshots.add(card.progressFor(Mode.FLASHCARD));
            snapshots.add(card.progressFor(Mode.TYPING));
        }
        return List.copyOf(snapshots);
    }

    /** Test context with deterministic time and a save-counting storage double. */
    private static final class TestContext {

        private final CountingStorage storage;
        private final KokoService service;

        private TestContext() {
            this(FIXED_CLOCK);
        }

        private TestContext(Clock clock) {
            storage = new CountingStorage();
            service = new KokoService(storage, clock);
        }

        private VocabularyCard addCard(String hiragana, String romaji, String meaning)
                throws StorageException {
            return service.addVocabularyCard(hiragana, romaji, meaning);
        }
    }

    /** Storage double that can fail one selected save without changing service data. */
    private static final class CountingStorage implements Storage {

        private KokoData loadedData = new KokoData();
        private int saveInvocations;
        private int successfulSaveCount;
        private boolean failNextSave;

        @Override
        public KokoData load() {
            return loadedData;
        }

        @Override
        public void save(KokoData data) throws StorageException {
            saveInvocations++;
            if (failNextSave) {
                failNextSave = false;
                throw new StorageException("forced save failure", null);
            }
            loadedData = data;
            successfulSaveCount++;
        }
    }
}
