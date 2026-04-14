package io.havocflow;

import io.havocflow.actuator.ChaosEndpoint;
import io.havocflow.actuator.ChaosStatus;
import io.havocflow.autoconfigure.ChaosProperties;
import io.havocflow.core.ChaosDecision;
import io.havocflow.core.FailureMode;
import io.havocflow.event.ChaosEventStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChaosEndpointTest {

    private ChaosProperties properties;
    private ChaosEventStore eventStore;
    private ChaosEndpoint endpoint;

    @BeforeEach
    void setUp() {
        properties = new ChaosProperties();
        properties.setEnabled(true);
        eventStore = new ChaosEventStore(10);
        endpoint = new ChaosEndpoint(properties, eventStore);
    }

    @Test
    @DisplayName("GET status returns enabled=true")
    void status_returnsEnabledTrue() {
        ChaosStatus status = endpoint.status();
        assertThat(status.isEnabled()).isTrue();
    }

    @Test
    @DisplayName("GET status reflects current dryRun state")
    void status_returnsCorrectDryRun() {
        properties.setDryRun(true);
        assertThat(endpoint.status().isDryRun()).isTrue();

        properties.setDryRun(false);
        assertThat(endpoint.status().isDryRun()).isFalse();
    }

    @Test
    @DisplayName("GET status reflects defaultFailureRate")
    void status_returnsDefaultFailureRate() {
        properties.setDefaultFailureRate(0.25);
        assertThat(endpoint.status().getDefaultFailureRate()).isEqualTo(0.25);
    }

    @Test
    @DisplayName("GET status includes configured scenarios")
    void status_includesScenarios() {
        ChaosProperties.ScenarioProperties s = new ChaosProperties.ScenarioProperties();
        s.setLatency("500ms");
        s.setFailureRate(0.1);
        properties.getScenarios().put("db-timeout", s);

        ChaosStatus status = endpoint.status();
        assertThat(status.getScenarios()).containsKey("db-timeout");
    }

    @Test
    @DisplayName("GET status returns recent events from the event store")
    void status_returnsRecentEvents() {
        ChaosDecision d = ChaosDecision.builder()
                .mode(FailureMode.LATENCY)
                .latencyMillis(100)
                .scenarioName("s")
                .build();
        eventStore.record("method1", d);
        eventStore.record("method2", d);

        assertThat(endpoint.status().getRecentEvents()).hasSize(2);
    }

    @Test
    @DisplayName("POST dry-run?enabled=true sets dryRun to true")
    void update_dryRunTrue_setsProperty() {
        endpoint.update("dry-run", true);
        assertThat(properties.isDryRun()).isTrue();
    }

    @Test
    @DisplayName("POST dry-run?enabled=false sets dryRun to false")
    void update_dryRunFalse_setsProperty() {
        properties.setDryRun(true);
        endpoint.update("dry-run", false);
        assertThat(properties.isDryRun()).isFalse();
    }

    @Test
    @DisplayName("POST unknown operation throws IllegalArgumentException mentioning dry-run")
    void update_unknownOp_throwsIllegalArgument() {
        assertThatThrownBy(() -> endpoint.update("unknown-op", true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("dry-run");
    }
}
