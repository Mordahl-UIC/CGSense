package dyncg.driver;

/** An error caused by the user's input; reported as a message without a stack trace. */
final class UsageException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    UsageException(String message) {
        super(message);
    }
}
