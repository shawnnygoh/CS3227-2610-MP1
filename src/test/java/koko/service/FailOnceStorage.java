package koko.service;

import koko.model.KokoData;
import koko.storage.JsonStorage;
import koko.storage.Storage;
import koko.storage.StorageException;

/**
 * Fails one requested save before delegating to real JSON persistence.
 *
 * <p>Used to check that a save failure leaves the file on disk untouched while
 * the service keeps its previously published state.
 */
final class FailOnceStorage implements Storage {

    private final JsonStorage delegate;
    private int saveInvocations;
    private boolean failNextSave;

    FailOnceStorage(JsonStorage delegate) {
        this.delegate = delegate;
    }

    /**
     * Returns how many saves were attempted, including the one that was failed.
     *
     * @return attempted save count.
     */
    int saveInvocations() {
        return saveInvocations;
    }

    /** Makes the next save fail once before delegating resumes. */
    void failNextSave() {
        failNextSave = true;
    }

    @Override
    public KokoData load() throws StorageException {
        return delegate.load();
    }

    @Override
    public void save(KokoData data) throws StorageException {
        saveInvocations++;
        if (failNextSave) {
            failNextSave = false;
            throw new StorageException("forced save failure", null);
        }
        delegate.save(data);
    }
}
