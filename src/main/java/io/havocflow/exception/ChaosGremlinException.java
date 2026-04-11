package io.havocflow.exception;

/**
 * Thrown by HavocFlow when the configured exception type cannot be instantiated,
 * or used directly as the default exception when no specific type is configured.
 *
 * <p>Extending {@link RuntimeException} means callers are not forced to declare
 * a checked exception. It also makes it easy to distinguish chaos-injected failures
 * from real ones in tests:
 *
 * <pre>{@code
 * assertThatThrownBy(() -> service.doWork())
 *     .isInstanceOf(ChaosGremlinException.class);
 * }</pre>
 */
public class ChaosGremlinException extends RuntimeException {

    /**
     * Constructs a new exception with the given message.
     *
     * @param message human-readable description of the injected failure
     */
    public ChaosGremlinException(String message) {
        super(message);
    }

    /**
     * Constructs a new exception with a message and a cause.
     *
     * @param message human-readable description of the injected failure
     * @param cause   the underlying cause
     */
    public ChaosGremlinException(String message, Throwable cause) {
        super(message, cause);
    }
}
