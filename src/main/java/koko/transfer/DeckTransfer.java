package koko.transfer;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.CopyOption;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

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
    private final TransferMoveOperation moveOperation;

    /**
     * Creates a transfer component using strict Jackson settings and UTF-8 files.
     */
    public DeckTransfer() {
        this(createMapper(), path -> Files.newOutputStream(path,
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE), Files::move);
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
        this(mapper, outputStreamFactory, Files::move);
    }

    /**
     * Creates a transfer component with injectable file-operation seams.
     *
     * @param mapper JSON mapper used for parsing and serialization.
     * @param outputStreamFactory factory used to open export destinations.
     * @param moveOperation operation used for atomic replacement.
     * @throws NullPointerException if an argument is null.
     */
    DeckTransfer(ObjectMapper mapper, OutputStreamFactory outputStreamFactory,
            TransferMoveOperation moveOperation) {
        this.mapper = Objects.requireNonNull(mapper, "JSON mapper cannot be null");
        this.outputStreamFactory = Objects.requireNonNull(outputStreamFactory,
                "Output stream factory cannot be null");
        this.moveOperation = Objects.requireNonNull(moveOperation, "Move operation cannot be null");
    }

    /**
     * Retains the final destination and its state after a native save selection.
     *
     * <p>On supported desktop platforms, returning a file means the chooser's
     * native replacement prompt was accepted when it was shown. The selected
     * final destination is captured immediately, before serialization. An absent
     * destination remains create-new even if a file subsequently appears there.
     */
    public static final class ConfirmedDestination {

        private final Path path;
        private final DestinationSnapshot snapshot;

        /**
         * Captures a chooser result that already has its final filename.
         *
         * @param chooserPath path returned by the native save chooser.
         * @throws DeckTransferException if the destination cannot be checked safely.
         * @throws NullPointerException if chooserPath is null.
         */
        public ConfirmedDestination(Path chooserPath) throws DeckTransferException {
            this(Objects.requireNonNull(chooserPath, "Chooser path cannot be null")
                    .toAbsolutePath(), inspectDestination(chooserPath));
        }

        /** Binds an inspected path to the snapshot captured for it. */
        private ConfirmedDestination(Path path, DestinationSnapshot snapshot) {
            this.path = path;
            this.snapshot = snapshot;
        }

        /**
         * Captures the final filename only when existing-file consent covers it.
         *
         * @param chooserPath non-null native save result.
         * @param destination final filename after suffix handling.
         * @return captured destination, or null when another native selection is needed.
         * @throws DeckTransferException if destination identity cannot be checked safely.
         * @throws NullPointerException if either path is null.
         */
        public static ConfirmedDestination fromNativeSelection(Path chooserPath, Path destination)
                throws DeckTransferException {
            Objects.requireNonNull(chooserPath, "Chooser path cannot be null");
            Path finalPath = Objects.requireNonNull(destination, "Destination cannot be null")
                    .toAbsolutePath();
            DestinationSnapshot existing = inspectDestination(finalPath);
            if (existing != null && !confirmationMatches(chooserPath, finalPath)) {
                return null;
            }
            return new ConfirmedDestination(finalPath, existing);
        }

        /** Returns the absolute final export path captured with this selection. */
        public Path path() {
            return path;
        }
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
     * files are protected by {@code CREATE_NEW}; callers must supply an explicit
     * native confirmation to replace one. Missing parent directories are not
     * created.
     *
     * @param document portable deck document to write.
     * @param destination new destination file.
     * @throws DeckTransferException if validation, serialization, or file writing fails.
     * @throws NullPointerException if document or destination is null.
     */
    public void write(PortableDeck document, Path destination) throws DeckTransferException {
        writeDocument(document, destination, null);
    }

    /**
     * Validates and writes a portable deck, honoring native-confirmed replacement.
     *
     * <p>Replacement is limited to the final path captured by the native selection,
     * and the destination must remain a regular file. The complete document is
     * serialized before an operation-owned sibling temporary file is created,
     * written, closed, and atomically moved into place. A destination captured
     * as absent keeps create-new behavior.
     *
     * <p>Size, modification time, and file identity where the provider exposes it
     * provide best-effort change detection. A missing file key does not block
     * replacement, but a different file with the same size and modification time
     * can go undetected at the approved path, even before export starts.
     *
     * <p>The native-dialog-to-capture and final-check-to-move intervals also allow
     * races, including concurrent writers and parent-directory swaps. This is
     * not a locking protocol and does not promise power-loss durability.
     *
     * @param document portable deck document to write.
     * @param confirmation captured native selection and its final destination.
     * @throws DeckTransferException if validation, serialization, identity checks,
     *         or file writing fails.
     * @throws NullPointerException if document or confirmation is null.
     */
    public void write(PortableDeck document, ConfirmedDestination confirmation)
            throws DeckTransferException {
        Objects.requireNonNull(confirmation, "Confirmed destination cannot be null");
        writeDocument(document, confirmation.path, confirmation.snapshot);
    }

    /** Serializes once; a null approved snapshot always requires create-new behavior. */
    private void writeDocument(PortableDeck document, Path destination, DestinationSnapshot approved)
            throws DeckTransferException {
        Objects.requireNonNull(document, "Portable deck cannot be null");
        Objects.requireNonNull(destination, "Destination path cannot be null");
        try {
            Path finalDestination = destination.toAbsolutePath();
            DestinationSnapshot current = inspectDestination(finalDestination);
            if (approved != null && (current == null || !approved.matches(current))) {
                throw new DeckTransferException("The approved export destination changed; "
                        + "choose the destination again");
            }
            validate(document);
            String json = mapper.writeValueAsString(document);
            byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
            if (approved != null) {
                replaceExisting(bytes, finalDestination, approved);
            } else {
                writeNewFile(bytes, finalDestination);
            }
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
     * Writes to a unique sibling and atomically replaces the approved target.
     *
     * @param bytes serialized UTF-8 document bytes.
     * @param destination approved existing regular file.
     * @param approvedSnapshot target attributes captured before writing.
     * @throws DeckTransferException if the target changes or replacement fails.
     */
    private void replaceExisting(byte[] bytes, Path destination,
            DestinationSnapshot approvedSnapshot) throws DeckTransferException {
        Path temporary = destination.resolveSibling(destination.getFileName() + ".tmp-"
                + UUID.randomUUID());
        boolean created = false;
        try {
            try (OutputStream output = outputStreamFactory.open(temporary)) {
                created = true;
                output.write(bytes);
            } catch (IOException | RuntimeException exception) {
                throw transferFailure("Could not prepare replacement export to '" + destination
                        + "'. The existing file was preserved: ", exception);
            }

            DestinationSnapshot current = inspectDestination(destination);
            if (current == null || !approvedSnapshot.matches(current)) {
                throw new DeckTransferException("The approved export destination changed before "
                        + "replacement; the existing file was preserved");
            }
            try {
                moveOperation.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException exception) {
                throw new DeckTransferException("Could not replace the export because atomic "
                        + "replacement is not supported; the existing file was preserved",
                        exception);
            } catch (IOException | RuntimeException exception) {
                throw transferFailure("Could not replace the export safely; the existing file "
                        + "was preserved: ", exception);
            }
        } finally {
            if (created) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException | SecurityException ignored) {
                    // The replacement result or original failure is more useful to the caller.
                }
            }
        }
    }

    /**
     * Checks whether a chooser result identifies the normalized final destination.
     *
     * @param chooserPath path returned by the chooser.
     * @param destination normalized final destination.
     * @return true when the paths identify the same destination.
     * @throws DeckTransferException if identity cannot be checked safely.
     */
    private static boolean confirmationMatches(Path chooserPath, Path destination)
            throws DeckTransferException {
        Path absoluteChooserPath = chooserPath.toAbsolutePath();
        if (absoluteChooserPath.equals(destination)) {
            return true;
        }
        if (Files.isSymbolicLink(absoluteChooserPath)) {
            return false;
        }
        DestinationSnapshot chooserSnapshot = inspectDestination(absoluteChooserPath);
        DestinationSnapshot destinationSnapshot = inspectDestination(destination);
        if (chooserSnapshot == null || destinationSnapshot == null) {
            return false;
        }
        try {
            return Files.isSameFile(absoluteChooserPath, destination);
        } catch (IOException | RuntimeException exception) {
            throw new DeckTransferException("Could not verify that native replacement consent "
                    + "covers the final export destination", exception);
        }
    }

    /**
     * Reads a destination without following a final symbolic link.
     *
     * @param path destination path.
     * @return regular-file snapshot, or null when absent.
     * @throws DeckTransferException if the path is nonregular or cannot be inspected.
     */
    private static DestinationSnapshot inspectDestination(Path path) throws DeckTransferException {
        try {
            BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS);
            if (attributes.isSymbolicLink()) {
                throw new DeckTransferException("Export destination cannot be a symbolic link");
            }
            if (!attributes.isRegularFile()) {
                throw new DeckTransferException("Export destination must be a regular file");
            }
            return new DestinationSnapshot(attributes.fileKey(), attributes.size(),
                    attributes.lastModifiedTime());
        } catch (NoSuchFileException exception) {
            return null;
        } catch (DeckTransferException exception) {
            throw exception;
        } catch (IOException | RuntimeException exception) {
            throw new DeckTransferException("Could not safely inspect export destination", exception);
        }
    }

    /** Adds recoverable guidance while retaining the original file-operation failure. */
    private static DeckTransferException transferFailure(String message, Throwable cause) {
        return new DeckTransferException(message + describe(cause), cause);
    }

    /**
     * Snapshot used to detect observable changes before an approved replacement.
     *
     * @param fileKey provider identity, or null when unavailable.
     * @param size file size in bytes.
     * @param modifiedTime last modification time.
     */
    private record DestinationSnapshot(Object fileKey, long size, FileTime modifiedTime) {

        /**
         * Requires matching size, modification time, and identity where it is exposed.
         *
         * <p>Providers that expose no file key, including the Windows default provider,
         * compare on size and modification time alone. They cannot distinguish a
         * different file with matching metadata at the approved path.
         */
        private boolean matches(DestinationSnapshot other) {
            return Objects.equals(fileKey, other.fileKey)
                    && size == other.size && modifiedTime.equals(other.modifiedTime);
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

    /**
     * Moves an operation-owned temporary file to its final destination.
     *
     * <p>The package-private seam lets transfer tests exercise atomic replacement
     * failures without changing production file operations.
     */
    @FunctionalInterface
    interface TransferMoveOperation {

        /**
         * Moves a temporary file to its destination.
         *
         * @param source operation-owned temporary file.
         * @param target final export destination.
         * @param options required atomic replacement options.
         * @return the destination path.
         * @throws IOException if the move cannot be completed.
         */
        Path move(Path source, Path target, CopyOption... options) throws IOException;
    }
}
