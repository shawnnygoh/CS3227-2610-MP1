package koko.review;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
 * Tests headless English-to-Hiragana typing review coordination.
 */
class TypingSessionTest {

    private static final LocalDate START_DATE = LocalDate.of(2026, 8, 30);
    private static final Clock FIXED_CLOCK = Clock.fixed(
            Instant.parse("2026-08-30T01:00:00Z"), ZoneId.of("Asia/Singapore"));

    @Test
    void newFactoriesRejectUnknownAndNullSelectionsWithoutSaving() {
        TestContext context = new TestContext();
        UUID missingId = UUID.randomUUID();
        KokoData originalData = context.service.data();

        assertThrows(IllegalArgumentException.class, () ->
                TypingSession.forCard(context.service, missingId));
        assertThrows(IllegalArgumentException.class, () ->
                TypingSession.forAllCardsInDeck(context.service, missingId));
        assertThrows(NullPointerException.class, () ->
                TypingSession.forCard(null, missingId));
        assertThrows(NullPointerException.class, () ->
                TypingSession.forAllCardsInDeck(null, missingId));
        assertThrows(NullPointerException.class, () ->
                TypingSession.forCard(context.service, null));
        assertThrows(NullPointerException.class, () ->
                TypingSession.forAllCardsInDeck(context.service, null));

        assertSame(originalData, context.service.data());
        assertEquals(0, context.storage.saveInvocations);
    }

    @Test
    void emptyAndFutureOnlyDecksStartCompletedWithoutSaving() throws StorageException {
        TestContext context = new TestContext();
        Deck empty = context.service.createDeck("Empty");
        TypingSession emptySession = TypingSession.forDeck(
                context.service, empty.id(), FIXED_CLOCK);
        assertEquals(TypingSession.State.COMPLETED, emptySession.state());
        assertEquals(new TypingSession.Summary(0, 0, 0, 0, 0, 0, false),
                emptySession.summary());

        VocabularyCard future = context.addCard("ねこ", "neko", "cat");
        setDueDate(context.service, future.id(), Mode.TYPING, START_DATE.plusDays(1));
        Deck futureDeck = context.service.createDeck("Future");
        context.service.addCardToDeck(futureDeck.id(), future.id());
        int savesBefore = context.storage.saveInvocations;
        TypingSession futureSession = TypingSession.forDeck(
                context.service, futureDeck.id(), FIXED_CLOCK);
        assertEquals(TypingSession.State.COMPLETED, futureSession.state());
        assertEquals(savesBefore, context.storage.saveInvocations);
    }

    @Test
    void dueSelectionIsInclusiveOldestFirstAndStableForDeckTies() throws StorageException {
        TestContext context = new TestContext();
        VocabularyCard todayFirst = context.addCard("あ", "a", "first");
        VocabularyCard yesterday = context.addCard("い", "i", "yesterday");
        VocabularyCard todaySecond = context.addCard("う", "u", "second");
        VocabularyCard tomorrow = context.addCard("え", "e", "tomorrow");
        setDueDate(context.service, todayFirst.id(), Mode.TYPING, START_DATE);
        setDueDate(context.service, yesterday.id(), Mode.TYPING, START_DATE.minusDays(1));
        setDueDate(context.service, todaySecond.id(), Mode.TYPING, START_DATE);
        setDueDate(context.service, tomorrow.id(), Mode.TYPING, START_DATE.plusDays(1));
        Deck deck = context.service.createDeck("Core");
        addToDeck(context.service, deck, todayFirst, yesterday, todaySecond, tomorrow);

        TypingSession session = TypingSession.forDeck(context.service, deck.id(), FIXED_CLOCK);
        List<UUID> order = new ArrayList<>();
        while (session.state() == TypingSession.State.PROMPT) {
            UUID id = session.currentCardId().orElseThrow();
            order.add(id);
            session.submit(id, context.service.data().findVocabularyCard(id).orElseThrow().hiragana());
            session.next(id);
        }
        assertEquals(List.of(yesterday.id(), todayFirst.id(), todaySecond.id()), order);
        assertEquals(3, session.attempted());
        assertEquals(0, session.remaining());
    }

