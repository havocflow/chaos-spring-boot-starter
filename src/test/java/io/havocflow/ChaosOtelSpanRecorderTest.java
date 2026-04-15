package io.havocflow;

import io.havocflow.core.ChaosDecision;
import io.havocflow.core.FailureMode;
import io.havocflow.otel.ChaosOtelSpanRecorder;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.EventData;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Unit tests for {@link ChaosOtelSpanRecorder}.
 */
class ChaosOtelSpanRecorderTest {

    private InMemorySpanExporter spanExporter;
    private OpenTelemetrySdk openTelemetry;
    private Tracer tracer;
    private ChaosOtelSpanRecorder recorder;

    @BeforeEach
    void setUp() {
        spanExporter = InMemorySpanExporter.create();
        SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
            .addSpanProcessor(SimpleSpanProcessor.create(spanExporter))
            .build();
        openTelemetry = OpenTelemetrySdk.builder()
            .setTracerProvider(tracerProvider)
            .build();
        tracer = openTelemetry.getTracer("havocflow-test");
        recorder = new ChaosOtelSpanRecorder();
    }

    @AfterEach
    void tearDown() {
        openTelemetry.close();
    }

    @Test
    @DisplayName("Adds chaos.gremlin.fired event on active span with correct attributes")
    void activeSpan_addsEventWithAttributes() {
        ChaosDecision decision = ChaosDecision.builder()
            .mode(FailureMode.EXCEPTION)
            .latencyMillis(0L)
            .exceptionType(RuntimeException.class)
            .scenarioName("db-timeout")
            .build();

        Span span = tracer.spanBuilder("test-operation").startSpan();
        try (Scope scope = span.makeCurrent()) {
            recorder.record("com.example.OrderService.placeOrder", decision);
        } finally {
            span.end();
        }

        List<SpanData> spans = spanExporter.getFinishedSpanItems();
        assertThat(spans).hasSize(1);

        List<EventData> events = spans.get(0).getEvents();
        assertThat(events).hasSize(1);
        assertThat(events.get(0).getName()).isEqualTo("chaos.gremlin.fired");
        assertThat(events.get(0).getAttributes().get(AttributeKey.stringKey("chaos.method")))
            .isEqualTo("com.example.OrderService.placeOrder");
        assertThat(events.get(0).getAttributes().get(AttributeKey.stringKey("chaos.mode")))
            .isEqualTo("EXCEPTION");
        assertThat(events.get(0).getAttributes().get(AttributeKey.stringKey("chaos.scenario")))
            .isEqualTo("db-timeout");
        assertThat(events.get(0).getAttributes().get(AttributeKey.longKey("chaos.latency_ms")))
            .isEqualTo(0L);
    }

    @Test
    @DisplayName("Does not throw when there is no active span")
    void noActiveSpan_silentNoOp() {
        // No span activated — Span.current() returns a noop span with invalid SpanContext
        ChaosDecision decision = ChaosDecision.builder()
            .mode(FailureMode.LATENCY)
            .latencyMillis(200L)
            .scenarioName("inline")
            .build();

        assertThatCode(() -> recorder.record("com.example.Service.method", decision))
            .doesNotThrowAnyException();

        // Nothing exported because no span was active
        assertThat(spanExporter.getFinishedSpanItems()).isEmpty();
    }

    @Test
    @DisplayName("Null scenario name is safely handled as empty string")
    void nullScenario_treatedAsEmpty() {
        ChaosDecision decision = ChaosDecision.builder()
            .mode(FailureMode.LATENCY)
            .latencyMillis(100L)
            .scenarioName(null)
            .build();

        Span span = tracer.spanBuilder("null-scenario-test").startSpan();
        assertThatCode(() -> {
            try (Scope scope = span.makeCurrent()) {
                recorder.record("com.example.Service.method", decision);
            } finally {
                span.end();
            }
        }).doesNotThrowAnyException();

        List<SpanData> spans = spanExporter.getFinishedSpanItems();
        assertThat(spans).hasSize(1);
        assertThat(spans.get(0).getEvents().get(0).getAttributes()
            .get(AttributeKey.stringKey("chaos.scenario")))
            .isEqualTo("");
    }
}
