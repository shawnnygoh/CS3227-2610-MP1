package koko.storage;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.CopyOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;

import koko.model.Deck;
import koko.model.KokoData;
import koko.model.Mode;
import koko.model.ModeProgress;
import koko.model.VocabularyCard;

/**
 * Versioned JSON storage for Koko's global cards and ordered decks.
 *
 * <p>Each save serializes to a sibling temporary file and atomically replaces
 * the target. Unsupported atomic replacement is a controlled save error and
 * is not retried with a non-atomic move. This policy does not promise
 * power-loss durability or protection from concurrent writers.
 */
public final class JsonStorage implements Storage {

    private static final int CURRENT_SCHEMA_VERSION = 1;
    private static final Path DEFAULT_PATH = Path.of("data", "koko-data.json");
    private final Path path;
    private final ObjectMapper mapper;
    private final MoveOperation moveOperation;

    /**
     * Creates storage using Koko's default data path.
     */
    public JsonStorage() {
        this(DEFAULT_PATH);
    }

    /**
     * Creates storage using a caller-provided path, which is useful for tests.
     *
     * @param path JSON file path.
     * @throws NullPointerException if path is null.
     */
    public JsonStorage(Path path) {
        this(path, createMapper());
    }

    JsonStorage(Path path, ObjectMapper mapper) {
        this(path, mapper, Files::move);
    }

    /**
     * Creates storage with injectable serialization and move operations for failure tests.
     *
     * @param path JSON file path.
     * @param mapper mapper used to serialize and restore storage documents.
     * @param moveOperation operation that honors the requested atomic move options.
     * @throws NullPointerException if an argument is null.
     */
    JsonStorage(Path path, ObjectMapper mapper, MoveOperation moveOperation) {
        this.path = Objects.requireNonNull(path, "Storage path cannot be null").toAbsolutePath();
        this.mapper = Objects.requireNonNull(mapper, "JSON mapper cannot be null");
        this.moveOperation = Objects.requireNonNull(moveOperation, "Move operation cannot be null");
    }

    /**
     * Returns the absolute configured file target for service-level protection.
     *
     * @return absolute storage path.
     */
    @Override
    public Optional<Path> configuredPath() {
        return Optional.of(path);
    }

    @Override
    public KokoData load() throws StorageException {
        try {
            if (Files.notExists(path)) {
                return new KokoData();
            }
            String json = Files.readString(path, StandardCharsets.UTF_8);
            JsonDocument document;
            try (JsonParser parser = mapper.getFactory().createParser(json)) {
                JsonNode root = mapper.readTree(parser);
                if (parser.nextToken() != null) {
                    throw new IllegalArgumentException("Trailing JSON content is not allowed");
                }
                validateJsonTypes(root);
                document = mapper.treeToValue(root, JsonDocument.class);
            }
            return restore(document);
        } catch (IOException | IllegalArgumentException | SecurityException exception) {
            throw new StorageException("Could not load valid Koko data: " + exception.getMessage(), exception);
        }
    }

    /**
     * Saves through a sibling temporary file and atomic replacement.
     *
     * <p>If atomic replacement is unsupported, the failure is reported without
     * a non-atomic fallback, so the existing target remains the persisted
     * version. Temporary-file cleanup is best effort.
     *
     * @param data state to save.
     * @throws NullPointerException if data is null.
     * @throws StorageException if serialization, temporary-file creation, or
     *         atomic replacement fails.
     */
    @Override
    public void save(KokoData data) throws StorageException {
        Objects.requireNonNull(data, "Data cannot be null");
        String json;
        try {
            json = mapper.writeValueAsString(toDocument(data));
        } catch (IOException | IllegalArgumentException | SecurityException exception) {
            throw new StorageException("Could not serialize Koko data", exception);
        }

        Path temporaryPath = path.resolveSibling(path.getFileName() + ".tmp-" + UUID.randomUUID());
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(temporaryPath, json, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            replaceTarget(temporaryPath);
        } catch (AtomicMoveNotSupportedException exception) {
            throw new StorageException(
                    "Could not save Koko data because atomic replacement is not supported",
                    exception);
        } catch (IOException | SecurityException exception) {
            throw new StorageException("Could not safely save Koko data", exception);
        } finally {
            try {
                Files.deleteIfExists(temporaryPath);
            } catch (IOException ignored) {
                // The original save failure is more useful than temporary-file cleanup failure.
            }
        }
    }

    /**
     * Replaces the target with a temporary file using only an atomic move.
     *
     * @param temporaryPath sibling temporary file containing the serialized state.
     * @throws AtomicMoveNotSupportedException if the file system does not support atomic moves.
     * @throws IOException if the atomic replacement fails for another reason.
     */
    private void replaceTarget(Path temporaryPath) throws IOException {
        moveOperation.move(temporaryPath, path, StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING);
    }

