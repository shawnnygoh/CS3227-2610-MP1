package koko.storage;

import java.nio.file.Path;
import java.util.Optional;

import koko.model.KokoData;

/**
 * Boundary for loading and saving Koko's application state.
 */
public interface Storage {

    /**
     * Returns the configured file target when this storage is file-backed.
     *
     * <p>The service uses this optional path to prevent portable exports from
     * replacing Koko's own persisted library. Non-file storage implementations
     * may return {@link Optional#empty()}.
     *
     * @return configured storage file, or empty when no file target is exposed.
     */
    default Optional<Path> configuredPath() {
        return Optional.empty();
    }

    /**
     * Loads the complete application state.
     *
     * @return loaded state, or empty state when no storage file exists.
     * @throws StorageException if the file cannot be read or is invalid.
     */
    KokoData load() throws StorageException;

    /**
     * Saves the complete application state.
     *
     * <p>If saving fails, previously persisted data remains unchanged, or absent
     * if nothing was saved before. Callers must not publish the supplied state
     * when this method throws. This contract does not promise power-loss
     * durability or protection from concurrent writers.
     *
     * @param data state to save.
     * @throws StorageException if serialization or replacement fails.
     */
    void save(KokoData data) throws StorageException;
}
