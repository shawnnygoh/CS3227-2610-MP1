package koko.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

import koko.model.Deck;
import koko.model.KokoData;
import koko.model.Mode;
import koko.model.ModeProgress;
import koko.model.VocabularyCard;
import koko.storage.Storage;
import koko.storage.StorageException;
import koko.transfer.DeckTransfer;
import koko.transfer.DeckTransferException;
import koko.transfer.PortableCard;
import koko.transfer.PortableDeck;

/**
 * Application service for the current Koko data set.
 *
 * <p>The service keeps domain operations together with their persistence
 * boundary. A successful mutation is applied to a detached candidate, saved
 * exactly once, and published only after saving succeeds.
 *
 * <p>Callers must treat the state returned by {@link #data()} as read-only and
 * perform changes through this service. Successful publication may replace
 * cards, decks, and their containing aggregate, while retaining their UUIDs.
 */
public final class KokoService {

    private final Storage storage;
    private final Clock clock;
    private final DeckTransfer deckTransfer;
    private KokoData data;

    /**
     * Creates a service with an empty current state.
     *
     * @param storage persistence boundary.
     * @param clock clock used for new-card creation dates.
     * @throws NullPointerException if storage or clock is null.
     */
    public KokoService(Storage storage, Clock clock) {
        this.storage = Objects.requireNonNull(storage, "Storage cannot be null");
        this.clock = Objects.requireNonNull(clock, "Clock cannot be null");
        deckTransfer = new DeckTransfer();
        data = new KokoData();
    }

    /**
     * Loads the complete current state from storage.
     *
     * @throws StorageException if the stored state cannot be loaded or is invalid.
     */
    public void load() throws StorageException {
        data = Objects.requireNonNull(storage.load(), "Storage returned no data");
    }

    /**
     * Returns the current domain state for read-only access.
     *
     * <p>The returned aggregate and its domain objects may become stale after
     * any successful service operation. Callers must not mutate exposed cards,
     * decks, or collections directly; they should retain UUIDs and reacquire
     * current objects from a later call to this method. Successful operations
     * do not promise to preserve Java object identity.
     *
     * @return current vocabulary and deck state.
     */
    public KokoData data() {
        return data;
    }

    /**
     * Reads and validates one portable deck without changing application state.
     *
     * @param source source portable JSON file.
     * @return immutable validated portable deck contents.
     * @throws DeckTransferException if the source cannot be read or violates the
     *         portable format.
     * @throws NullPointerException if source is null.
     */
    public PortableDeck prepareImport(Path source) throws DeckTransferException {
        return deckTransfer.read(source);
    }

    /**
     * Applies a validated portable document under a confirmed deck name.
     *
     * <p>The document is validated again because public DTO application must not
     * bypass the portable boundary. The import date is sampled for every
     * application attempt, after name validation and before fresh cards are made.
     *
     * @param document immutable portable document to apply.
     * @param confirmedName final application deck name.
     * @return the newly created published deck.
     * @throws DeckTransferException if the document is invalid or unsupported.
     * @throws IllegalArgumentException if the confirmed name is blank or conflicts.
     * @throws StorageException if the complete candidate cannot be persisted.
     * @throws NullPointerException if document or confirmedName is null.
     */
    public Deck importDeck(PortableDeck document, String confirmedName)
            throws DeckTransferException, StorageException {
        deckTransfer.validate(document);
        String normalizedName = Deck.normalizeName(confirmedName);
        KokoData candidate = copyOf(data);
        Deck importedDeck = candidate.createDeck(normalizedName);
        LocalDate importDate = LocalDate.now(clock);
        List<UUID> resolvedCardIds = new ArrayList<>();
        for (PortableCard portableCard : document.cards()) {
            VocabularyCard card = candidate.findVocabularyCardByIdentity(
                    portableCard.hiragana(), portableCard.englishMeaning()).orElse(null);
            if (card == null) {
                card = candidate.addVocabularyCard(portableCard.hiragana(), portableCard.romaji(),
                        portableCard.englishMeaning(), importDate);
            }
            resolvedCardIds.add(card.id());
        }
        for (UUID cardId : resolvedCardIds) {
            candidate.addCardToDeck(importedDeck.id(), cardId);
        }
        storage.save(candidate);
        data = candidate;
        return importedDeck;
    }

