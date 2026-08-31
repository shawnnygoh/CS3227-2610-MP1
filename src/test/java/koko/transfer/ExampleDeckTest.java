package koko.transfer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import koko.model.Deck;
import koko.model.KokoData;
import koko.model.Mode;
import koko.model.ModeProgress;
import koko.model.VocabularyCard;
import koko.service.KokoService;
import koko.storage.JsonStorage;

/**
 * Checks that the shipped example works through the real portable import boundary.
 *
 * <p>Automated import checks do not establish linguistic accuracy.
 */
class ExampleDeckTest {

    private static final Path EXAMPLE_PATH = Path.of("examples", "koko-sample-deck.json");
    private static final LocalDate IMPORT_DATE = LocalDate.of(2026, 8, 31);
    private static final Clock IMPORT_CLOCK = Clock.fixed(
            Instant.parse("2026-08-31T01:00:00Z"), ZoneId.of("Asia/Singapore"));

    @TempDir
    Path temporaryDirectory;

    @Test
    void exampleImportsIntoEmptyStorageWithOrderedCardsAndFreshProgress() throws Exception {
        PortableDeck document = new DeckTransfer().read(EXAMPLE_PATH);
        JsonStorage storage = new JsonStorage(temporaryDirectory.resolve("new library.json"));
        KokoService service = new KokoService(storage, IMPORT_CLOCK);
        service.load();

        Deck imported = service.importDeck(EXAMPLE_PATH);

        assertEquals(1, document.schemaVersion());
        assertEquals("Koko Starter Vocabulary", imported.name());
        assertEquals(12, document.cards().size());
        assertEquals(12, new HashSet<>(imported.cardIds()).size());
        assertEquals(12, service.data().vocabularyCards().size());
        assertEquals(document.cards(), cardText(service.data(), imported));
        for (VocabularyCard card : service.data().vocabularyCards()) {
            assertFreshProgress(card);
        }

        KokoData restored = storage.load();
        Deck restoredDeck = restored.findDeckById(imported.id()).orElseThrow();
        assertEquals(imported.cardIds(), restoredDeck.cardIds());
        assertEquals(document.deckName(), restoredDeck.name());
        assertEquals(document.cards(), cardText(restored, restoredDeck));
        for (VocabularyCard card : restored.vocabularyCards()) {
            assertFreshProgress(card);
        }
    }

    @Test
    void exampleReusesMatchingVocabularyWithoutReplacingTextOrEitherProgress() throws Exception {
        PortableDeck document = new DeckTransfer().read(EXAMPLE_PATH);
        PortableCard exampleCard = document.cards().get(0);
        KokoData initial = new KokoData();
        VocabularyCard shared = initial.addVocabularyCard(exampleCard.hiragana(), "IE",
                "HOUSE", IMPORT_DATE.minusDays(10));
        ModeProgress flashcard = new ModeProgress(4, 8, 6,
                IMPORT_DATE.minusDays(1), IMPORT_DATE.plusDays(8));
        ModeProgress typing = new ModeProgress(2, 5, 3,
                IMPORT_DATE.minusDays(2), IMPORT_DATE.plusDays(3));
        shared.updateProgress(Mode.FLASHCARD, flashcard);
        shared.updateProgress(Mode.TYPING, typing);
        Deck existing = initial.createDeck("Existing deck");
        initial.addCardToDeck(existing.id(), shared.id());
        JsonStorage storage = new JsonStorage(temporaryDirectory.resolve("existing library.json"));
        storage.save(initial);
        KokoService service = new KokoService(storage, IMPORT_CLOCK);
        service.load();

        Deck imported = service.importDeck(EXAMPLE_PATH);

        assertEquals(shared.id(), imported.cardIds().get(0));
        assertEquals(12, service.data().vocabularyCards().size());
        assertEquals(2, service.data().decks().size());
        for (KokoData data : List.of(service.data(), storage.load())) {
            VocabularyCard reused = data.findVocabularyCard(shared.id()).orElseThrow();
            assertEquals(shared.hiragana(), reused.hiragana());
            assertEquals("IE", reused.romaji());
            assertEquals("HOUSE", reused.englishMeaning());
            assertProgressEquals(flashcard, reused.progressFor(Mode.FLASHCARD));
            assertProgressEquals(typing, reused.progressFor(Mode.TYPING));
            assertEquals(List.of(shared.id()), data.findDeckById(existing.id())
                    .orElseThrow().cardIds());
            Deck currentImported = data.findDeckById(imported.id()).orElseThrow();
            assertEquals(imported.cardIds(), currentImported.cardIds());
            assertEquals(document.cards().subList(1, 12),
                    cardText(data, currentImported).subList(1, 12));
            for (VocabularyCard card : data.vocabularyCards()) {
                if (!card.id().equals(shared.id())) {
                    assertFreshProgress(card);
                }
            }
        }
    }

    private static List<PortableCard> cardText(KokoData data, Deck deck) {
        return deck.cardIds().stream()
                .map(id -> data.findVocabularyCard(id).orElseThrow())
                .map(card -> new PortableCard(card.hiragana(), card.romaji(), card.englishMeaning()))
                .toList();
    }

    private static void assertFreshProgress(VocabularyCard card) {
        for (Mode mode : Mode.values()) {
            ModeProgress progress = card.progressFor(mode);
            assertEquals(0, progress.mastery());
            assertEquals(0, progress.attempts());
            assertEquals(0, progress.correctAttempts());
            assertNull(progress.lastReviewedDate());
            assertEquals(IMPORT_DATE, progress.nextDueDate());
        }
    }

    private static void assertProgressEquals(ModeProgress expected, ModeProgress actual) {
        assertEquals(expected.mastery(), actual.mastery());
        assertEquals(expected.attempts(), actual.attempts());
        assertEquals(expected.correctAttempts(), actual.correctAttempts());
        assertEquals(expected.lastReviewedDate(), actual.lastReviewedDate());
        assertEquals(expected.nextDueDate(), actual.nextDueDate());
    }
}
