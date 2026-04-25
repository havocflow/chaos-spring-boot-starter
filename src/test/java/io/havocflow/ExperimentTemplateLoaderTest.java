package io.havocflow;

import io.havocflow.autoconfigure.ChaosProperties;
import io.havocflow.autoconfigure.ExperimentTemplateLoader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link ExperimentTemplateLoader}.
 */
class ExperimentTemplateLoaderTest {

    private ChaosProperties properties;
    private ExperimentTemplateLoader loader;

    @BeforeEach
    void setUp() {
        properties = new ChaosProperties();
        loader = new ExperimentTemplateLoader(properties);
    }

    @Test
    @DisplayName("All five built-in templates are loaded when the scenarios map is empty")
    void allFiveTemplatesLoaded_whenScenariosEmpty() {
        assertThat(properties.getScenarios()).isEmpty();

        loader.afterSingletonsInstantiated();

        assertThat(properties.getScenarios()).hasSize(5);
        assertThat(properties.getScenarios()).containsKeys(
            ExperimentTemplateLoader.TEMPLATE_GOLDEN_SIGNAL,
            ExperimentTemplateLoader.TEMPLATE_DB_TIMEOUT,
            ExperimentTemplateLoader.TEMPLATE_NETWORK_PARTITION,
            ExperimentTemplateLoader.TEMPLATE_CASCADING_FAILURE,
            ExperimentTemplateLoader.TEMPLATE_CACHE_MISS_STORM
        );
    }

    @Test
    @DisplayName("User-defined scenario wins when key conflicts with a template")
    void userScenarioWins_whenKeyConflicts() {
        ChaosProperties.ScenarioProperties userDefined = new ChaosProperties.ScenarioProperties();
        userDefined.setFailureRate(0.99);
        userDefined.setLatency("99s");
        properties.getScenarios().put(ExperimentTemplateLoader.TEMPLATE_DB_TIMEOUT, userDefined);

        loader.afterSingletonsInstantiated();

        ChaosProperties.ScenarioProperties loaded =
            properties.getScenarios().get(ExperimentTemplateLoader.TEMPLATE_DB_TIMEOUT);
        assertThat(loaded.getFailureRate()).isEqualTo(0.99);
        assertThat(loaded.getLatency()).isEqualTo("99s");
    }

    @Test
    @DisplayName("Other templates are still loaded when one key conflicts")
    void otherTemplatesLoaded_whenOneKeyConflicts() {
        properties.getScenarios().put(ExperimentTemplateLoader.TEMPLATE_DB_TIMEOUT,
            new ChaosProperties.ScenarioProperties());

        loader.afterSingletonsInstantiated();

        // 4 templates added + 1 user-defined = 5 total
        assertThat(properties.getScenarios()).hasSize(5);
    }

    @Test
    @DisplayName("builtInTemplateNames() returns exactly 5 names in canonical order")
    void builtInTemplateNames_returnsAllFive() {
        List<String> names = ExperimentTemplateLoader.builtInTemplateNames();
        assertThat(names).hasSize(5);
        assertThat(names).containsExactly(
            ExperimentTemplateLoader.TEMPLATE_GOLDEN_SIGNAL,
            ExperimentTemplateLoader.TEMPLATE_DB_TIMEOUT,
            ExperimentTemplateLoader.TEMPLATE_NETWORK_PARTITION,
            ExperimentTemplateLoader.TEMPLATE_CASCADING_FAILURE,
            ExperimentTemplateLoader.TEMPLATE_CACHE_MISS_STORM
        );
    }

    @Test
    @DisplayName("golden-signal-degradation template has correct config")
    void goldenSignalTemplate_hasExpectedConfig() {
        loader.afterSingletonsInstantiated();

        ChaosProperties.ScenarioProperties s =
            properties.getScenarios().get(ExperimentTemplateLoader.TEMPLATE_GOLDEN_SIGNAL);
        assertThat(s.getLatency()).isEqualTo("500ms");
        assertThat(s.getFailureRate()).isEqualTo(0.05);
        assertThat(s.getException()).isEqualTo("java.lang.RuntimeException");
    }

    @Test
    @DisplayName("network-partition template has jitter latency and SocketTimeoutException")
    void networkPartitionTemplate_hasJitterAndSocketTimeout() {
        loader.afterSingletonsInstantiated();

        ChaosProperties.ScenarioProperties s =
            properties.getScenarios().get(ExperimentTemplateLoader.TEMPLATE_NETWORK_PARTITION);
        assertThat(s.getLatency()).isEqualTo("200ms-2s");
        assertThat(s.getFailureRate()).isEqualTo(0.50);
        assertThat(s.getException()).isEqualTo("java.net.SocketTimeoutException");
    }

    @Test
    @DisplayName("cascading-failure template has high failure rate")
    void cascadingFailureTemplate_hasHighFailureRate() {
        loader.afterSingletonsInstantiated();

        ChaosProperties.ScenarioProperties s =
            properties.getScenarios().get(ExperimentTemplateLoader.TEMPLATE_CASCADING_FAILURE);
        assertThat(s.getFailureRate()).isGreaterThanOrEqualTo(0.7);
    }

    @Test
    @DisplayName("Calling afterSingletonsInstantiated twice does not duplicate templates")
    void doubleInvocation_doesNotDuplicateTemplates() {
        loader.afterSingletonsInstantiated();
        int sizeAfterFirst = properties.getScenarios().size();

        loader.afterSingletonsInstantiated();

        assertThat(properties.getScenarios()).hasSize(sizeAfterFirst);
    }

    @Test
    @DisplayName("Templates are active by default (active=true)")
    void templates_activeByDefault() {
        loader.afterSingletonsInstantiated();

        for (ChaosProperties.ScenarioProperties s : properties.getScenarios().values()) {
            assertThat(s.isActive()).isTrue();
        }
    }
}