    /**
     * Reads and imports a portable deck using its embedded deck name.
     *
     * <p>This compatibility operation delegates to the same preparation and
     * application steps used by the editable import workflow.
     *
     * @param source source portable JSON file.
     * @return the newly created deck.
     * @throws DeckTransferException if the source cannot be read or is invalid.
     * @throws IllegalArgumentException if the embedded deck name conflicts.
     * @throws StorageException if the complete candidate cannot be persisted.
     * @throws NullPointerException if source is null.
     */
    public Deck importDeck(Path source) throws DeckTransferException, StorageException {
        PortableDeck document = prepareImport(source);
        return importDeck(document, document.deckName());
    }

    /**
     * Exports one current deck to a new portable JSON file.
     *
     * <p>Membership order is taken from the deck while card text is resolved
     * from the current global library. Export does not persist or otherwise
     * mutate application state. This overload never replaces an existing file.
     *
     * @param deckId deck to export.
     * @param destination new destination JSON file.
     * @throws DeckTransferException if serialization or destination writing fails.
     * @throws IllegalArgumentException if deckId is unknown.
     * @throws NullPointerException if deckId or destination is null.
     */
    public void exportDeck(UUID deckId, Path destination) throws DeckTransferException {
        deckTransfer.write(prepareExport(deckId, destination), destination);
    }

    /**
     * Exports one current deck, honoring a native confirmation for this destination.
     *
     * <p>The service rejects the configured internal storage file and aliases
     * before portable file operations begin. A confirmation is accepted only
     * for the actual final destination represented by the chooser path.
     *
     * @param deckId deck to export.
     * @param confirmation captured native selection and its final destination.
     * @throws DeckTransferException if the destination is unsafe or export fails.
     * @throws IllegalArgumentException if deckId is unknown.
     * @throws NullPointerException if deckId or confirmation is null.
     */
    public void exportDeck(UUID deckId,
            DeckTransfer.ConfirmedDestination confirmation) throws DeckTransferException {
        Objects.requireNonNull(confirmation, "Confirmed destination cannot be null");
        deckTransfer.write(prepareExport(deckId, confirmation.path()), confirmation);
    }

    /** Checks library protection and snapshots current deck text for either export mode. */
    private PortableDeck prepareExport(UUID deckId, Path destination) throws DeckTransferException {
        Objects.requireNonNull(deckId, "Deck ID cannot be null");
        Objects.requireNonNull(destination, "Destination path cannot be null");
        rejectInternalStorageAlias(destination);
        Deck deck = data.findDeckById(deckId)
                .orElseThrow(() -> new IllegalArgumentException("Deck does not exist"));
        List<PortableCard> cards = deck.cardIds().stream()
                .map(cardId -> data.findVocabularyCard(cardId).orElseThrow(() ->
                        new IllegalStateException("Deck references an unknown card")))
                .map(card -> new PortableCard(card.hiragana(), card.romaji(),
                        card.englishMeaning()))
                .toList();
        return new PortableDeck(DeckTransfer.CURRENT_SCHEMA_VERSION, deck.name(), cards);
    }