    @Test
    void allCardReviewUsesMembershipOrderRegardlessOfTypingDueDateAndCanRepeat()
            throws StorageException {
        TestContext context = new TestContext();
        VocabularyCard first = context.addCard("あ", "a", "first");
        VocabularyCard second = context.addCard("い", "i", "second");
        VocabularyCard third = context.addCard("う", "u", "third");
        setDueDate(context.service, first.id(), Mode.TYPING, START_DATE.plusDays(5));
        setDueDate(context.service, second.id(), Mode.TYPING, START_DATE.minusDays(1));
        setDueDate(context.service, third.id(), Mode.TYPING, START_DATE);
        Deck deck = context.service.createDeck("Core");
        addToDeck(context.service, deck, third, first, second);
        int savesBeforeSession = context.storage.saveInvocations;

        TypingSession session = TypingSession.forAllCardsInDeck(
                context.service, deck.id());
        List<UUID> order = new ArrayList<>();
        while (session.state() == TypingSession.State.PROMPT) {
            UUID cardId = session.currentCardId().orElseThrow();
            order.add(cardId);
            VocabularyCard card = context.service.data().findVocabularyCard(cardId).orElseThrow();
            session.submit(cardId, card.hiragana());
            session.next(cardId);
        }

        assertEquals(List.of(third.id(), first.id(), second.id()), order);
        assertEquals(new TypingSession.Summary(3, 3, 0, 0, 3, 0, false), session.summary());
        assertEquals(3, context.storage.saveInvocations - savesBeforeSession);
        assertEquals(3, context.storage.successfulSaveCount - savesBeforeSession);

        TypingSession repeated = TypingSession.forAllCardsInDeck(
                context.service, deck.id());
        assertEquals(TypingSession.State.PROMPT, repeated.state());
        assertEquals(third.id(), repeated.currentCardId().orElseThrow());
    }

    @Test
    void allCardFutureOnlyAndEmptyDecksCompleteWithoutSaving() throws StorageException {
        TestContext context = new TestContext();
        VocabularyCard future = context.addCard("み", "mi", "future");
        setDueDate(context.service, future.id(), Mode.TYPING, START_DATE.plusDays(10));
        Deck futureDeck = context.service.createDeck("Future");
        context.service.addCardToDeck(futureDeck.id(), future.id());
        int savesBeforeReview = context.storage.saveInvocations;

        TypingSession futureSession = TypingSession.forAllCardsInDeck(
                context.service, futureDeck.id());
        assertEquals(TypingSession.State.PROMPT, futureSession.state());
        assertEquals(savesBeforeReview, context.storage.saveInvocations);
        futureSession.submit(future.id(), future.hiragana());
        futureSession.next(future.id());
        assertEquals(TypingSession.State.COMPLETED, futureSession.state());

        Deck emptyDeck = context.service.createDeck("Empty");
        int savesBeforeEmptySession = context.storage.saveInvocations;
        TypingSession emptySession = TypingSession.forAllCardsInDeck(
                context.service, emptyDeck.id());

        assertEquals(TypingSession.State.COMPLETED, emptySession.state());
        assertEquals(new TypingSession.Summary(0, 0, 0, 0, 0, 0, false), emptySession.summary());
        assertEquals(savesBeforeEmptySession, context.storage.saveInvocations);
    }

