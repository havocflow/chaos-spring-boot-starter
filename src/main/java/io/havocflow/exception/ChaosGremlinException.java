package io.havocflow.exception;

/**
 * Thrown by HavocFlow when the configured exception type cannot be instantiated.
 *
 * <p>Also serves as the default exception when no specific type is configured,
 * making it easy to distinguish chaos-injected failures from real ones in tests.
 */
public class ChaosGremlinException extends RuntimeException {

    public ChaosGremlinException(String message) {
        super(message);
    }

    public ChaosGremlinException(String message, Throwable cause) {
        super(message, cause);
    }
}
