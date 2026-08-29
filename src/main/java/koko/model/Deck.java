package koko.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * An ordered deck of references to globally stored vocabulary cards.
 */
public final class Deck {

    private final UUID id;
    private final List<UUID> cardIds;
    private String name;

    /**
     * Creates a deck with a new stable identity.
     *
     * @param name deck name
     * @throws IllegalArgumentException if name is blank
     * @throws NullPointerException if name is null
     */
    public Deck(String name) {
        id = UUID.randomUUID();
        cardIds = new ArrayList<>();
        rename(name);
    }

    /**
     * Renames this deck after trimming its new name.
     *
     * @param newName replacement deck name
     * @throws IllegalArgumentException if newName is blank
     * @throws NullPointerException if newName is null
     */
    void rename(String newName) {
        name = requireNonBlank(newName, "Deck name");
    }

    /**
     * Returns the deck's stable identity.
     *
     * @return deck UUID
     */
    public UUID id() {
        return id;
    }

    public String name() {
        return name;
    }

    /**
     * Returns an unmodifiable view of the ordered card references.
     *
     * @return read-only ordered card UUIDs
     */
    public List<UUID> cardIds() {
        return Collections.unmodifiableList(cardIds);
    }

    void addCard(UUID cardId) {
        Objects.requireNonNull(cardId, "Card ID cannot be null");
        if (cardIds.contains(cardId)) {
            throw new IllegalArgumentException("Card is already in this deck");
        }
        cardIds.add(cardId);
    }

    void removeCard(UUID cardId) {
        Objects.requireNonNull(cardId, "Card ID cannot be null");
        if (!cardIds.remove(cardId)) {
            throw new IllegalArgumentException("Card is not in this deck");
        }
    }

    boolean containsCard(UUID cardId) {
        return cardIds.contains(cardId);
    }

    private static String requireNonBlank(String value, String fieldName) {
        Objects.requireNonNull(value, fieldName + " cannot be null");
        String trimmed = value.strip();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException(fieldName + " cannot be blank");
        }
        return trimmed;
    }
}
