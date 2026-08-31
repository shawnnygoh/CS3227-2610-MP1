package koko.controller;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Function;

import koko.transfer.DeckTransfer.ConfirmedDestination;
import koko.transfer.DeckTransferException;

/**
 * Applies the filename policy used by the portable transfer controls.
 *
 * <p>Suggested names are sanitized because they originate from a deck name.
 * Names edited in the save chooser are only given a normalized JSON suffix.
 */
public final class TransferFileNames {

    private static final String JSON_SUFFIX = ".json";

    private TransferFileNames() {
    }

    /**
     * Suggests a safe JSON filename derived from a deck name.
     *
     * @param deckName current deck name.
     * @return suggested filename with a lowercase JSON suffix.
     * @throws NullPointerException if deckName is null.
     */
    public static String suggestExportFileName(String deckName) {
        Objects.requireNonNull(deckName, "Deck name cannot be null");
        String sourceName = trimTrailingSpacesAndDots(deckName);
        String sanitizedName = sanitize(sourceName);
        boolean alreadyJson = endsWithJson(sanitizedName);
        String sourceStem = alreadyJson
                ? sourceName.substring(0, sourceName.length() - JSON_SUFFIX.length()) : sourceName;
        String sanitizedStem = trimTrailingSpacesAndDots(sanitize(sourceStem));
        if (sanitizedStem.isEmpty() || !hasUsableStemContent(sourceStem)) {
            return "koko-deck.json";
        }
        if (isWindowsReservedBasename(sanitizedStem)) {
            sanitizedStem = "_" + sanitizedStem;
        }
        return sanitizedStem + JSON_SUFFIX;
    }

    /**
     * Normalizes only the terminal filename suffix of a chosen destination.
     *
     * @param chosenDestination destination selected by the user.
     * @return destination with a lowercase JSON suffix and the same parent path.
     * @throws IllegalArgumentException if the path has no filename component.
     * @throws NullPointerException if chosenDestination is null.
     */
    public static Path normalizeDestination(Path chosenDestination) {
        Objects.requireNonNull(chosenDestination, "Destination path cannot be null");
        Path fileName = chosenDestination.getFileName();
        if (fileName == null || fileName.toString().isEmpty()) {
            throw new IllegalArgumentException("Destination must include a filename");
        }
        String chosenName = fileName.toString();
        String normalizedName = endsWithJson(chosenName)
                ? chosenName.substring(0, chosenName.length() - JSON_SUFFIX.length()) + JSON_SUFFIX
                : chosenName + JSON_SUFFIX;
        Path parent = chosenDestination.getParent();
        return parent == null ? Path.of(normalizedName) : parent.resolve(normalizedName);
    }

    /**
     * Resolves a native save selection without transferring consent to another file.
     *
     * <p>A different existing final destination is presented in the native chooser
     * again. A new final filename keeps create-new behavior. The callback keeps
     * native interaction in the controller and permits headless decision tests.
     *
     * @param deckName name used for the initial filename suggestion.
     * @param chooser native chooser receiving each suggested destination.
     * @return captured final destination, or null after canceling any chooser.
     * @throws DeckTransferException if the destination cannot be checked safely.
     * @throws IllegalArgumentException if a selected path has no filename.
     * @throws NullPointerException if deckName or chooser is null.
     */
    static ConfirmedDestination chooseExportDestination(String deckName,
            Function<Path, Path> chooser) throws DeckTransferException {
        Objects.requireNonNull(chooser, "Chooser cannot be null");
        Path suggestion = Path.of(suggestExportFileName(deckName));
        while (true) {
            Path chosen = chooser.apply(suggestion);
            if (chosen == null) {
                return null;
            }
            Path destination = normalizeDestination(chosen);
            ConfirmedDestination confirmed = ConfirmedDestination.fromNativeSelection(
                    chosen, destination);
            if (confirmed != null) {
                return confirmed;
            }
            suggestion = destination;
        }
    }

    /**
     * Replaces filename-invalid characters while preserving valid Unicode and spaces.
     *
     * @param value filename stem to sanitize.
     * @return sanitized filename stem.
     */
    private static String sanitize(String value) {
        StringBuilder sanitized = new StringBuilder(value.length());
        value.codePoints().forEach(codePoint -> {
            if (Character.isISOControl(codePoint) || isFilenameInvalid(codePoint)) {
                sanitized.append('_');
            } else {
                sanitized.appendCodePoint(codePoint);
            }
        });
        return sanitized.toString();
    }

    /**
     * Identifies characters that are invalid in common desktop filenames.
     *
     * @param codePoint character to check.
     * @return true when the character is invalid.
     */
    private static boolean isFilenameInvalid(int codePoint) {
        return switch (codePoint) {
            case '/', '\\', ':', '*', '?', '"', '<', '>', '|' -> true;
            default -> false;
        };
    }

    /**
     * Removes trailing spaces and periods from a suggested filename stem.
     *
     * @param value filename stem to trim.
     * @return trimmed filename stem.
     */
    private static String trimTrailingSpacesAndDots(String value) {
        int end = value.length();
        while (end > 0 && (value.charAt(end - 1) == ' ' || value.charAt(end - 1) == '.')) {
            end--;
        }
        return value.substring(0, end);
    }

    /**
     * Checks whether the source stem contains usable non-replaced content.
     *
     * @param sourceStem original source stem.
     * @return true when the stem can produce a meaningful filename.
     */
    private static boolean hasUsableStemContent(String sourceStem) {
        return sourceStem.codePoints().anyMatch(codePoint -> codePoint != ' '
                && codePoint != '.' && !Character.isISOControl(codePoint)
                && !isFilenameInvalid(codePoint));
    }

    /**
     * Checks whether a name ends with JSON in any letter case.
     *
     * @param value filename or deck name to check.
     * @return true when value has a terminal JSON suffix.
     */
    private static boolean endsWithJson(String value) {
        return value.toLowerCase(Locale.ROOT).endsWith(JSON_SUFFIX);
    }

    /**
     * Checks common Windows device basenames before returning a suggestion.
     *
     * @param stem sanitized filename stem.
     * @return true when the basename is reserved by Windows.
     */
    private static boolean isWindowsReservedBasename(String stem) {
        String basename = stem;
        int firstDot = stem.indexOf('.');
        if (firstDot >= 0) {
            basename = stem.substring(0, firstDot);
        }
        String upperCaseBasename = basename.toUpperCase(Locale.ROOT);
        return upperCaseBasename.equals("CON") || upperCaseBasename.equals("PRN")
                || upperCaseBasename.equals("AUX") || upperCaseBasename.equals("NUL")
                || isNumberedDevice(upperCaseBasename, "COM")
                || isNumberedDevice(upperCaseBasename, "LPT");
    }

    /**
     * Checks one numbered COM or LPT Windows device basename.
     *
     * @param basename uppercase basename to check.
     * @param prefix device prefix.
     * @return true when basename is a reserved numbered device.
     */
    private static boolean isNumberedDevice(String basename, String prefix) {
        return basename.length() == prefix.length() + 1
                && basename.startsWith(prefix)
                && basename.charAt(prefix.length()) >= '1'
                && basename.charAt(prefix.length()) <= '9';
    }
}
