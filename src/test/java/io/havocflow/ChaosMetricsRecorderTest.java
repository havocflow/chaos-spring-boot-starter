package io.havocflow;

import io.havocflow.core.ChaosDecision;
import io.havocflow.core.FailureMode;
import io.havocflow.metrics.ChaosMetricsRecorder;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ChaosMetricsRecorderTest {

    private SimpleMeterRegistry registry;
    private ChaosMetricsRecorder recorder;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        recorder = new ChaosMetricsRecorder(registry);
    }

    private ChaosDecision latencyDecision(long latencyMs) {
        return ChaosDecision.builder()
                .mode(FailureMode.LATENCY)
                .latencyMillis(latencyMs)
                .scenarioName("test-scenario")
                .build();
    }

    private ChaosDecision exceptionDecision() {
        return ChaosDecision.builder()
                .mode(FailureMode.EXCEPTION)
                .latencyMillis(0)
                .scenarioName("ex-scenario")
                .build();
    }

    @Test
    @DisplayName("recordGremlin increments chaos.gremlins.fired counter")
    void incrementsGremlinsFiredCounter() {
        recorder.recordGremlin("com.example.MyService.doWork", latencyDecision(100));

        Counter counter = registry.find("chaos.gremlins.fired")
                .tag("library", "havocflow")
                .counter();
        assertThat(counter).isNotNull();
        assertThat(counter.count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("chaos.gremlins.fired has library, version, method, scenario, mode tags")
    void gremlinsFired_hasCorrectTags() {
        recorder.recordGremlin("com.example.MyService.doWork", latencyDecision(100));

        Counter counter = registry.find("chaos.gremlins.fired")
                .tag("library", "havocflow")
                .tag("version", "01.01.00")
                .tag("method", "com.example.MyService.doWork")
                .tag("scenario", "test-scenario")
                .tag("mode", "latency")
                .counter();
        assertThat(counter).isNotNull();
    }

    @Test
    @DisplayName("chaos.latency.injected.ms is incremented by latency amount")
    void latencyCounter_createdWithCorrectIncrement() {
        recorder.recordGremlin("com.example.MyService.doWork", latencyDecision(200));

        Counter counter = registry.find("chaos.latency.injected.ms")
                .tag("library", "havocflow")
                .counter();
        assertThat(counter).isNotNull();
        assertThat(counter.count()).isEqualTo(200.0);
    }

    @Test
    @DisplayName("chaos.latency.injected.ms has library and version tags")
    void latencyCounter_hasLibraryVersionTags() {
        recorder.recordGremlin("com.example.MyService.doWork", latencyDecision(50));

        Counter counter = registry.find("chaos.latency.injected.ms")
                .tag("library", "havocflow")
                .tag("version", "01.01.00")
                .counter();
        assertThat(counter).isNotNull();
    }

    @Test
    @DisplayName("No latency counter created when latencyMillis is 0")
    void noLatency_noLatencyCounter() {
        recorder.recordGremlin("com.example.MyService.doWork", exceptionDecision());

        Counter counter = registry.find("chaos.latency.injected.ms").counter();
        assertThat(counter).isNull();
    }

    @Test
    @DisplayName("Multiple events accumulate on the same counter")
    void multipleEvents_accumulatesCount() {
        ChaosDecision d = latencyDecision(0);
        recorder.recordGremlin("com.example.Svc.op", d);
        recorder.recordGremlin("com.example.Svc.op", d);
        recorder.recordGremlin("com.example.Svc.op", d);

        Counter counter = registry.find("chaos.gremlins.fired")
                .tag("library", "havocflow")
                .tag("method", "com.example.Svc.op")
                .counter();
        assertThat(counter).isNotNull();
        assertThat(counter.count()).isEqualTo(3.0);
    }

    @Test
    @DisplayName("Method names longer than 80 chars are trimmed to last 80 chars")
    void longMethodName_isTrimmedTo80Chars() {
        String longName = "com.example.very.long.package.name.MyService.someVeryDescriptiveMethodNameThatExceedsLimit";
        // longName.length() > 80
        String expectedTag = longName.substring(longName.length() - 80);

        recorder.recordGremlin(longName, latencyDecision(0));

        Counter counter = registry.find("chaos.gremlins.fired")
                .tag("method", expectedTag)
                .counter();
        assertThat(counter).isNotNull();
    }
}
