package io.havocflow.otel;

import io.havocflow.core.ChaosDecision;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Span;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Records a {@code chaos.gremlin.fired} event on the current OpenTelemetry span
 * every time HavocFlow injects a gremlin.
 *
 * <p>This makes chaos injections visible in distributed tracing dashboards (Jaeger,
 * Zipkin, Tempo, etc.) without any additional configuration. Simply add
 * {@code opentelemetry-api} to the classpath and HavocFlow will annotate the active
 * span automatically.
 *
 * <p>Registered by {@link io.havocflow.autoconfigure.ChaosAutoConfiguration} when
 * {@code io.opentelemetry.api.trace.Span} is detected on the classpath. No
 * {@code chaos.otel.*} properties are required — presence of the API is sufficient.
 *
 * <p>If there is no active span (e.g. no instrumentation agent is installed), this
 * recorder is a silent no-op.
 *
 * <p>Span event attributes:
 * <ul>
 *   <li>{@code chaos.method} — fully-qualified method name</li>
 *   <li>{@code chaos.mode} — {@link io.havocflow.core.FailureMode} name</li>
 *   <li>{@code chaos.scenario} — named scenario, or empty string</li>
 *   <li>{@code chaos.latency_ms} — injected latency in milliseconds</li>
 * </ul>
 */
public class ChaosOtelSpanRecorder {

    private static final Logger log = LoggerFactory.getLogger(ChaosOtelSpanRecorder.class);

    private static final String EVENT_NAME = "chaos.gremlin.fired";
    private static final AttributeKey<String> ATTR_METHOD   = AttributeKey.stringKey("chaos.method");
    private static final AttributeKey<String> ATTR_MODE     = AttributeKey.stringKey("chaos.mode");
    private static final AttributeKey<String> ATTR_SCENARIO = AttributeKey.stringKey("chaos.scenario");
    private static final AttributeKey<Long>   ATTR_LATENCY  = AttributeKey.longKey("chaos.latency_ms");

    /**
     * Adds a {@code chaos.gremlin.fired} event to the current OTel span.
     * Does nothing if no span is active.
     *
     * @param methodName fully-qualified method name (e.g. {@code "com.example.OrderService.placeOrder"})
     * @param decision   the resolved chaos decision
     */
    public void record(String methodName, ChaosDecision decision) {
        Span span = Span.current();
        if (span == null || !span.getSpanContext().isValid()) {
            return;
        }
        try {
            Attributes attrs = Attributes.of(
                ATTR_METHOD,   methodName,
                ATTR_MODE,     decision.getMode().name(),
                ATTR_SCENARIO, decision.getScenarioName() != null ? decision.getScenarioName() : "",
                ATTR_LATENCY,  decision.getLatencyMillis()
            );
            span.addEvent(EVENT_NAME, attrs);
        } catch (Exception e) {
            log.warn("[HavocFlow][OTel] Failed to record span event: {}", e.getMessage());
        }
    }
}