    @Test
    void selectedCardIgnoresTypingDueDateAndDeckMembershipAndCanRepeat() throws StorageException {
        TestContext context = new TestContext();
        VocabularyCard card = context.addCard("が", "ga", "sound");
        setDueDate(context.service, card.id(), Mode.TYPING, START_DATE.plusDays(10));
        int savesBeforeSession = context.storage.saveInvocations;

        TypingSession session = TypingSession.forCard(context.service, card.id());
        assertEquals(new TypingSession.Prompt(card.id(), "sound"),
                session.currentPrompt().orElseThrow());
        session.submit(card.id(), "  か\u3099  ");
        assertEquals(ReviewOutcome.CORRECT, session.currentFeedback().orElseThrow().outcome());
        assertEquals(TypingSession.State.FEEDBACK, session.state());
        assertEquals(savesBeforeSession + 1, context.storage.saveInvocations);
        session.next(card.id());
        assertEquals(TypingSession.State.COMPLETED, session.state());

        TypingSession repeated = TypingSession.forCard(context.service, card.id());
        assertEquals(TypingSession.State.PROMPT, repeated.state());
        assertEquals(card.id(), repeated.currentCardId().orElseThrow());
    }

    @Test
    void queueIsFrozenWhileCurrentEnglishIsResolvedFromGlobalData() throws StorageException {
        TestContext context = new TestContext();
        VocabularyCard first = context.addCard("ね", "ne", "first");
        VocabularyCard second = context.addCard("こ", "ko", "second");
        Deck deck = context.service.createDeck("Words");
        addToDeck(context.service, deck, first, second);
        TypingSession session = TypingSession.forDeck(context.service, deck.id(), FIXED_CLOCK);

        setDueDate(context.service, first.id(), Mode.TYPING, START_DATE.plusDays(5));
        VocabularyCard third = context.addCard("い", "i", "new");
        context.service.addCardToDeck(deck.id(), third.id());
        context.service.editVocabularyCard(first.id(), "ね", "ne", "updated");

        assertEquals(2, session.summary().initialQueueSize());
        assertEquals(new TypingSession.Prompt(first.id(), "updated"),
                session.currentPrompt().orElseThrow());
    }

    @Test
    void allCardQueueIsFrozenAfterMembershipAndProgressChanges() throws StorageException {
        TestContext context = new TestContext();
        VocabularyCard first = context.addCard("ね", "ne", "first");
        VocabularyCard second = context.addCard("こ", "ko", "second");
        VocabularyCard addedLater = context.addCard("い", "i", "new");
        Deck deck = context.service.createDeck("Words");
        addToDeck(context.service, deck, first, second);
        TypingSession session = TypingSession.forAllCardsInDeck(
                context.service, deck.id());

        setDueDate(context.service, first.id(), Mode.TYPING, START_DATE.plusDays(10));
        setDueDate(context.service, second.id(), Mode.TYPING, START_DATE.plusDays(10));
        context.service.addCardToDeck(deck.id(), addedLater.id());
        context.service.removeCardFromDeck(deck.id(), second.id());
        context.service.editVocabularyCard(first.id(), "ね", "ne", "updated");

        assertEquals(2, session.summary().initialQueueSize());
        assertEquals(new TypingSession.Prompt(first.id(), "updated"),
                session.currentPrompt().orElseThrow());
        session.submit(first.id(), first.hiragana());
        session.next(first.id());
        assertEquals(second.id(), session.currentCardId().orElseThrow());
        session.submit(second.id(), second.hiragana());
        session.next(second.id());
        assertEquals(TypingSession.State.COMPLETED, session.state());
        assertEquals(new TypingSession.Summary(2, 2, 0, 0, 2, 0, false), session.summary());
    }

