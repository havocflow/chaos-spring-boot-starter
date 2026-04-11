package io.havocflow;

import io.havocflow.annotation.InjectChaos;
import io.havocflow.autoconfigure.ChaosProperties;
import io.havocflow.autoconfigure.ChaosProductionGuard;
import io.havocflow.core.ChaosDecision;
import io.havocflow.core.ChaosEngine;
import io.havocflow.core.FailureMode;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent;
import org.springframework.mock.env.MockEnvironment;

import java.lang.annotation.Annotation;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Validates Phase 1 security hardening: input bounds, latency cap,
 * production guard, and exception allowlist.
 */
class ChaosPropertiesValidationTest {

    // -----------------------------------------------------------------------
    // 1.1 failureRate bounds
    // -----------------------------------------------------------------------

    @Test
    void setDefaultFailureRate_aboveOne_throwsIllegalArgument() {
        ChaosProperties props = new ChaosProperties();
        assertThatThrownBy(() -> props.setDefaultFailureRate(1.5))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("0.0 and 1.0");
    }

    @Test
    void setDefaultFailureRate_belowZero_throwsIllegalArgument() {
        ChaosProperties props = new ChaosProperties();
        assertThatThrownBy(() -> props.setDefaultFailureRate(-0.1))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("0.0 and 1.0");
    }

    @Test
    void setScenarioFailureRate_aboveOne_throwsIllegalArgument() {
        ChaosProperties.ScenarioProperties scenario = new ChaosProperties.ScenarioProperties();
        assertThatThrownBy(() -> scenario.setFailureRate(2.0))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("failureRate");
    }

    @Test
    void setScenarioFailureRate_belowZero_throwsIllegalArgument() {
        ChaosProperties.ScenarioProperties scenario = new ChaosProperties.ScenarioProperties();
        assertThatThrownBy(() -> scenario.setFailureRate(-0.5))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("failureRate");
    }

    @Test
    void setScenarioFailureRate_boundaryValues_accepted() {
        ChaosProperties.ScenarioProperties scenario = new ChaosProperties.ScenarioProperties();
        scenario.setFailureRate(0.0);
        assertThat(scenario.getFailureRate()).isEqualTo(0.0);
        scenario.setFailureRate(1.0);
        assertThat(scenario.getFailureRate()).isEqualTo(1.0);
    }

    // -----------------------------------------------------------------------
    // 1.2 Max latency cap
    // -----------------------------------------------------------------------

    @Test
    void latency_exceedingCap_isClamped() {
        ChaosProperties props = new ChaosProperties();
        props.setEnabled(true);
        props.setMaxLatencyMillis(5_000L); // cap at 5s

        ChaosEngine engine = new ChaosEngine(props);
        // "1000m" = 60,000,000 ms — should be clamped to 5000ms
        ChaosDecision decision = engine.decide(annotation("1000m", 0.0, RuntimeException.class, "", true), "test.method");

        assertThat(decision.getLatencyMillis()).isEqualTo(5_000L);
        assertThat(decision.getMode()).isEqualTo(FailureMode.LATENCY);
    }

    @Test
    void latency_withinCap_notClamped() {
        ChaosProperties props = new ChaosProperties();
        props.setEnabled(true);
        props.setMaxLatencyMillis(30_000L);

        ChaosEngine engine = new ChaosEngine(props);
        ChaosDecision decision = engine.decide(annotation("2s", 0.0, RuntimeException.class, "", true), "test.method");

        assertThat(decision.getLatencyMillis()).isEqualTo(2_000L);
    }

    @Test
    void setMaxLatencyMillis_zero_throwsIllegalArgument() {
        ChaosProperties props = new ChaosProperties();
        assertThatThrownBy(() -> props.setMaxLatencyMillis(0))
            .isInstanceOf(IllegalArgumentException.class);
    }

    // -----------------------------------------------------------------------
    // 1.3 Production profile guard
    // -----------------------------------------------------------------------

    @Test
    void forbiddenProfile_prod_blocksStartup() {
        ChaosProductionGuard guard = new ChaosProductionGuard();

        MockEnvironment env = new MockEnvironment();
        env.setProperty("chaos.enabled", "true");
        env.setActiveProfiles("prod");

        ApplicationEnvironmentPreparedEvent event = mock(ApplicationEnvironmentPreparedEvent.class);
        when(event.getEnvironment()).thenReturn(env);

        assertThatThrownBy(() -> guard.onApplicationEvent(event))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("SAFETY VIOLATION")
            .hasMessageContaining("prod");
    }

    @Test
    void forbiddenProfile_production_blocksStartup() {
        ChaosProductionGuard guard = new ChaosProductionGuard();

        MockEnvironment env = new MockEnvironment();
        env.setProperty("chaos.enabled", "true");
        env.setActiveProfiles("production");

        ApplicationEnvironmentPreparedEvent event = mock(ApplicationEnvironmentPreparedEvent.class);
        when(event.getEnvironment()).thenReturn(env);

        assertThatThrownBy(() -> guard.onApplicationEvent(event))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("production");
    }

