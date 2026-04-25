package io.havocflow;

import io.havocflow.autoconfigure.ChaosProperties;
import io.havocflow.test.ChaosContainerSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * Unit tests for {@link ChaosContainerSupport}.
 * Uses Mockito to mock the {@link GenericContainer} so Docker is not required.
 */
class ChaosContainerSupportTest {

    private ChaosProperties properties;
    private ChaosProperties.ScenarioProperties scenario;

    @BeforeEach
    void setUp() {
        properties = new ChaosProperties();

        scenario = new ChaosProperties.ScenarioProperties();
        scenario.setLatency("3s");
        scenario.setFailureRate(0.5);
        // Scenarios default to active=true; set to false so we can observe the change on start
        scenario.setActive(false);

        properties.getScenarios().put("database-timeout", scenario);
    }

    private GenericContainer<?> mockContainer() {
        GenericContainer<?> container = mock(GenericContainer.class);
        doNothing().when(container).start();
        doNothing().when(container).stop();
        return container;
    }

    @Test
    @DisplayName("start() delegates to the underlying container")
    void start_delegatesToContainer() {
        GenericContainer<?> container = mockContainer();
        ChaosContainerSupport.LifecycleAwareChaosContainer<?> wrapped =
            ChaosContainerSupport.withScenario(container, properties, "database-timeout");

        wrapped.start();

        verify(container).start();
    }

    @Test
    @DisplayName("start() sets scenario active=true")
    void start_activatesScenario() {
        assertThat(scenario.isActive()).isFalse();

        ChaosContainerSupport.LifecycleAwareChaosContainer<?> wrapped =
            ChaosContainerSupport.withScenario(mockContainer(), properties, "database-timeout");

        wrapped.start();

        assertThat(scenario.isActive()).isTrue();
    }

    @Test
    @DisplayName("stop() delegates to the underlying container")
    void stop_delegatesToContainer() {
        GenericContainer<?> container = mockContainer();
        ChaosContainerSupport.LifecycleAwareChaosContainer<?> wrapped =
            ChaosContainerSupport.withScenario(container, properties, "database-timeout");

        wrapped.start();
        wrapped.stop();

        verify(container).stop();
    }

    @Test
    @DisplayName("stop() sets scenario active=false")
    void stop_deactivatesScenario() {
        ChaosContainerSupport.LifecycleAwareChaosContainer<?> wrapped =
            ChaosContainerSupport.withScenario(mockContainer(), properties, "database-timeout");

        wrapped.start();
        assertThat(scenario.isActive()).isTrue();

        wrapped.stop();
        assertThat(scenario.isActive()).isFalse();
    }

    @Test
    @DisplayName("getDelegate() returns the original container")
    void getDelegate_returnsOriginalContainer() {
        GenericContainer<?> container = mockContainer();
        ChaosContainerSupport.LifecycleAwareChaosContainer<?> wrapped =
            ChaosContainerSupport.withScenario(container, properties, "database-timeout");

        assertThat(wrapped.getDelegate()).isSameAs(container);
    }

    @Test
    @DisplayName("Unknown scenario name: start() does not throw and does not activate")
    void unknownScenario_startDoesNotThrow() {
        ChaosContainerSupport.LifecycleAwareChaosContainer<?> wrapped =
            ChaosContainerSupport.withScenario(mockContainer(), properties, "nonexistent-scenario");

        // Must not throw
        wrapped.start();
        wrapped.stop();

        // Original scenario unaffected
        assertThat(scenario.isActive()).isFalse();
    }

    @Test
    @DisplayName("Multiple start/stop cycles toggle active flag correctly")
    void multipleStartStop_togglingWorks() {
        GenericContainer<?> container = mockContainer();
        ChaosContainerSupport.LifecycleAwareChaosContainer<?> wrapped =
            ChaosContainerSupport.withScenario(container, properties, "database-timeout");

        wrapped.start();
        assertThat(scenario.isActive()).isTrue();

        wrapped.stop();
        assertThat(scenario.isActive()).isFalse();

        wrapped.start();
        assertThat(scenario.isActive()).isTrue();
    }
}