    @Test
    void submitShowsFeedbackAndNextCompletesFinalCard() throws StorageException {
        TestContext context = new TestContext();
        VocabularyCard card = context.addCard("ねこ", "neko", "cat");
        Deck deck = context.service.createDeck("Animals");
        context.service.addCardToDeck(deck.id(), card.id());
        TypingSession session = TypingSession.forDeck(context.service, deck.id(), FIXED_CLOCK);

        assertTrue(session.currentFeedback().isEmpty());
        session.submit(card.id(), "  neko  ");
        assertEquals(TypingSession.State.FEEDBACK, session.state());
        assertEquals(new TypingSession.Feedback(card.id(), "  neko  ", "ねこ",
                ReviewOutcome.INCORRECT), session.currentFeedback().orElseThrow());
        assertEquals(new TypingSession.Summary(1, 0, 1, 0, 1, 0, false), session.summary());
        assertEquals(card.id(), session.currentCardId().orElseThrow());
        session.next(card.id());
        assertEquals(TypingSession.State.COMPLETED, session.state());
        assertTrue(session.currentCardId().isEmpty());
        assertTrue(session.currentFeedback().isEmpty());
        assertThrows(IllegalStateException.class, () -> session.next(card.id()));
    }

    @Test
    void skipIsAnAttemptAndWaitsForNext() throws StorageException {
        TestContext context = new TestContext();
        VocabularyCard card = context.addCard("ねこ", "neko", "cat");
        Deck deck = context.service.createDeck("Animals");
        context.service.addCardToDeck(deck.id(), card.id());
        TypingSession session = TypingSession.forDeck(context.service, deck.id(), FIXED_CLOCK);

        session.skip(card.id());
        assertEquals(new TypingSession.Feedback(card.id(), "", "ねこ", ReviewOutcome.SKIPPED),
                session.currentFeedback().orElseThrow());
        assertEquals(new TypingSession.Summary(1, 0, 0, 1, 1, 0, false), session.summary());
        assertThrows(IllegalStateException.class, () -> session.skip(card.id()));
        session.next(card.id());
        assertEquals(TypingSession.State.COMPLETED, session.state());
    }

    @Test
    void earlyDuplicateAndStaleActionsDoNotSaveOrChangeState() throws StorageException {
        TestContext context = new TestContext();
        VocabularyCard first = context.addCard("ね", "ne", "first");
        VocabularyCard second = context.addCard("こ", "ko", "second");
        Deck deck = context.service.createDeck("Words");
        addToDeck(context.service, deck, first, second);
        TypingSession session = TypingSession.forDeck(context.service, deck.id(), FIXED_CLOCK);
        int savesBefore = context.storage.saveInvocations;

        assertThrows(IllegalStateException.class, () -> session.next(first.id()));
        assertThrows(IllegalStateException.class, () -> session.submit(second.id(), "ね"));
        assertThrows(IllegalStateException.class, () -> session.skip(second.id()));
        assertThrows(NullPointerException.class, () -> session.submit(first.id(), null));
        assertEquals(TypingSession.State.PROMPT, session.state());
        assertEquals(new TypingSession.Summary(2, 0, 0, 0, 0, 2, false), session.summary());
        assertEquals(savesBefore, context.storage.saveInvocations);

        session.submit(first.id(), "ね");
        assertThrows(IllegalStateException.class, () -> session.submit(first.id(), "ね"));
        assertThrows(IllegalStateException.class, () -> session.next(second.id()));
        assertEquals(1, session.attempted());
        assertEquals(1, context.storage.saveInvocations - savesBefore);
    }