    /**
     * Rejects paths that could address Koko's own configured storage file.
     *
     * <p>Absolute paths retain symbolic-link and parent traversal semantics; do
     * not collapse a linked directory followed by {@code ..}. File identity
     * checks cover hard links, case aliases on providers that support them, and
     * existing paths reached through a parent-directory link. An identity check
     * that fails for a reason other than absence fails closed.
     *
     * @param destination requested export path.
     * @throws DeckTransferException if the path is protected or cannot be checked.
     */
    private void rejectInternalStorageAlias(Path destination) throws DeckTransferException {
        Optional<Path> configured = storage.configuredPath();
        if (configured.isEmpty()) {
            return;
        }
        Path destinationPath = destination.toAbsolutePath();
        Path storagePath = configured.get().toAbsolutePath();
        if (destinationPath.equals(storagePath)) {
            throw new DeckTransferException("Export destination is Koko's protected internal storage");
        }

        BasicFileAttributes destinationAttributes = readAttributesIfPresent(destinationPath);
        BasicFileAttributes storageAttributes = readAttributesIfPresent(storagePath);
        if (destinationAttributes != null && storageAttributes != null) {
            if (sameFile(destinationPath, storagePath)) {
                throw new DeckTransferException("Export destination aliases Koko's protected "
                        + "internal storage");
            }
            return;
        }

        Path destinationParent = destinationPath.getParent();
        Path storageParent = storagePath.getParent();
        if (destinationParent == null || storageParent == null) {
            return;
        }
        BasicFileAttributes destinationParentAttributes = readAttributesIfPresent(destinationParent);
        BasicFileAttributes storageParentAttributes = readAttributesIfPresent(storageParent);
        if (destinationParentAttributes != null && storageParentAttributes != null
                && sameFile(destinationParent, storageParent)
                && destinationPath.getFileName().toString()
                        .equalsIgnoreCase(storagePath.getFileName().toString())) {
            throw new DeckTransferException("Export destination aliases Koko's protected internal "
                    + "storage");
        }
    }

    /**
     * Reads identity-check attributes without following the final symbolic link.
     *
     * @param path path to inspect without lexical normalization.
     * @return attributes, or null only when the path is absent.
     * @throws DeckTransferException if inspection fails for a reason other than absence.
     */
    private static BasicFileAttributes readAttributesIfPresent(Path path)
            throws DeckTransferException {
        try {
            return Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        } catch (NoSuchFileException exception) {
            return null;
        } catch (IOException | RuntimeException exception) {
            throw new DeckTransferException("Could not safely verify export destination identity",
                    exception);
        }
    }

    /**
     * Compares filesystem identity while preserving linked-directory traversal.
     *
     * @param first first existing path.
     * @param second second existing path.
     * @return true when both paths refer to the same file.
     * @throws DeckTransferException if identity cannot be checked.
     */
    private static boolean sameFile(Path first, Path second) throws DeckTransferException {
        try {
            return Files.isSameFile(first, second);
        } catch (IOException | RuntimeException exception) {
            throw new DeckTransferException("Could not safely verify export destination identity",
                    exception);
        }
    }

    /**
     * Creates and persists a globally owned vocabulary card.
     *
     * @param hiragana Hiragana text.
     * @param romaji romaji pronunciation.
     * @param englishMeaning English meaning.
     * @return the newly created card.
     * @throws IllegalArgumentException if a field is blank, invalid, or duplicated.
     * @throws NullPointerException if a card field is null.
     * @throws StorageException if persistence fails.
     */
    public VocabularyCard addVocabularyCard(String hiragana, String romaji,
            String englishMeaning) throws StorageException {
        LocalDate creationDate = LocalDate.now(clock);
        return mutate(working -> working.addVocabularyCard(hiragana, romaji, englishMeaning,
                creationDate));
    }

    /**
     * Edits a global card while retaining its identity and progress.
     *
     * @param cardId card to edit.
     * @param hiragana replacement Hiragana text.
     * @param romaji replacement romaji pronunciation.
     * @param englishMeaning replacement English meaning.
     * @throws IllegalArgumentException if the card ID is unknown, a field is invalid,
     *         or the replacement is duplicated.
     * @throws NullPointerException if the card ID or a replacement field is null.
     * @throws StorageException if persistence fails.
     */
    public void editVocabularyCard(UUID cardId, String hiragana, String romaji,
            String englishMeaning) throws StorageException {
        mutate(working -> {
            working.editVocabularyCard(cardId, hiragana, romaji, englishMeaning);
            return null;
        });
    }

