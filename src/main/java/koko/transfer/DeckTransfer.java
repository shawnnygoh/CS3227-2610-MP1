package koko.transfer;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;

import koko.model.Deck;
import koko.model.VocabularyCard;

/**
 * Reads and writes Koko's headless, single-deck portable JSON format.
 *
 * <p>The portable format is deliberately separate from Koko's internal storage
 * schema. It contains only {@code schemaVersion}, {@code deckName}, and an
 * ordered {@code cards} array. Card IDs, progress, dates, and other application
 * metadata never cross this boundary.
 */
public final class DeckTransfer {

    /** The only portable schema version currently supported. */
    public static final int CURRENT_SCHEMA_VERSION = 1;
    private static final Set<String> DOCUMENT_FIELDS = Set.of(
            "schemaVersion", "deckName", "cards");
    private static final Set<String> CARD_FIELDS = Set.of(
            "hiragana", "romaji", "englishMeaning");
    private final ObjectMapper mapper;
    private final OutputStreamFactory outputStreamFactory;

    /**
     * Creates a transfer component using strict Jackson settings and UTF-8 files.
     */
    public DeckTransfer() {
        this(createMapper(), path -> Files.newOutputStream(path,
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE));
    }

    /**
     * Creates a transfer component with injectable serialization and output seams.
     *
     * <p>The production constructor supplies strict JSON and create-new file behavior.
     * This seam lets package-level tests force serialization or output failures without
     * changing the ownership and cleanup rules used by production exports.
     *
     * @param mapper JSON mapper used for parsing and serialization.
     * @param outputStreamFactory factory used to open export destinations.
     * @throws NullPointerException if mapper or outputStreamFactory is null.
     */
    DeckTransfer(ObjectMapper mapper, OutputStreamFactory outputStreamFactory) {
        this.mapper = Objects.requireNonNull(mapper, "JSON mapper cannot be null");
        this.outputStreamFactory = Objects.requireNonNull(outputStreamFactory,
                "Output stream factory cannot be null");
    }

    /**
     * Reads, strictly validates, and returns one portable deck document.
     *
     * @param source source JSON file.
     * @return validated portable deck contents.
     * @throws DeckTransferException if the source is missing, unreadable, malformed,
     *         incorrectly encoded, or violates the portable format.
     * @throws NullPointerException if source is null.
     */
    public PortableDeck read(Path source) throws DeckTransferException {
        Objects.requireNonNull(source, "Source path cannot be null");
        try {
            byte[] bytes = Files.readAllBytes(source);
            String json = decodeUtf8(bytes);
            try (JsonParser parser = mapper.getFactory().createParser(json)) {
                JsonNode root = mapper.readTree(parser);
                if (parser.nextToken() != null) {
                    throw invalid("Trailing JSON content is not allowed");
                }
                return parseDocument(root);
            }
        } catch (DeckTransferException exception) {
            throw new DeckTransferException("Could not import portable deck from '" + source
                    + "': " + exception.getMessage(), exception);
        } catch (IOException | RuntimeException exception) {
            throw new DeckTransferException("Could not import portable deck from '" + source
                    + "'. Check that it is a readable UTF-8 JSON file: "
                    + describe(exception), exception);
        }
    }

    /**
     * Validates a portable document supplied by a service caller.
     *
     * <p>The record constructor only protects null references. This operation
     * applies the complete portable schema, text, Unicode, and document-wide
     * uniqueness rules before a caller can apply the document.
     *
     * @param document portable deck document to validate.
     * @throws DeckTransferException if the document is invalid or unsupported.
     * @throws NullPointerException if document is null.
     */
    public void validate(PortableDeck document) throws DeckTransferException {
        Objects.requireNonNull(document, "Portable deck cannot be null");
        validateDocument(document);
    }

    /**
     * Validates and creates a new destination file containing one portable deck document.
     *
     * <p>The destination is opened only after serialization completes. Existing
     * files, directories, and symbolic links are protected by {@code CREATE_NEW};
     * callers must choose a new filename when the destination already exists.
     * Missing parent directories are not created.
     *
     * @param document portable deck document to write.
     * @param destination new destination file.
     * @throws DeckTransferException if validation, serialization, or file writing fails.
     * @throws NullPointerException if document or destination is null.
     */
    public void write(PortableDeck document, Path destination) throws DeckTransferException {
        Objects.requireNonNull(document, "Portable deck cannot be null");
        Objects.requireNonNull(destination, "Destination path cannot be null");
        try {
            validate(document);
            String json = mapper.writeValueAsString(document);
            byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
            writeNewFile(bytes, destination);
        } catch (DeckTransferException exception) {
            throw exception;
        } catch (IOException | RuntimeException exception) {
            throw new DeckTransferException("Could not export portable deck to '" + destination
                    + "'. Choose a new filename and check the destination: "
                    + describe(exception), exception);
        }
    }

