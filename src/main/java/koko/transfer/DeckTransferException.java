package koko.transfer;

/**
 * Recoverable failure while reading or writing a portable deck document.
 */
public final class DeckTransferException extends Exception {

    /**
     * Creates a transfer failure with a contextual message.
     *
     * @param message actionable failure description.
     */
    public DeckTransferException(String message) {
        super(message);
    }

    /**
     * Creates a transfer failure while retaining the underlying cause.
     *
     * @param message actionable failure description.
     * @param cause underlying file, encoding, serialization, or format failure.
     */
    public DeckTransferException(String message, Throwable cause) {
        super(message, cause);
    }
}
