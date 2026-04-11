package io.havocflow.core;

import io.havocflow.annotation.InjectChaos;
import io.havocflow.autoconfigure.ChaosProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Random;

/**
 * Core decision engine. Given an {@link InjectChaos} annotation on a method,
 * resolves the effective latency, failure rate, and exception type — then decides
 * whether to inject chaos for this particular invocation.
 *
 * <p>Resolution priority (highest to lowest):
 * <ol>
 *   <li>Named scenario from {@code chaos.scenarios.*}</li>
 *   <li>Inline annotation attributes ({@code latency}, {@code failureRate}, {@code exception})</li>
 *   <li>Global {@code chaos.default-failure-rate}</li>
 * </ol>
 *
 * <p>When {@code chaos.dry-run=true} the engine logs the decision that <em>would</em>
 * have been made and always returns {@link ChaosDecision#none()}.
 */
public class ChaosEngine {

    private static final Logger log = LoggerFactory.getLogger(ChaosEngine.class);

    private final ChaosProperties properties;
    private final Random random = new Random();

    /**
     * Constructs a {@code ChaosEngine} backed by the given configuration.
     *
     * @param properties the HavocFlow configuration; must not be {@code null}
     */
    public ChaosEngine(ChaosProperties properties) {
        this.properties = properties;
    }

    /**
     * Resolves a {@link ChaosDecision} for the given annotation instance and method name.
     *
     * <p>Returns {@link ChaosDecision#none()} when:
     * <ul>
     *   <li>chaos is globally disabled ({@code chaos.enabled=false}), or</li>
     *   <li>dry-run mode is active ({@code chaos.dry-run=true}).</li>
     * </ul>
     *
     * @param chaos      the annotation present on the intercepted method or class; must not be {@code null}
     * @param methodName fully-qualified method name, used for dry-run logging (e.g. {@code "com.example.MyService.doWork"})
     * @return the resolved decision; never {@code null}
     */
    public ChaosDecision decide(InjectChaos chaos, String methodName) {
        if (!properties.isEnabled()) {
            return ChaosDecision.none();
        }

        ChaosDecision decision;

        if (!chaos.scenario().trim().isEmpty()) {
            // Pass inline failureRate so it can override the scenario's rate when explicitly set
            decision = resolveFromScenario(chaos.scenario(), chaos.failureRate());
        } else {
            decision = resolveFromAnnotation(chaos);
        }

        if (properties.isDryRun()) {
            log.warn("[HavocFlow][DRY-RUN] Would apply {} to {}", decision, methodName);
            return ChaosDecision.none();
        }

        return decision;
    }

    // -----------------------------------------------------------------------
    // Private helpers
    // -----------------------------------------------------------------------

    /**
     * @param inlineFailureRate the failureRate from the annotation; when &gt; 0 it overrides the
     *                          scenario's own failureRate, allowing per-call fine-tuning.
     */
    private ChaosDecision resolveFromScenario(String scenarioName, double inlineFailureRate) {
        ChaosProperties.ScenarioProperties scenario = properties.getScenarios().get(scenarioName);
        if (scenario == null) {
            log.warn("[HavocFlow] Unknown scenario '{}' — no chaos injected. "
                    + "Define it under chaos.scenarios.{} in application.yml",
                    scenarioName, scenarioName);
            return ChaosDecision.none();
        }

        long latencyMillis = capLatency(LatencyParser.parseMillis(scenario.getLatency()));
        double rate = inlineFailureRate > 0 ? inlineFailureRate : scenario.getFailureRate();
        boolean shouldFail = random.nextDouble() < rate;

        FailureMode mode = determineMode(latencyMillis, shouldFail);
        Class<? extends Throwable> exType = shouldFail
                ? loadExceptionClass(scenario.getException())
                : null;

        return ChaosDecision.builder()
                .mode(mode)
                .latencyMillis(latencyMillis)
                .exceptionType(exType)
                .scenarioName(scenarioName)
                .build();
    }

    private ChaosDecision resolveFromAnnotation(InjectChaos chaos) {
        long latencyMillis = capLatency(LatencyParser.parseMillis(chaos.latency()));
        double rawRate = chaos.failureRate();
        if (rawRate < 0.0 || rawRate > 1.0) {
            log.warn("[HavocFlow] @InjectChaos failureRate={} is out of range [0.0,1.0] — clamped", rawRate);
            rawRate = Math.min(1.0, Math.max(0.0, rawRate));
        }
        double rate = rawRate > 0
                ? rawRate
                : properties.getDefaultFailureRate();
        boolean shouldFail = rate > 0 && random.nextDouble() < rate;

        FailureMode mode = determineMode(latencyMillis, shouldFail);
        Class<? extends Throwable> exType = shouldFail ? chaos.exception() : null;

        return ChaosDecision.builder()
                .mode(mode)
                .latencyMillis(latencyMillis)
                .exceptionType(exType)
                .scenarioName("inline")
                .build();
    }

    private FailureMode determineMode(long latencyMillis, boolean shouldFail) {
        if (latencyMillis > 0 && shouldFail) return FailureMode.LATENCY_AND_EXCEPTION;
        if (latencyMillis > 0)              return FailureMode.LATENCY;
        if (shouldFail)                     return FailureMode.EXCEPTION;
        return FailureMode.NONE;
    }

    private long capLatency(long rawMillis) {
        long cap = properties.getMaxLatencyMillis();
        if (rawMillis > cap) {
            log.warn("[HavocFlow] Latency {}ms exceeds cap of {}ms — clamped to cap", rawMillis, cap);
            return cap;
        }
        return rawMillis;
    }

    @SuppressWarnings("unchecked")
    private Class<? extends Throwable> loadExceptionClass(String className) {
        // Deny-list: reject class names that could have dangerous constructor side effects
        if (isDangerousClass(className)) {
            log.warn("[HavocFlow] Exception class '{}' is potentially dangerous — using RuntimeException", className);
            return RuntimeException.class;
        }

        // Allowlist check: if configured, only permit listed classes
        java.util.List<String> allowlist = properties.getAllowedExceptionClasses();
        if (!allowlist.isEmpty() && !allowlist.contains(className)) {
            log.warn("[HavocFlow] Exception class '{}' is not in the allowed-exception-classes list — using RuntimeException", className);
            return RuntimeException.class;
        }

        try {
            Class<?> clazz = Class.forName(className);
            if (!Throwable.class.isAssignableFrom(clazz)) {
                log.warn("[HavocFlow] '{}' does not extend Throwable — using RuntimeException", className);
                return RuntimeException.class;
            }
            return (Class<? extends Throwable>) clazz;
        } catch (ClassNotFoundException e) {
            log.warn("[HavocFlow] Exception class '{}' not found — using RuntimeException", className);
            return RuntimeException.class;
        }
    }

    private boolean isDangerousClass(String className) {
        return className.contains("System")
            || className.contains("Runtime")
            || className.contains("ProcessBuilder")
            || className.contains("Shutdown");
    }
}