    /**
     * Creates a destination, writes the serialized document, and cleans up a partial file.
     *
     * <p>Cleanup is attempted only after the factory reports that the destination was
     * opened, so a failed create-new open cannot remove an existing path. All opening,
     * writing, closing, and cleanup-related failures are reported through the checked
     * transfer exception contract.
     *
     * @param bytes serialized UTF-8 document bytes.
     * @param destination new destination path owned by this export attempt.
     * @throws DeckTransferException if opening, writing, or closing the destination fails.
     */
    private void writeNewFile(byte[] bytes, Path destination) throws DeckTransferException {
        boolean created = false;
        try (OutputStream output = outputStreamFactory.open(destination)) {
            created = true;
            output.write(bytes);
        } catch (IOException | RuntimeException exception) {
            if (created) {
                try {
                    Files.deleteIfExists(destination);
                } catch (IOException | SecurityException cleanupException) {
                    exception.addSuppressed(cleanupException);
                }
            }
            throw new DeckTransferException("Could not complete export to '" + destination
                    + "'. Choose a new filename. The incomplete file was cleaned up when possible: "
                    + describe(exception), exception);
        }
    }

    /**
     * Parses and validates the complete portable document tree before returning it.
     *
     * <p>This boundary enforces the single-deck shape, supported schema version, exact
     * root fields, card order, and document-wide validation without mutating application
     * state.
     *
     * @param root parsed JSON root value.
     * @return fully validated portable deck document.
     * @throws DeckTransferException if the root shape, fields, version, or cards are invalid.
     */
    private static PortableDeck parseDocument(JsonNode root) throws DeckTransferException {
        requireObject(root, "The root JSON value must be an object");
        requireExactFields(root, DOCUMENT_FIELDS, "The portable document");

        JsonNode version = root.get("schemaVersion");
        if (version == null || !version.isInt()) {
            throw invalid("schemaVersion must be an integer exactly equal to 1");
        }
        if (version.intValue() != CURRENT_SCHEMA_VERSION) {
            throw invalid("Unsupported portable deck schema version: " + version);
        }

        JsonNode deckName = requireText(root.get("deckName"), "deckName");
        JsonNode cards = root.get("cards");
        if (cards == null || !cards.isArray()) {
            throw invalid("cards must be an array");
        }

        List<PortableCard> parsedCards = new ArrayList<>();
        int index = 0;
        for (JsonNode card : cards) {
            parsedCards.add(parseCard(card, index));
            index++;
        }
        PortableDeck document = new PortableDeck(CURRENT_SCHEMA_VERSION, deckName.textValue(),
                parsedCards);
        validateDocument(document);
        return document;
    }

    /**
     * Parses one ordered card object while enforcing its exact portable field set.
     *
     * <p>Field values must be JSON strings; domain character and duplicate-identity rules
     * are applied by {@link #validateDocument(PortableDeck)} after the whole document is parsed.
     *
     * @param card JSON object for one card.
     * @param index zero-based card position used in failure messages.
     * @return parsed portable card text.
     * @throws DeckTransferException if the card is not an object with the required text fields.
     */
    private static PortableCard parseCard(JsonNode card, int index) throws DeckTransferException {
        requireObject(card, "Card at index " + index + " must be an object");
        requireExactFields(card, CARD_FIELDS, "Card at index " + index);
        String hiragana = requireText(card.get("hiragana"), "Card " + index + " hiragana")
                .textValue();
        String romaji = requireText(card.get("romaji"), "Card " + index + " romaji")
                .textValue();
        String englishMeaning = requireText(card.get("englishMeaning"),
                "Card " + index + " englishMeaning").textValue();
        return new PortableCard(hiragana, romaji, englishMeaning);
    }

