package io.havocflow.metrics;

import io.havocflow.engine.ChaosDecision;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Records chaos events to Micrometer.
 *
 * <p>Metrics produced:
 * <ul>
 *   <li>{@code chaos.gremlins.fired} — counter, tagged with method + scenario + mode</li>
 *   <li>{@code chaos.latency.injected.ms} — counter of total ms injected (for rate calculation)</li>
 * </ul>
 *
 * <p>Visible in any Micrometer-compatible backend: Prometheus, Datadog, CloudWatch, etc.
 */
@Slf4j
@RequiredArgsConstructor
public class ChaosMetricsRecorder {

    private final MeterRegistry meterRegistry;

    public void recordGremlin(String methodName, ChaosDecision decision) {
        Counter.builder("chaos.gremlins.fired")
                .description("Number of chaos gremlins fired by HavocFlow")
                .tag("method", sanitize(methodName))
                .tag("scenario", decision.getScenarioLabel())
                .tag("mode", decision.getMode().name().toLowerCase())
                .register(meterRegistry)
                .increment();

        if (decision.getLatencyMs() > 0) {
            Counter.builder("chaos.latency.injected.ms")
                    .description("Total artificial latency injected in milliseconds")
                    .tag("method", sanitize(methodName))
                    .register(meterRegistry)
                    .increment(decision.getLatencyMs());
        }
    }

    private String sanitize(String name) {
        // Keep tag values concise for cardinality control
        return name.length() > 80 ? name.substring(name.length() - 80) : name;
    }
}
