package koko.storage;

/**
 * Controlled failure reported by a storage implementation.
 */
public final class StorageException extends Exception {

    /**
     * Creates a storage failure with a message and underlying cause.
     *
     * @param message failure description.
     * @param cause underlying failure.
     */
    public StorageException(String message, Throwable cause) {
        super(message, cause);
    }
}