    /**
     * Deletes a global card and all of its deck memberships.
     *
     * @param cardId card to delete.
     * @throws IllegalArgumentException if the card ID is unknown.
     * @throws NullPointerException if the card ID is null.
     * @throws StorageException if persistence fails.
     */
    public void deleteVocabularyCard(UUID cardId) throws StorageException {
        mutate(working -> {
            working.deleteVocabularyCard(cardId);
            return null;
        });
    }

    /**
     * Creates and persists a uniquely named deck.
     *
     * @param name deck name.
     * @return the newly created deck.
     * @throws IllegalArgumentException if the name is blank or already used.
     * @throws NullPointerException if the name is null.
     * @throws StorageException if persistence fails.
     */
    public Deck createDeck(String name) throws StorageException {
        return mutate(working -> working.createDeck(name));
    }

    /**
     * Renames a deck while retaining its identity and memberships.
     *
     * @param deckId deck to rename.
     * @param newName replacement name.
     * @throws IllegalArgumentException if the deck ID is unknown, the name is blank,
     *         or the name is already used.
     * @throws NullPointerException if the deck ID or replacement name is null.
     * @throws StorageException if persistence fails.
     */
    public void renameDeck(UUID deckId, String newName) throws StorageException {
        mutate(working -> {
            working.renameDeck(deckId, newName);
            return null;
        });
    }

    /**
     * Deletes a deck and persists the removal without deleting its global cards.
     *
     * @param deckId deck to delete.
     * @throws IllegalArgumentException if the deck ID is unknown.
     * @throws NullPointerException if the deck ID is null.
     * @throws StorageException if persistence fails.
     */
    public void deleteDeck(UUID deckId) throws StorageException {
        mutate(working -> {
            working.deleteDeck(deckId);
            return null;
        });
    }

    /**
     * Adds an existing global card to a deck.
     *
     * @param deckId destination deck.
     * @param cardId existing global card.
     * @throws IllegalArgumentException if either ID is unknown or the card is already
     *         in the deck.
     * @throws NullPointerException if either ID is null.
     * @throws StorageException if persistence fails.
     */
    public void addCardToDeck(UUID deckId, UUID cardId) throws StorageException {
        mutate(working -> {
            working.addCardToDeck(deckId, cardId);
            return null;
        });
    }

    /**
     * Removes a card from a deck without deleting the global card.
     *
     * @param deckId deck to change.
     * @param cardId card membership to remove.
     * @throws IllegalArgumentException if either ID is unknown or the card is not in
     *         the deck.
     * @throws NullPointerException if either ID is null.
     * @throws StorageException if persistence fails.
     */
    public void removeCardFromDeck(UUID deckId, UUID cardId) throws StorageException {
        mutate(working -> {
            working.removeCardFromDeck(deckId, cardId);
            return null;
        });
    }

    /**
     * Records one flashcard review outcome and persists the resulting progress.
     *
     * <p>The review is allowed for any globally stored card, regardless of deck
     * membership or due status. The date is sampled from the injected clock for
     * this submission.
     *
     * @param cardId global card whose flashcard progress is reviewed.
     * @param outcome correct or incorrect review result.
     * @throws IllegalArgumentException if the card is unknown, outcome is skipped,
     *         or incrementing the attempt count exceeds the supported integer range.
     * @throws NullPointerException if cardId or outcome is null.
     * @throws java.time.DateTimeException if the next due date exceeds the range supported by {@link LocalDate}.
     * @throws StorageException if persistence fails.
     */
    public void recordFlashcardOutcome(UUID cardId, ReviewOutcome outcome)
            throws StorageException {
        Objects.requireNonNull(cardId, "Card ID cannot be null");
        Objects.requireNonNull(outcome, "Review outcome cannot be null");
        if (outcome == ReviewOutcome.SKIPPED) {
            throw new IllegalArgumentException("Skipped outcomes are not recorded");
        }

        recordOutcome(cardId, outcome, Mode.FLASHCARD);
    }

