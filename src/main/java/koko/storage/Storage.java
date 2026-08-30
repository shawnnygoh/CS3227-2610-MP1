package koko.storage;

import koko.model.KokoData;

/**
 * Boundary for loading and saving Koko's application state.
 */
public interface Storage {

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
