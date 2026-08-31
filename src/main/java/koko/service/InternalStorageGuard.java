package koko.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Optional;

import koko.transfer.DeckTransferException;

/**
 * Protects Koko's own storage file from being used as a portable export destination.
 *
 * <p>Replacing the internal library with a portable deck document would discard
 * every card, deck, and progress record, so the service applies this guard before
 * any portable file operation begins.
 */
final class InternalStorageGuard {

    private InternalStorageGuard() {
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
     * @param configuredStoragePath configured internal storage file, or empty when
     *        the storage implementation exposes no file target.
     * @param destination requested export path.
     * @throws DeckTransferException if the path is protected or cannot be checked.
     */
    static void rejectAlias(Optional<Path> configuredStoragePath, Path destination)
            throws DeckTransferException {
        if (configuredStoragePath.isEmpty()) {
            return;
        }
        Path destinationPath = destination.toAbsolutePath();
        Path storagePath = configuredStoragePath.get().toAbsolutePath();
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
}