    /**
     * Records one English-to-Hiragana typing outcome and persists the resulting progress.
     *
     * <p>Typing outcomes are allowed for any globally stored card, regardless of deck
     * membership or due status. The date is sampled from the injected clock for this
     * submission, and only the card's typing progress is changed.
     *
     * @param cardId global card whose typing progress is reviewed.
     * @param outcome correct, incorrect, or skipped review result.
     * @throws IllegalArgumentException if the card is unknown or incrementing the attempt
     *         count exceeds the supported integer range.
     * @throws NullPointerException if cardId or outcome is null.
     * @throws java.time.DateTimeException if the next due date exceeds the range supported by {@link LocalDate}.
     * @throws StorageException if persistence fails.
     */
    public void recordTypingOutcome(UUID cardId, ReviewOutcome outcome)
            throws StorageException {
        Objects.requireNonNull(cardId, "Card ID cannot be null");
        Objects.requireNonNull(outcome, "Review outcome cannot be null");

        recordOutcome(cardId, outcome, Mode.TYPING);
    }

    private void recordOutcome(UUID cardId, ReviewOutcome outcome, Mode mode)
            throws StorageException {

        LocalDate reviewDate = LocalDate.now(clock);
        KokoData candidate = copyOf(data);
        VocabularyCard card = candidate.findVocabularyCard(cardId)
                .orElseThrow(() -> new IllegalArgumentException("Vocabulary card does not exist"));
        ModeProgress scheduled = new MasteryScheduler().schedule(
                card.progressFor(mode), outcome, reviewDate);
        card.updateProgress(mode, scheduled);
        storage.save(candidate);
        data = candidate;
    }

    /**
     * Applies one management operation to a detached candidate and publishes it after saving.
     *
     * <p>Validation and operation failures discard the candidate without changing current
     * state. A storage failure likewise leaves current state and all previously exposed
     * references untouched. The result is returned from the candidate that is published.
     *
     * @param operation management operation to apply to the candidate.
     * @param <T> operation result type.
     * @return the operation result from the published candidate.
     * @throws StorageException if the candidate cannot be persisted.
     */
    private <T> T mutate(Function<KokoData, T> operation) throws StorageException {
        KokoData candidate = copyOf(data);
        T result = operation.apply(candidate);
        storage.save(candidate);
        data = candidate;
        return result;
    }

    /**
     * Creates a detached deep copy of the complete aggregate, including both progress records.
     *
     * <p>Detachment covers the aggregate structure: cards, decks, and their
     * collections are rebuilt. Progress records are shared rather than rebuilt
     * because {@link ModeProgress} is immutable and {@code updateProgress}
     * replaces a mode's entry instead of mutating it, so a change to the
     * candidate can never reach the source.
     *
     * @param source aggregate to copy.
     * @return detached aggregate with the same UUIDs, values, order, memberships, and progress.
     */
    private static KokoData copyOf(KokoData source) {
        List<VocabularyCard> cards = new ArrayList<>();
        for (VocabularyCard card : source.vocabularyCards()) {
            cards.add(VocabularyCard.restore(card.id(), card.hiragana(), card.romaji(),
                    card.englishMeaning(), card.progressFor(Mode.FLASHCARD),
                    card.progressFor(Mode.TYPING)));
        }
        List<Deck> decks = source.decks().stream()
                .map(deck -> Deck.restore(deck.id(), deck.name(), deck.cardIds()))
                .toList();
        return KokoData.restore(cards, decks);
    }
}