    @Test
    void stopBeforeAndAfterFeedbackDoesNotSaveAgain() throws StorageException {
        TestContext context = new TestContext();
        VocabularyCard card = context.addCard("ね", "ne", "root");
        Deck deck = context.service.createDeck("Words");
        context.service.addCardToDeck(deck.id(), card.id());
        TypingSession session = TypingSession.forDeck(context.service, deck.id(), FIXED_CLOCK);
        int savesBeforeStop = context.storage.saveInvocations;

        session.stop(card.id());
        assertEquals(TypingSession.State.STOPPED, session.state());
        assertEquals(card.id(), session.currentCardId().orElseThrow());
        assertEquals(new TypingSession.Summary(1, 0, 0, 0, 0, 1, true), session.summary());
        assertEquals(savesBeforeStop, context.storage.saveInvocations);
        session.stop(card.id());
        assertEquals(savesBeforeStop, context.storage.saveInvocations);

        TypingSession afterFeedback = TypingSession.forDeck(context.service, deck.id(), FIXED_CLOCK);
        afterFeedback.submit(card.id(), "ね");
        int savesAfterSubmit = context.storage.saveInvocations;
        afterFeedback.stop(card.id());
        assertEquals(TypingSession.State.STOPPED, afterFeedback.state());
        assertEquals(1, afterFeedback.attempted());
        assertEquals(0, afterFeedback.remaining());
        assertEquals(1, afterFeedback.currentFeedback().orElseThrow().outcome() == ReviewOutcome.CORRECT
                ? afterFeedback.correct() : -1);
        afterFeedback.stop(card.id());
        assertEquals(savesAfterSubmit, context.storage.saveInvocations);
    }

    @Test
    void failedSubmitAndSkipLeaveStateUnchangedAndCanBeRetried() throws StorageException {
        TestContext context = new TestContext();
        VocabularyCard first = context.addCard("ね", "ne", "first");
        VocabularyCard second = context.addCard("こ", "ko", "second");
        currentCard(context.service, first.id()).updateProgress(Mode.TYPING,
                new ModeProgress(2, 3, 1, START_DATE.minusDays(1), START_DATE));
        currentCard(context.service, second.id()).updateProgress(Mode.TYPING,
                new ModeProgress(3, 5, 2, START_DATE.minusDays(1), START_DATE));
        Deck deck = context.service.createDeck("Words");
        addToDeck(context.service, deck, first, second);
        TypingSession session = TypingSession.forDeck(context.service, deck.id(), FIXED_CLOCK);
        KokoData originalData = context.service.data();
        VocabularyCard originalCard = currentCard(context.service, first.id());
        ModeProgress original = originalCard.progressFor(Mode.TYPING);
        int savesBeforeFailure = context.storage.saveInvocations;
        context.storage.failNextSave = true;

        assertThrows(StorageException.class, () -> session.submit(first.id(), "ね"));
        assertSame(originalData, context.service.data());
        assertSame(originalCard, currentCard(context.service, first.id()));
        assertSame(original, originalCard.progressFor(Mode.TYPING));
        assertEquals(TypingSession.State.PROMPT, session.state());
        assertEquals(first.id(), session.currentCardId().orElseThrow());
        assertTrue(session.currentFeedback().isEmpty());
        assertEquals(new TypingSession.Summary(2, 0, 0, 0, 0, 2, false), session.summary());
        assertEquals(1, context.storage.saveInvocations - savesBeforeFailure);

        session.submit(first.id(), "ね");
        ModeProgress expectedFirst = new ModeProgress(3, 4, 2,
                START_DATE, START_DATE.plusDays(7));
        assertProgressEquals(expectedFirst, context.service.data().findVocabularyCard(first.id())
                .orElseThrow().progressFor(Mode.TYPING));
        assertProgressEquals(expectedFirst, context.storage.load().findVocabularyCard(first.id())
                .orElseThrow().progressFor(Mode.TYPING));
        assertEquals(TypingSession.State.FEEDBACK, session.state());
        assertEquals(new TypingSession.Summary(2, 1, 0, 0, 1, 1, false), session.summary());
        assertEquals(2, context.storage.saveInvocations - savesBeforeFailure);
        session.next(first.id());
        assertEquals(2, context.storage.saveInvocations - savesBeforeFailure);
        context.storage.failNextSave = true;
        KokoData dataBeforeSkip = context.service.data();
        ModeProgress secondOriginal = context.service.data().findVocabularyCard(second.id())
                .orElseThrow().progressFor(Mode.TYPING);
        assertThrows(StorageException.class, () -> session.skip(second.id()));
        assertSame(dataBeforeSkip, context.service.data());
        assertSame(secondOriginal, context.service.data().findVocabularyCard(second.id())
                .orElseThrow().progressFor(Mode.TYPING));
        assertEquals(TypingSession.State.PROMPT, session.state());
        assertEquals(second.id(), session.currentCardId().orElseThrow());
        assertTrue(session.currentFeedback().isEmpty());
        assertEquals(new TypingSession.Summary(2, 1, 0, 0, 1, 1, false), session.summary());
        assertEquals(3, context.storage.saveInvocations - savesBeforeFailure);
        session.skip(second.id());
        ModeProgress expectedSecond = new ModeProgress(3, 6, 2,
                START_DATE, START_DATE.plusDays(1));
        assertProgressEquals(expectedSecond, context.service.data().findVocabularyCard(second.id())
                .orElseThrow().progressFor(Mode.TYPING));
        assertProgressEquals(expectedSecond, context.storage.load().findVocabularyCard(second.id())
                .orElseThrow().progressFor(Mode.TYPING));
        assertProgressEquals(expectedFirst, context.storage.load().findVocabularyCard(first.id())
                .orElseThrow().progressFor(Mode.TYPING));
        assertEquals(TypingSession.State.FEEDBACK, session.state());
        assertEquals(new TypingSession.Summary(2, 1, 0, 1, 2, 0, false), session.summary());
        assertEquals(4, context.storage.saveInvocations - savesBeforeFailure);
        session.next(second.id());
        assertEquals(TypingSession.State.COMPLETED, session.state());
        assertEquals(2, session.attempted());
        assertEquals(4, context.storage.saveInvocations - savesBeforeFailure);
    }

