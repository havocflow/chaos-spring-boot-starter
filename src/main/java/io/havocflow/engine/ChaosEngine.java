package io.havocflow.engine;

import io.havocflow.annotation.InjectChaos;
import io.havocflow.config.ChaosProperties;
import io.havocflow.config.ChaosScenarioProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Core decision engine. Given an {@link InjectChaos} annotation on a method,
 * resolves the effective latency, failure rate, and exception — then decides
 * whether to inject chaos on this particular invocation.
 *
 * <p>Scenarios defined in {@code chaos.scenarios.*} override annotation fields.
 */
@Slf4j
@RequiredArgsConstructor
public class ChaosEngine {

    private static final Pattern FIXED_MS  = Pattern.compile("(\\d+)ms");
    private static final Pattern FIXED_S   = Pattern.compile("(\\d+)s");
    private static final Pattern RANGE     = Pattern.compile("(\\d+)ms-(\\d+)ms|(\\d+)s-(\\d+)s|(\\d+)ms-(\\d+)s");

    private final ChaosProperties properties;
    private final Random random = new Random();

    /**
     * Resolves a {@link ChaosDecision} for the given annotation instance.
     * Returns {@link ChaosDecision#none()} if chaos is globally disabled.
     */
    public ChaosDecision resolve(InjectChaos chaos) {
        if (!properties.isEnabled()) {
            return ChaosDecision.none();
        }

        // Named scenario takes priority over inline annotation values
        if (!chaos.scenario().isBlank()) {
            return resolveFromScenario(chaos.scenario());
        }

        return resolveFromAnnotation(chaos);
    }

    // -----------------------------------------------------------------------
    // Private helpers
    // -----------------------------------------------------------------------

    private ChaosDecision resolveFromScenario(String scenarioName) {
        ChaosScenarioProperties scenario = properties.getScenarios().get(scenarioName);
        if (scenario == null) {
            log.warn("[HavocFlow] Unknown scenario '{}' — no chaos injected. "
                    + "Define it under chaos.scenarios.{} in application.yml", scenarioName, scenarioName);
            return ChaosDecision.none();
        }

        long latencyMs = parseLatency(scenario.getLatency());
        boolean shouldFail = random.nextDouble() < scenario.getFailureRate();

        FailureMode mode = determineMode(latencyMs, shouldFail);
        Class<? extends Throwable> exClass = shouldFail
                ? loadExceptionClass(scenario.getException())
                : null;

        return ChaosDecision.builder()
                .mode(mode)
                .latencyMs(latencyMs)
                .exceptionClass(exClass)
                .scenarioLabel(scenarioName)
                .build();
    }

    private ChaosDecision resolveFromAnnotation(InjectChaos chaos) {
        long latencyMs = parseLatency(chaos.latency());
        double rate = chaos.failureRate() > 0
                ? chaos.failureRate()
                : properties.getDefaultFailureRate();
        boolean shouldFail = rate > 0 && random.nextDouble() < rate;

        FailureMode mode = determineMode(latencyMs, shouldFail);
        Class<? extends Throwable> exClass = shouldFail ? chaos.exception() : null;

        return ChaosDecision.builder()
                .mode(mode)
                .latencyMs(latencyMs)
                .exceptionClass(exClass)
                .scenarioLabel("inline")
                .build();
    }

    private FailureMode determineMode(long latencyMs, boolean shouldFail) {
        if (latencyMs > 0 && shouldFail) return FailureMode.BOTH;
        if (latencyMs > 0) return FailureMode.LATENCY;
        if (shouldFail)    return FailureMode.EXCEPTION;
        return FailureMode.NONE;
    }

    /**
     * Parses latency strings: "500ms", "2s", "100ms-2000ms" (random jitter range).
     */
    long parseLatency(String latency) {
        if (latency == null || latency.isBlank()) return 0;

        // Range pattern: "100ms-500ms"
        Pattern rangeMs = Pattern.compile("(\\d+)ms-(\\d+)ms");
        Matcher rm = rangeMs.matcher(latency);
        if (rm.matches()) {
            long lo = Long.parseLong(rm.group(1));
            long hi = Long.parseLong(rm.group(2));
            return lo + (long)(random.nextDouble() * (hi - lo));
        }

        // Range pattern: "1s-5s"
        Pattern rangeS = Pattern.compile("(\\d+)s-(\\d+)s");
        Matcher rs = rangeS.matcher(latency);
        if (rs.matches()) {
            long lo = Long.parseLong(rs.group(1)) * 1000;
            long hi = Long.parseLong(rs.group(2)) * 1000;
            return lo + (long)(random.nextDouble() * (hi - lo));
        }

        // Fixed milliseconds: "500ms"
        Matcher fm = FIXED_MS.matcher(latency);
        if (fm.matches()) return Long.parseLong(fm.group(1));

        // Fixed seconds: "2s"
        Matcher fs = FIXED_S.matcher(latency);
        if (fs.matches()) return Long.parseLong(fs.group(1)) * 1000;

        log.warn("[HavocFlow] Could not parse latency '{}' — ignoring", latency);
        return 0;
    }

    @SuppressWarnings("unchecked")
    private Class<? extends Throwable> loadExceptionClass(String className) {
        try {
            return (Class<? extends Throwable>) Class.forName(className);
        } catch (ClassNotFoundException e) {
            log.warn("[HavocFlow] Exception class '{}' not found — using RuntimeException", className);
            return RuntimeException.class;
        }
    }
}