    @Test
    void devProfile_doesNotBlockStartup() {
        ChaosProductionGuard guard = new ChaosProductionGuard();

        MockEnvironment env = new MockEnvironment();
        env.setProperty("chaos.enabled", "true");
        env.setActiveProfiles("dev");

        ApplicationEnvironmentPreparedEvent event = mock(ApplicationEnvironmentPreparedEvent.class);
        when(event.getEnvironment()).thenReturn(env);

        // Should not throw
        guard.onApplicationEvent(event);
    }

    @Test
    void chaosDisabled_prodProfile_doesNotBlock() {
        ChaosProductionGuard guard = new ChaosProductionGuard();

        MockEnvironment env = new MockEnvironment();
        env.setProperty("chaos.enabled", "false");
        env.setActiveProfiles("prod");

        ApplicationEnvironmentPreparedEvent event = mock(ApplicationEnvironmentPreparedEvent.class);
        when(event.getEnvironment()).thenReturn(env);

        // chaos is off — guard should pass silently
        guard.onApplicationEvent(event);
    }

    @Test
    void customForbiddenProfiles_respected() {
        ChaosProductionGuard guard = new ChaosProductionGuard();

        MockEnvironment env = new MockEnvironment();
        env.setProperty("chaos.enabled", "true");
        env.setProperty("chaos.forbidden-profiles", "staging,uat");
        env.setActiveProfiles("staging");

        ApplicationEnvironmentPreparedEvent event = mock(ApplicationEnvironmentPreparedEvent.class);
        when(event.getEnvironment()).thenReturn(env);

        assertThatThrownBy(() -> guard.onApplicationEvent(event))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("staging");
    }

    // -----------------------------------------------------------------------
    // 1.5 Exception allowlist + dangerous class guard
    // -----------------------------------------------------------------------

    @Test
    void dangerousExceptionClass_fallsBackToRuntimeException() {
        ChaosProperties props = new ChaosProperties();
        props.setEnabled(true);

        ChaosProperties.ScenarioProperties scenario = new ChaosProperties.ScenarioProperties();
        scenario.setFailureRate(1.0);
        scenario.setException("java.lang.RuntimeException"); // safe
        props.getScenarios().put("safe", scenario);

        // Verify dangerous class names are blocked
        ChaosEngine engine = new ChaosEngine(props);

        // We test the guard indirectly via a scenario referencing a dangerous name.
        // A class named "FakeSystemShutdown" contains "System" — should be blocked.
        ChaosProperties.ScenarioProperties dangerousScenario = new ChaosProperties.ScenarioProperties();
        dangerousScenario.setFailureRate(1.0);
        dangerousScenario.setException("com.evil.FakeSystemShutdown");
        props.getScenarios().put("dangerous", dangerousScenario);

        // The decision itself is still made (mode=EXCEPTION) but the thrown exception
        // will be RuntimeException because loadExceptionClass falls back.
        ChaosDecision decision = engine.decide(annotation("", 1.0, RuntimeException.class, "dangerous", true), "test.method");
        assertThat(decision.getMode()).isEqualTo(FailureMode.EXCEPTION);
        // Exception type resolves to RuntimeException due to dangerous-class guard
        assertThat(decision.getExceptionType()).isEqualTo(RuntimeException.class);
    }

    @Test
    void allowlist_permitsListedClass() {
        ChaosProperties props = new ChaosProperties();
        props.setEnabled(true);
        props.setAllowedExceptionClasses(Arrays.asList("java.lang.IllegalStateException"));

        ChaosProperties.ScenarioProperties scenario = new ChaosProperties.ScenarioProperties();
        scenario.setFailureRate(1.0);
        scenario.setException("java.lang.IllegalStateException");
        props.getScenarios().put("allowed", scenario);

        ChaosEngine engine = new ChaosEngine(props);
        ChaosDecision decision = engine.decide(annotation("", 1.0, RuntimeException.class, "allowed", true), "test.method");

        assertThat(decision.getExceptionType()).isEqualTo(IllegalStateException.class);
    }

    @Test
    void allowlist_rejectsUnlistedClass_fallsBackToRuntimeException() {
        ChaosProperties props = new ChaosProperties();
        props.setEnabled(true);
        props.setAllowedExceptionClasses(Arrays.asList("java.lang.IllegalStateException"));

        ChaosProperties.ScenarioProperties scenario = new ChaosProperties.ScenarioProperties();
        scenario.setFailureRate(1.0);
        scenario.setException("java.io.IOException");
        props.getScenarios().put("unlisted", scenario);

        ChaosEngine engine = new ChaosEngine(props);
        ChaosDecision decision = engine.decide(annotation("", 1.0, RuntimeException.class, "unlisted", true), "test.method");

        assertThat(decision.getExceptionType()).isEqualTo(RuntimeException.class);
    }

    // -----------------------------------------------------------------------
    // Helper: build a synthetic @InjectChaos annotation instance
    // -----------------------------------------------------------------------

    private InjectChaos annotation(final String latency, final double failureRate,
                                   final Class<? extends Throwable> exception,
                                   final String scenario, final boolean overridable) {
        return new InjectChaos() {
            @Override public Class<? extends Annotation> annotationType() { return InjectChaos.class; }
            @Override public String latency()      { return latency; }
            @Override public double failureRate()  { return failureRate; }
            @Override public Class<? extends Throwable> exception() { return exception; }
            @Override public String scenario()     { return scenario; }
            @Override public boolean overridable() { return overridable; }
        };
    }
}
