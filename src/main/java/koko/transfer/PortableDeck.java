package koko.transfer;

import java.util.List;
import java.util.Objects;

/**
 * The versioned root document for a portable single-deck transfer.
 *
 * @param schemaVersion portable document schema version.
 * @param deckName deck name.
 * @param cards cards in deck membership order.
 */
public record PortableDeck(int schemaVersion, String deckName, List<PortableCard> cards) {

    /**
     * Creates a portable deck with an independent read-only card list.
     *
     * @param schemaVersion portable document schema version.
     * @param deckName deck name.
     * @param cards cards in deck membership order.
     * @throws NullPointerException if deckName, cards, or a card is null.
     */
    public PortableDeck {
        Objects.requireNonNull(deckName, "Deck name cannot be null");
        cards = List.copyOf(Objects.requireNonNull(cards, "Cards cannot be null"));
    }
}