    @Test
    void missingCurrentCardFailsWithoutChangingSession() throws StorageException {
        TestContext context = new TestContext();
        VocabularyCard card = context.addCard("ね", "ne", "root");
        Deck deck = context.service.createDeck("Words");
        context.service.addCardToDeck(deck.id(), card.id());
        TypingSession session = TypingSession.forDeck(context.service, deck.id(), FIXED_CLOCK);
        context.service.deleteVocabularyCard(card.id());
        int savesBefore = context.storage.saveInvocations;

        assertThrows(IllegalStateException.class, session::currentPrompt);
        assertThrows(IllegalStateException.class, () -> session.submit(card.id(), "ね"));
        assertEquals(TypingSession.State.PROMPT, session.state());
        assertEquals(0, session.attempted());
        assertEquals(1, session.remaining());
        assertEquals(savesBefore, context.storage.saveInvocations);
    }

    @Test
    void typingProgressIsIndependentSharedAndUsesSubmissionDate() throws StorageException {
        AtomicReference<Instant> current = new AtomicReference<>(
                Instant.parse("2026-08-30T01:00:00Z"));
        InstantSource source = current::get;
        Clock clock = source.withZone(ZoneId.of("Asia/Singapore"));
        TestContext context = new TestContext(clock);
        VocabularyCard card = context.addCard("ねこ", "neko", "cat");
        ModeProgress originalFlashcard = card.progressFor(Mode.FLASHCARD);
        ModeProgress originalTyping = new ModeProgress(2, 4, 3,
                START_DATE.minusDays(2), START_DATE);
        card.updateProgress(Mode.TYPING, originalTyping);
        Deck first = context.service.createDeck("First");
        Deck second = context.service.createDeck("Second");
        context.service.addCardToDeck(first.id(), card.id());
        context.service.addCardToDeck(second.id(), card.id());

        TypingSession firstSession = TypingSession.forDeck(context.service, first.id(), clock);
        assertEquals(TypingSession.State.PROMPT, firstSession.state());
        current.set(Instant.parse("2026-08-31T16:00:00Z"));
        firstSession.submit(card.id(), "ねこ");
        ModeProgress updated = context.service.data().findVocabularyCard(card.id()).orElseThrow()
                .progressFor(Mode.TYPING);
        assertProgressEquals(new ModeProgress(3, 5, 4, LocalDate.of(2026, 9, 1),
                LocalDate.of(2026, 9, 8)), updated);
        assertProgressEquals(originalFlashcard, context.service.data()
                .findVocabularyCard(card.id()).orElseThrow().progressFor(Mode.FLASHCARD));

        TypingSession secondSession = TypingSession.forDeck(context.service, second.id(), clock);
        assertEquals(TypingSession.State.COMPLETED, secondSession.state());
        firstSession.next(card.id());
        assertEquals(TypingSession.State.COMPLETED, firstSession.state());
    }

