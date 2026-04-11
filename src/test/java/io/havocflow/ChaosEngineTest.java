package io.havocflow;

import io.havocflow.annotation.InjectChaos;
import io.havocflow.autoconfigure.ChaosProperties;
import io.havocflow.core.ChaosDecision;
import io.havocflow.core.ChaosEngine;
import io.havocflow.core.FailureMode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

import java.lang.annotation.Annotation;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link ChaosEngine}.
 * No Spring context needed — pure decision-logic tests.
 */
@DisplayName("ChaosEngine")
class ChaosEngineTest {

    private ChaosProperties properties;
    private ChaosEngine engine;

    @BeforeEach
    void setUp() {
        properties = new ChaosProperties();
        properties.setEnabled(true);
        engine = new ChaosEngine(properties);
    }

    @Test
    @DisplayName("NONE when no latency and failureRate = 0")
    void noOpAnnotation_returnsNone() {
        ChaosDecision decision = engine.decide(chaos("", 0.0), "TestService#method");
        assertThat(decision.getMode()).isEqualTo(FailureMode.NONE);
    }

    @Test
    @DisplayName("LATENCY when only latency is specified")
    void latencyOnly_returnsLatencyMode() {
        ChaosDecision decision = engine.decide(chaos("500ms", 0.0), "TestService#method");
        assertThat(decision.getMode()).isEqualTo(FailureMode.LATENCY);
        assertThat(decision.getLatencyMillis()).isEqualTo(500L);
    }

    @Test
    @DisplayName("EXCEPTION when failureRate = 1.0")
    void fullFailureRate_returnsExceptionMode() {
        ChaosDecision decision = engine.decide(chaos("", 1.0), "TestService#method");
        assertThat(decision.getMode()).isEqualTo(FailureMode.EXCEPTION);
        assertThat(decision.getExceptionType()).isEqualTo(RuntimeException.class);
    }

    @Test
    @DisplayName("LATENCY_AND_EXCEPTION when both are set with failureRate = 1.0")
    void latencyAndFailure_returnsBothMode() {
        ChaosDecision decision = engine.decide(chaos("1s", 1.0), "TestService#method");
        assertThat(decision.getMode()).isEqualTo(FailureMode.LATENCY_AND_EXCEPTION);
        assertThat(decision.getLatencyMillis()).isEqualTo(1000L);
    }

    @Test
    @DisplayName("Named scenario resolves latency, rate, and exception from properties")
    void namedScenario_resolvesFromProperties() {
        ChaosProperties.ScenarioProperties scenario = new ChaosProperties.ScenarioProperties();
        scenario.setLatency("2s");
        scenario.setFailureRate(1.0);
        scenario.setException("java.lang.IllegalStateException");
        Map<String, ChaosProperties.ScenarioProperties> scenarios =
                new HashMap<String, ChaosProperties.ScenarioProperties>();
        scenarios.put("db-timeout", scenario);
        properties.setScenarios(scenarios);

        ChaosDecision decision = engine.decide(chaosWithScenario("db-timeout"), "Svc#op");
        assertThat(decision.getMode()).isEqualTo(FailureMode.LATENCY_AND_EXCEPTION);
        assertThat(decision.getLatencyMillis()).isEqualTo(2000L);
        assertThat(decision.getExceptionType()).isEqualTo(IllegalStateException.class);
        assertThat(decision.getScenarioName()).isEqualTo("db-timeout");
    }

    @Test
    @DisplayName("Unknown scenario name falls back to NONE (no inline params set)")
    void unknownScenario_noInlineParams_returnsNone() {
        ChaosDecision decision = engine.decide(chaosWithScenario("does-not-exist"), "Svc#op");
        assertThat(decision.getMode()).isEqualTo(FailureMode.NONE);
    }

    @Test
    @DisplayName("Annotation attribute overrides scenario value")
    void annotationOverridesScenario() {
        ChaosProperties.ScenarioProperties scenario = new ChaosProperties.ScenarioProperties();
        scenario.setLatency("5s");
        scenario.setFailureRate(0.5);
        Map<String, ChaosProperties.ScenarioProperties> scenarios =
                new HashMap<String, ChaosProperties.ScenarioProperties>();
        scenarios.put("slow", scenario);
        properties.setScenarios(scenarios);

        // Inline failureRate=1.0 should override scenario's 0.5
        ChaosDecision decision = engine.decide(
                chaosWithScenarioAndRate("slow", 1.0), "Svc#op");
        assertThat(decision.shouldThrow()).isTrue();
    }

    @Test
    @DisplayName("Global defaultFailureRate is used when annotation and scenario don't specify")
    void globalDefaultRate_appliedWhenNoAnnotationRate() {
        properties.setDefaultFailureRate(1.0); // always fail
        ChaosDecision decision = engine.decide(chaos("", 0.0), "Svc#op");
        assertThat(decision.getMode()).isEqualTo(FailureMode.EXCEPTION);
    }

    @RepeatedTest(50)
    @DisplayName("Jitter latency range resolves within bounds every invocation")
    void jitterRange_alwaysWithinBounds() {
        ChaosDecision decision = engine.decide(chaos("100ms-500ms", 0.0), "Svc#op");
        assertThat(decision.getLatencyMillis()).isBetween(100L, 500L);
    }

    @Test
    @DisplayName("Dry-run returns NONE regardless of annotation params")
    void dryRun_returnsNone() {
        properties.setDryRun(true);
        ChaosDecision decision = engine.decide(chaos("2s", 1.0), "Svc#op");
        assertThat(decision.getMode()).isEqualTo(FailureMode.NONE);
    }

    // -------------------------------------------------------------------------
    // Annotation builder helpers
    // -------------------------------------------------------------------------

    private InjectChaos chaos(final String latency, final double failureRate) {
        return new InjectChaos() {
            public Class<? extends Annotation> annotationType() { return InjectChaos.class; }
            public String latency()      { return latency; }
            public double failureRate()  { return failureRate; }
            public Class<? extends Throwable> exception() { return RuntimeException.class; }
            public String scenario()     { return ""; }
            public boolean overridable() { return true; }
        };
    }

    private InjectChaos chaosWithScenario(final String scenario) {
        return new InjectChaos() {
            public Class<? extends Annotation> annotationType() { return InjectChaos.class; }
            public String latency()      { return ""; }
            public double failureRate()  { return 0.0; }
            public Class<? extends Throwable> exception() { return RuntimeException.class; }
            public String scenario()     { return scenario; }
            public boolean overridable() { return true; }
        };
    }

    private InjectChaos chaosWithScenarioAndRate(final String scenario, final double failureRate) {
        return new InjectChaos() {
            public Class<? extends Annotation> annotationType() { return InjectChaos.class; }
            public String latency()      { return ""; }
            public double failureRate()  { return failureRate; }
            public Class<? extends Throwable> exception() { return RuntimeException.class; }
            public String scenario()     { return scenario; }
            public boolean overridable() { return true; }
        };
    }
}
