package io.havocflow.core;

import io.havocflow.annotation.InjectChaos;
import io.havocflow.autoconfigure.ChaosProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ThreadLocalRandom;

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
            // When overridable=true, inline annotation values (latency, failureRate, exception)
            // take precedence over the named scenario's values if they are explicitly set.
            // When overridable=false, the named scenario is authoritative and inline values are ignored.
            decision = resolveFromScenario(chaos.scenario(), chaos.overridable() ? chaos : null);
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
     * @param scenarioName  the name of the scenario to resolve
     * @param inlineOverride when non-null (overridable=true), inline annotation values may override
     *                       the scenario's own values if they are explicitly set.
     *                       When null (overridable=false), the scenario is authoritative.
     */
    private ChaosDecision resolveFromScenario(String scenarioName, InjectChaos inlineOverride) {
        ChaosProperties.ScenarioProperties scenario = properties.getScenarios().get(scenarioName);
        if (scenario == null) {
            log.warn("[HavocFlow] Unknown scenario '{}' — no chaos injected. "
                    + "Define it under chaos.scenarios.{} in application.yml",
                    scenarioName, scenarioName);
            return ChaosDecision.none();
        }

        // Inline latency overrides scenario latency only when overridable=true and explicitly set
        String latencyExpr = (inlineOverride != null && !inlineOverride.latency().trim().isEmpty())
                ? inlineOverride.latency()
                : scenario.getLatency();
        long latencyMillis = capLatency(LatencyParser.parseMillis(latencyExpr));

        // Inline failureRate overrides scenario failureRate only when overridable=true and > 0
        double rate = (inlineOverride != null && inlineOverride.failureRate() > 0)
                ? inlineOverride.failureRate()
                : scenario.getFailureRate();
        boolean shouldFail = ThreadLocalRandom.current().nextDouble() < rate;

        // Inline exception overrides scenario exception only when overridable=true and not the default
        Class<? extends Throwable> exType = null;
        if (shouldFail) {
            boolean inlineExceptionSet = inlineOverride != null
                    && inlineOverride.exception() != RuntimeException.class;
            if (inlineExceptionSet) {
                exType = inlineOverride.exception();
            } else {
                exType = loadExceptionClass(scenario.getException());
            }
        }

        FailureMode mode = determineMode(latencyMillis, shouldFail);

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
        boolean shouldFail = rate > 0 && ThreadLocalRandom.current().nextDouble() < rate;

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

    /**
     * Logs an INFO-level advisory at the first call when no allowlist is configured,
     * so operators are aware that any {@link Throwable} subclass on the classpath is accepted.
     * Called once per engine instance (not per invocation) via the flag below.
     */
    private volatile boolean allowlistWarningEmitted = false;

    @SuppressWarnings("unchecked")
    private Class<? extends Throwable> loadExceptionClass(String className) {
        // Advisory: remind operators to configure an allowlist for stricter security
        if (!allowlistWarningEmitted && properties.getAllowedExceptionClasses().isEmpty()) {
            log.info("[HavocFlow] chaos.allowed-exception-classes is not configured — any Throwable "
                    + "subclass on the classpath may be used. Set this list to restrict injection "
                    + "to approved exception types in shared or multi-tenant environments.");
            allowlistWarningEmitted = true;
        }

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
