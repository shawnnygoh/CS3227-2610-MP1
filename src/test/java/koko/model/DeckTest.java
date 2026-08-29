package koko.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

/**
 * Tests ordered, unique deck membership references.
 */
class DeckTest {

    @Test
    void deckTrimsNameAndRejectsBlankNames() {
        Deck deck = new Deck("  Core words  ");

        assertEquals("Core words", deck.name());
        assertThrows(IllegalArgumentException.class, () -> new Deck(""));
        assertThrows(IllegalArgumentException.class, () -> new Deck("  "));
        assertThrows(NullPointerException.class, () -> new Deck(null));
    }

    @Test
    void deckKeepsInsertionOrderAndRejectsDuplicateReferences() {
        Deck deck = new Deck("Core words");
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        deck.addCard(first);
        deck.addCard(second);

        assertEquals(List.of(first, second), deck.cardIds());
        assertThrows(IllegalArgumentException.class, () -> deck.addCard(first));
        assertThrows(NullPointerException.class, () -> deck.addCard(null));
    }

    @Test
    void deckMembershipCollectionIsReadOnly() {
        Deck deck = new Deck("Core words");
        UUID cardId = UUID.randomUUID();
        deck.addCard(cardId);

        assertThrows(UnsupportedOperationException.class, () -> deck.cardIds().add(UUID.randomUUID()));
    }

    @Test
    void removingMissingMembershipIsRejected() {
        Deck deck = new Deck("Core words");

        assertThrows(IllegalArgumentException.class, () -> deck.removeCard(UUID.randomUUID()));
    }
}
