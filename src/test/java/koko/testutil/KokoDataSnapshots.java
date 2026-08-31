package koko.testutil;

import java.util.List;

import koko.model.Deck;
import koko.model.KokoData;
import koko.model.Mode;
import koko.model.VocabularyCard;

/**
 * Detached copies of a Koko aggregate, for storage doubles that must snapshot.
 *
 * <p>A storage double that retains the aggregate it was handed keeps a live
 * reference to state the service goes on to publish and mutate, so a later read
 * would observe current memory rather than what was stored. Copying here matches
 * what real persistence does: it rebuilds the structure.
 */
public final class KokoDataSnapshots {

    private KokoDataSnapshots() {
    }

    /**
     * Rebuilds an aggregate so later changes to the source cannot reach the copy.
     *
     * <p>Cards, decks, and their collections are rebuilt. Progress records are
     * shared rather than rebuilt, because {@code ModeProgress} is immutable and
     * a card replaces a mode's entry instead of mutating it. Mode independence
     * does not require distinct progress instances.
     *
     * @param source aggregate to copy.
     * @return a detached aggregate with the same UUIDs, values, order, and memberships.
     */
    public static KokoData copyOf(KokoData source) {
        List<VocabularyCard> cards = source.vocabularyCards().stream()
                .map(card -> VocabularyCard.restore(card.id(), card.hiragana(), card.romaji(),
                        card.englishMeaning(), card.progressFor(Mode.FLASHCARD),
                        card.progressFor(Mode.TYPING)))
                .toList();
        List<Deck> decks = source.decks().stream()
                .map(deck -> Deck.restore(deck.id(), deck.name(), deck.cardIds()))
                .toList();
        return KokoData.restore(cards, decks);
    }
}
