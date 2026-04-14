package io.havocflow.metrics;

import io.havocflow.core.ChaosDecision;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * Records chaos events to a Micrometer {@link MeterRegistry}.
 *
 * <p>Metrics produced:
 * <ul>
 *   <li>{@code chaos.gremlins.fired} — counter, tagged with {@code method}, {@code scenario},
 *       and {@code mode}</li>
 *   <li>{@code chaos.latency.injected.ms} — counter of total milliseconds injected
 *       (useful for calculating injected latency rate)</li>
 * </ul>
 *
 * <p>Visible in any Micrometer-compatible backend: Prometheus, Datadog, CloudWatch, etc.
 * This bean is only created when {@code micrometer-core} is on the classpath.
 */
public class ChaosMetricsRecorder {

    private static final String LIBRARY_VERSION = "01.01.00";

    private final MeterRegistry meterRegistry;

    /**
     * Constructs a {@code ChaosMetricsRecorder}.
     *
     * @param meterRegistry the Micrometer registry to publish metrics to; must not be {@code null}
     */
    public ChaosMetricsRecorder(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    /**
     * Records a single chaos gremlin event.
     *
     * @param methodName the fully-qualified method name where chaos was injected
     * @param decision   the decision that was applied; must not be {@code null}
     */
    public void recordGremlin(String methodName, ChaosDecision decision) {
        Counter.builder("chaos.gremlins.fired")
                .description("Number of chaos gremlins fired by HavocFlow")
                .tag("library", "havocflow")
                .tag("version", LIBRARY_VERSION)
                .tag("method", sanitize(methodName))
                .tag("scenario", decision.getScenarioName())
                .tag("mode", decision.getMode().name().toLowerCase())
                .register(meterRegistry)
                .increment();

        if (decision.getLatencyMillis() > 0) {
            Counter.builder("chaos.latency.injected.ms")
                    .description("Total artificial latency injected in milliseconds")
                    .tag("library", "havocflow")
                    .tag("version", LIBRARY_VERSION)
                    .tag("method", sanitize(methodName))
                    .register(meterRegistry)
                    .increment(decision.getLatencyMillis());
        }
    }

    /** Trims long method names to keep tag cardinality under control. */
    private String sanitize(String name) {
        return name.length() > 80 ? name.substring(name.length() - 80) : name;
    }
}