    /**
     * Applies the portable document's schema, name, Unicode, content, and uniqueness rules.
     *
     * <p>Validation is deliberately independent of service mutation: matching cards are
     * not resolved, new IDs are not allocated, and no progress or storage state is changed
     * here. The document must be safe to apply or export before either operation proceeds.
     *
     * @param document portable deck document to validate.
     * @throws DeckTransferException if the version, name, Unicode text, card content, or
     *         vocabulary uniqueness is invalid.
     */
    private static void validateDocument(PortableDeck document) throws DeckTransferException {
        if (document.schemaVersion() != CURRENT_SCHEMA_VERSION) {
            throw invalid("Unsupported portable deck schema version: "
                    + document.schemaVersion());
        }
        try {
            Deck.normalizeName(document.deckName());
        } catch (RuntimeException exception) {
            throw invalid("deckName is invalid: " + describe(exception), exception);
        }
        // JSON escapes can introduce unpaired surrogates even in a valid UTF-8 file.
        // Reject them before persistence or export could lose the original text.
        if (!StandardCharsets.UTF_8.newEncoder().canEncode(document.deckName())) {
            throw invalid("deckName must contain valid Unicode text");
        }

        List<PortableCard> validatedCards = new ArrayList<>();
        for (int index = 0; index < document.cards().size(); index++) {
            PortableCard card = document.cards().get(index);
            if (card == null) {
                throw invalid("Card at index " + index + " cannot be null");
            }
            try {
                VocabularyCard.validateContent(card.hiragana(), card.romaji(),
                        card.englishMeaning());
            } catch (RuntimeException exception) {
                throw invalid("Card at index " + index + " has invalid text: "
                        + describe(exception), exception);
            }
            boolean duplicate = validatedCards.stream().anyMatch(previous ->
                    VocabularyCard.sameIdentity(previous.hiragana(), previous.englishMeaning(),
                            card.hiragana(), card.englishMeaning()));
            if (duplicate) {
                throw invalid("Card at index " + index
                        + " duplicates vocabulary in the document");
            }
            validatedCards.add(card);
        }
    }

    private static void requireObject(JsonNode value, String message) throws DeckTransferException {
        if (value == null || !value.isObject()) {
            throw invalid(message);
        }
    }

    private static JsonNode requireText(JsonNode value, String fieldName)
            throws DeckTransferException {
        if (value == null || !value.isTextual()) {
            throw invalid(fieldName + " must be a JSON string");
        }
        return value;
    }

    /**
     * Requires a JSON object to contain exactly the fields allowed by its boundary schema.
     *
     * <p>Both missing and unknown fields fail validation, preventing portable documents from
     * silently omitting required data or carrying internal IDs, progress, or other metadata.
     *
     * @param object JSON object whose fields are checked.
     * @param expected complete allowed field set.
     * @param context description included in the validation failure.
     * @throws DeckTransferException if the actual field set differs from expected.
     */
    private static void requireExactFields(JsonNode object, Set<String> expected, String context)
            throws DeckTransferException {
        Set<String> actual = new HashSet<>();
        Iterator<String> fields = object.fieldNames();
        while (fields.hasNext()) {
            actual.add(fields.next());
        }
        if (!actual.equals(expected)) {
            throw invalid(context + " must contain exactly these fields: " + expected);
        }
    }

    /**
     * Decodes bytes with a UTF-8 decoder that reports malformed or unmappable input.
     *
     * <p>Reporting errors instead of silently replacing invalid sequences preserves the
     * portable contract and prevents corrupted text from reaching JSON parsing or storage.
     *
     * @param bytes source bytes read from the portable file.
     * @return strictly decoded UTF-8 text.
     * @throws CharacterCodingException if the bytes are not valid UTF-8.
     */
    private static String decodeUtf8(byte[] bytes) throws CharacterCodingException {
        return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString();
    }

    private static DeckTransferException invalid(String message) {
        return new DeckTransferException(message);
    }

    private static DeckTransferException invalid(String message, Throwable cause) {
        return new DeckTransferException(message, cause);
    }

    private static String describe(Throwable exception) {
        return exception.getMessage() == null
                ? exception.getClass().getSimpleName() : exception.getMessage();
    }

    /**
     * Creates the strict mapper used at the portable JSON boundary.
     *
     * <p>Duplicate properties are rejected by the parser, and scalar coercion is disabled
     * so values must retain their declared JSON types during validation.
     *
     * @return configured mapper for portable JSON documents.
     */
    private static ObjectMapper createMapper() {
        JsonFactory factory = JsonFactory.builder()
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .build();
        return JsonMapper.builder(factory)
                .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
                .build();
    }

    /**
     * Opens the new output stream used by an export attempt.
     *
     * <p>The package-private seam allows tests to inject write and close failures while
     * production uses a create-new stream. The caller remains responsible for closing the
     * returned stream and cleaning up a file opened by the attempt when writing fails.
     */
    @FunctionalInterface
    interface OutputStreamFactory {

        /**
         * Opens a new output stream for a destination.
         *
         * @param destination output destination.
         * @return opened output stream.
         * @throws IOException if the stream cannot be opened.
         */
        OutputStream open(Path destination) throws IOException;
    }
}