    private static ObjectMapper createMapper() {
        JsonFactory factory = JsonFactory.builder()
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .build();
        return JsonMapper.builder(factory)
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
                .build();
    }

    private static JsonDocument toDocument(KokoData data) {
        List<JsonCard> cards = new ArrayList<>();
        for (VocabularyCard card : data.vocabularyCards()) {
            cards.add(new JsonCard(card.id().toString(), card.hiragana(), card.romaji(),
                    card.englishMeaning(), Map.of(
                            Mode.FLASHCARD.name(), toProgress(card.progressFor(Mode.FLASHCARD)),
                            Mode.TYPING.name(), toProgress(card.progressFor(Mode.TYPING)))));
        }
        List<JsonDeck> decks = new ArrayList<>();
        for (Deck deck : data.decks()) {
            decks.add(new JsonDeck(deck.id().toString(), deck.name(), deck.cardIds().stream()
                    .map(UUID::toString).toList()));
        }
        return new JsonDocument(CURRENT_SCHEMA_VERSION, cards, decks);
    }

    private static JsonProgress toProgress(ModeProgress progress) {
        return new JsonProgress(progress.mastery(), progress.nextDueDate().toString());
    }

    private static KokoData restore(JsonDocument document) {
        if (document == null) {
            throw new IllegalArgumentException("JSON document cannot be null");
        }
        validateDocumentShape(document);
        List<RestoredCard> cards = restoreCards(document.cards());
        Set<UUID> cardIds = cards.stream().map(RestoredCard::id).collect(Collectors.toSet());
        List<RestoredDeck> decks = restoreDecks(document.decks(), cardIds);
        return KokoData.restore(toDomainCards(cards), toDomainDecks(decks));
    }

    private static void validateDocumentShape(JsonDocument document) {
        if (document.schemaVersion() == null
                || document.schemaVersion() != CURRENT_SCHEMA_VERSION) {
            throw new IllegalArgumentException("Unsupported Koko data schema version");
        }
        if (document.cards() == null || document.decks() == null) {
            throw new IllegalArgumentException("Cards and decks must be arrays");
        }
    }

    private static void validateJsonTypes(JsonNode root) {
        if (root == null || !root.isObject()) {
            return;
        }
        requireInteger(root, "schemaVersion");
        validateCardsType(root.get("cards"));
        validateDecksType(root.get("decks"));
    }

    private static void validateCardsType(JsonNode cards) {
        if (cards == null || !cards.isArray()) {
            return;
        }
        for (JsonNode card : cards) {
            if (card != null && card.isObject()) {
                requireString(card, "id");
                requireString(card, "hiragana");
                requireString(card, "romaji");
                requireString(card, "englishMeaning");
                validateProgressType(card.get("progress"));
            }
        }
    }

    private static void validateProgressType(JsonNode progress) {
        if (progress == null || !progress.isObject()) {
            return;
        }
        validateModeProgressType(progress.get(Mode.FLASHCARD.name()));
        validateModeProgressType(progress.get(Mode.TYPING.name()));
    }

    private static void validateModeProgressType(JsonNode progress) {
        if (progress == null || !progress.isObject()) {
            return;
        }
        requireInteger(progress, "mastery");
        requireString(progress, "nextDueDate");
    }

    private static void validateDecksType(JsonNode decks) {
        if (decks == null || !decks.isArray()) {
            return;
        }
        for (JsonNode deck : decks) {
            if (deck != null && deck.isObject()) {
                requireString(deck, "id");
                requireString(deck, "name");
                JsonNode cardIds = deck.get("cardIds");
                if (cardIds != null && cardIds.isArray()) {
                    for (JsonNode cardId : cardIds) {
                        if (cardId != null && !cardId.isNull() && !cardId.isTextual()) {
                            throw new IllegalArgumentException("cardIds must contain strings");
                        }
                    }
                }
            }
        }
    }

    private static void requireInteger(JsonNode object, String fieldName) {
        JsonNode value = object.get(fieldName);
        if (value != null && !value.isNull()
                && (!value.isIntegralNumber() || !value.canConvertToInt())) {
            throw new IllegalArgumentException(fieldName + " must be an integer");
        }
    }

    private static void requireString(JsonNode object, String fieldName) {
        JsonNode value = object.get(fieldName);
        if (value != null && !value.isNull() && !value.isTextual()) {
            throw new IllegalArgumentException(fieldName + " must be a string");
        }
    }

    private static List<RestoredCard> restoreCards(List<JsonCard> cards) {
        Set<UUID> cardIds = new HashSet<>();
        List<RestoredCard> restoredCards = new ArrayList<>();
        for (JsonCard card : cards) {
            RestoredCard restoredCard = restoreCard(card);
            if (!cardIds.add(restoredCard.id())) {
                throw new IllegalArgumentException("Card ID is duplicated");
            }
            restoredCards.add(restoredCard);
        }
        return restoredCards;
    }

