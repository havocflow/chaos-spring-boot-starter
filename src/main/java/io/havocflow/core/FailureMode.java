package io.havocflow.core;

/**
 * Describes the type of chaos that the {@link ChaosEngine} has decided to inject
 * for a single method invocation.
 *
 * <p>The engine always returns one of these four modes from
 * {@link ChaosEngine#decide(io.havocflow.annotation.InjectChaos, String)}.
 * The {@link io.havocflow.aop.ChaosAspect} uses the mode to drive its behaviour.
 */
public enum FailureMode {

    /**
     * No chaos is injected. The method proceeds normally.
     * Returned when {@code chaos.enabled=false}, dry-run is active, or
     * both latency and failure rate evaluate to zero.
     */
    NONE,

    /**
     * Artificial latency is injected before the method call, but no exception is thrown.
     */
    LATENCY,

    /**
     * An exception is thrown; no artificial latency is added beforehand.
     */
    EXCEPTION,

    /**
     * Artificial latency is injected first, then an exception is thrown.
     * Simulates a slow, eventually-failing dependency.
     */
    LATENCY_AND_EXCEPTION
}