    @Test
    void earlyAllCardReviewSharesTypingProgressAcrossDecksAndLeavesFlashcardAlone()
            throws StorageException {
        AtomicReference<Instant> current = new AtomicReference<>(
                Instant.parse("2026-08-30T01:00:00Z"));
        InstantSource source = current::get;
        Clock clock = source.withZone(ZoneId.of("Asia/Singapore"));
        TestContext context = new TestContext(clock);
        VocabularyCard card = context.addCard("ねこ", "neko", "cat");
        ModeProgress originalFlashcard = card.progressFor(Mode.FLASHCARD);
        ModeProgress originalTyping = new ModeProgress(2, 4, 3,
                START_DATE.minusDays(2), START_DATE.plusDays(20));
        card.updateProgress(Mode.TYPING, originalTyping);
        Deck first = context.service.createDeck("First");
        Deck second = context.service.createDeck("Second");
        context.service.addCardToDeck(first.id(), card.id());
        context.service.addCardToDeck(second.id(), card.id());

        TypingSession firstSession = TypingSession.forAllCardsInDeck(
                context.service, first.id());
        current.set(Instant.parse("2026-08-31T16:00:00Z"));
        firstSession.submit(card.id(), card.hiragana());
        firstSession.next(card.id());

        VocabularyCard updated = context.service.data().findVocabularyCard(card.id()).orElseThrow();
        assertProgressEquals(new ModeProgress(3, 5, 4, LocalDate.of(2026, 9, 1),
                LocalDate.of(2026, 9, 8)), updated.progressFor(Mode.TYPING));
        assertProgressEquals(originalFlashcard, updated.progressFor(Mode.FLASHCARD));
        assertEquals(TypingSession.State.COMPLETED,
                TypingSession.forDeck(context.service, second.id(), clock).state());
        assertEquals(TypingSession.State.PROMPT,
                TypingSession.forAllCardsInDeck(context.service, second.id()).state());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void newFactoriesCanRetryFailedSaveAndStopWithoutDuplicateAttempt(boolean reviewAll)
            throws StorageException {
        TestContext context = new TestContext();
        VocabularyCard card = context.addCard("ね", "ne", "root");
        setDueDate(context.service, card.id(), Mode.TYPING, START_DATE.plusDays(10));
        Deck deck = context.service.createDeck("Words");
        context.service.addCardToDeck(deck.id(), card.id());
        TypingSession session = reviewAll
                ? TypingSession.forAllCardsInDeck(context.service, deck.id())
                : TypingSession.forCard(context.service, card.id());
        KokoData originalData = context.service.data();
        VocabularyCard originalCard = currentCard(context.service, card.id());
        ModeProgress originalProgress = originalCard.progressFor(Mode.TYPING);
        TypingSession.Summary originalSummary = session.summary();
        int savesBeforeFailure = context.storage.saveInvocations;
        int successfulSavesBeforeFailure = context.storage.successfulSaveCount;
        context.storage.failNextSave = true;

        assertThrows(StorageException.class, () -> session.submit(card.id(), card.hiragana()));
        assertSame(originalData, context.service.data());
        assertSame(originalCard, currentCard(context.service, card.id()));
        assertSame(originalProgress, originalCard.progressFor(Mode.TYPING));
        assertEquals(originalSummary, session.summary());
        assertEquals(TypingSession.State.PROMPT, session.state());
        assertEquals(savesBeforeFailure + 1, context.storage.saveInvocations);
        assertEquals(successfulSavesBeforeFailure, context.storage.successfulSaveCount);

        session.submit(card.id(), card.hiragana());
        VocabularyCard updated = context.service.data().findVocabularyCard(card.id()).orElseThrow();
        assertEquals(1, updated.progressFor(Mode.TYPING).attempts());
        assertEquals(1, context.storage.load().findVocabularyCard(card.id()).orElseThrow()
                .progressFor(Mode.TYPING).attempts());
        assertEquals(savesBeforeFailure + 2, context.storage.saveInvocations);
        assertEquals(successfulSavesBeforeFailure + 1, context.storage.successfulSaveCount);
        assertEquals(1, session.attempted());

        session.stop(card.id());
        session.stop(card.id());
        assertEquals(TypingSession.State.STOPPED, session.state());
        assertEquals(1, session.attempted());
        assertEquals(savesBeforeFailure + 2, context.storage.saveInvocations);
        assertThrows(IllegalStateException.class, () -> session.submit(card.id(), card.hiragana()));
    }

    @Test
    void typingSessionPersistsAndRestoresThroughRealJson(@TempDir Path temporaryDirectory)
            throws StorageException {
        JsonStorage storage = new JsonStorage(temporaryDirectory.resolve("koko.json"));
        KokoService service = new KokoService(storage, FIXED_CLOCK);
        VocabularyCard card = service.addVocabularyCard("ねこ", "neko", "cat");
        Deck deck = service.createDeck("Animals");
        service.addCardToDeck(deck.id(), card.id());
        TypingSession session = TypingSession.forDeck(service, deck.id(), FIXED_CLOCK);
        session.submit(card.id(), "ねこ");

        KokoService restored = new KokoService(storage, FIXED_CLOCK);
        restored.load();
        VocabularyCard restoredCard = restored.data().findVocabularyCard(card.id()).orElseThrow();
        assertEquals(1, restoredCard.progressFor(Mode.TYPING).attempts());
        assertEquals(0, restoredCard.progressFor(Mode.FLASHCARD).attempts());
        assertEquals(List.of(card.id()), restored.data().findDeckById(deck.id()).orElseThrow()
                .cardIds());
    }

    private static void addToDeck(KokoService service, Deck deck, VocabularyCard... cards)
            throws StorageException {
        for (VocabularyCard card : cards) {
            service.addCardToDeck(deck.id(), card.id());
        }
    }

    private static void setDueDate(KokoService service, UUID cardId, Mode mode, LocalDate dueDate) {
        VocabularyCard card = service.data().findVocabularyCard(cardId).orElseThrow();
        ModeProgress original = card.progressFor(mode);
        card.updateProgress(mode, new ModeProgress(original.mastery(), original.attempts(),
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
            loadedData = copyOf(data);
            successfulSaveCount++;
        }

        private static KokoData copyOf(KokoData source) {
            List<VocabularyCard> cards = new ArrayList<>();
            for (VocabularyCard card : source.vocabularyCards()) {
                cards.add(VocabularyCard.restore(card.id(), card.hiragana(), card.romaji(),
                        card.englishMeaning(), copyProgress(card.progressFor(Mode.FLASHCARD)),
                        copyProgress(card.progressFor(Mode.TYPING))));
            }
            List<Deck> decks = source.decks().stream()
                    .map(deck -> Deck.restore(deck.id(), deck.name(), deck.cardIds()))
                    .toList();
            return KokoData.restore(cards, decks);
        }

        private static ModeProgress copyProgress(ModeProgress progress) {
            return new ModeProgress(progress.mastery(), progress.attempts(),
                    progress.correctAttempts(), progress.lastReviewedDate(), progress.nextDueDate());
        }
    }
}