    private static RestoredCard restoreCard(JsonCard card) {
        if (card == null) {
            throw new IllegalArgumentException("Card entries cannot be null");
        }
        Map<String, JsonProgress> progress = requireProgress(card.progress());
        return new RestoredCard(parseUuid(card.id(), "card ID"),
                requireValue(card.hiragana(), "hiragana"),
                requireValue(card.romaji(), "romaji"),
                requireValue(card.englishMeaning(), "English meaning"),
                restoreProgress(progress.get(Mode.FLASHCARD.name())),
                restoreProgress(progress.get(Mode.TYPING.name())));
    }

    private static List<RestoredDeck> restoreDecks(List<JsonDeck> decks, Set<UUID> cardIds) {
        Set<UUID> deckIds = new HashSet<>();
        List<RestoredDeck> restoredDecks = new ArrayList<>();
        for (JsonDeck deck : decks) {
            if (deck == null) {
                throw new IllegalArgumentException("Deck entries cannot be null");
            }
            UUID id = parseUuid(deck.id(), "deck ID");
            if (!deckIds.add(id)) {
                throw new IllegalArgumentException("Deck ID is duplicated");
            }
            List<UUID> membership = restoreMembership(deck.cardIds(), cardIds);
            restoredDecks.add(new RestoredDeck(id, requireValue(deck.name(), "deck name"),
                    membership));
        }
        return restoredDecks;
    }

    private static List<VocabularyCard> toDomainCards(List<RestoredCard> cards) {
        return cards.stream().map(card -> VocabularyCard.restore(
                card.id(), card.hiragana(), card.romaji(), card.englishMeaning(),
                card.flashcardProgress(), card.typingProgress())).toList();
    }

    private static List<Deck> toDomainDecks(List<RestoredDeck> decks) {
        return decks.stream()
                .map(deck -> Deck.restore(deck.id(), deck.name(), deck.cardIds())).toList();
    }

    private static Map<String, JsonProgress> requireProgress(Map<String, JsonProgress> progress) {
        if (progress == null || !progress.keySet().equals(
                Set.of(Mode.FLASHCARD.name(), Mode.TYPING.name()))) {
            throw new IllegalArgumentException("Both learning mode progress records are required");
        }
        return progress;
    }

    private static List<UUID> restoreMembership(List<String> cardIds, Set<UUID> knownCardIds) {
        if (cardIds == null) {
            throw new IllegalArgumentException("Deck card references must be an array");
        }
        Set<UUID> membership = new HashSet<>();
        List<UUID> restored = new ArrayList<>();
        for (String cardId : cardIds) {
            UUID parsedId = parseUuid(cardId, "deck card ID");
            if (!knownCardIds.contains(parsedId)) {
                throw new IllegalArgumentException("Deck references an unknown card");
            }
            if (!membership.add(parsedId)) {
                throw new IllegalArgumentException("Deck card membership is duplicated");
            }
            restored.add(parsedId);
        }
        return restored;
    }

    private static ModeProgress restoreProgress(JsonProgress progress) {
        if (progress == null || progress.mastery() == null || progress.nextDueDate() == null) {
            throw new IllegalArgumentException("Progress is missing a required field");
        }
        return new ModeProgress(progress.mastery(), parseDate(progress.nextDueDate()));
    }

    private static UUID parseUuid(String value, String fieldName) {
        try {
            return UUID.fromString(requireValue(value, fieldName));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Invalid " + fieldName, exception);
        }
    }

    private static String requireValue(String value, String fieldName) {
        if (value == null) {
            throw new IllegalArgumentException(fieldName + " is required");
        }
        return value;
    }

    private static LocalDate parseDate(String value) {
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException exception) {
            throw new IllegalArgumentException("Invalid date", exception);
        }
    }

    private record JsonDocument(Integer schemaVersion, List<JsonCard> cards,
            List<JsonDeck> decks) {
    }

    private record JsonCard(String id, String hiragana, String romaji, String englishMeaning,
            Map<String, JsonProgress> progress) {
    }

    private record JsonProgress(Integer mastery, String nextDueDate) {
    }

    private record JsonDeck(String id, String name, List<String> cardIds) {
    }

    private record RestoredCard(UUID id, String hiragana, String romaji, String englishMeaning,
            ModeProgress flashcardProgress, ModeProgress typingProgress) {
    }

    private record RestoredDeck(UUID id, String name, List<UUID> cardIds) {
    }

    /**
     * Moves one path to another using the requested file-system options.
     *
     * <p>The package-private seam lets storage tests inject deterministic move
     * failures. Production storage uses {@link Files#move(Path, Path, CopyOption...)}.
     */
    @FunctionalInterface
    interface MoveOperation {

        /**
         * Moves a source path to a target path.
         *
         * @param source path to move.
         * @param target replacement path.
         * @param options move options.
         * @return the target path.
         * @throws IOException if the move cannot be completed.
         */
        Path move(Path source, Path target, CopyOption... options) throws IOException;
    }
}
