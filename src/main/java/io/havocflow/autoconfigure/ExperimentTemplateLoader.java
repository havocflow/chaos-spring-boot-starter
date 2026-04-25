package io.havocflow.autoconfigure;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Merges built-in experiment templates into {@link ChaosProperties#getScenarios()} at startup.
 *
 * <p>Built-in templates provide sensible pre-configured scenario definitions for common chaos
 * experiments. They are merged <em>after</em> all singleton beans are fully initialized, so
 * user-defined scenarios (bound from {@code application.yml}) are already present in the map.
 * A template is only added when the key is absent — <strong>user configuration always wins</strong>.
 *
 * <h3>Built-in Templates</h3>
 * <ul>
 *   <li>{@value #TEMPLATE_GOLDEN_SIGNAL} — moderate latency + low failure rate (golden signal degradation)</li>
 *   <li>{@value #TEMPLATE_DB_TIMEOUT} — long latency + moderate failure rate + JDBC timeout exception</li>
 *   <li>{@value #TEMPLATE_NETWORK_PARTITION} — jitter latency + high failure rate + SocketTimeoutException</li>
 *   <li>{@value #TEMPLATE_CASCADING_FAILURE} — long latency + high failure rate (cascading service failure)</li>
 *   <li>{@value #TEMPLATE_CACHE_MISS_STORM} — short latency + very high failure rate (cache miss storm)</li>
 * </ul>
 *
 * <p>Usage:
 * <pre>
 * &#64;InjectChaos(scenario = "golden-signal-degradation")
 * public Order findOrder(Long id) { ... }
 * </pre>
 *
 * <p>Or override a template's behaviour in {@code application.yml}:
 * <pre>
 * chaos:
 *   scenarios:
 *     database-timeout:
 *       extends: database-timeout   # NOT valid — just set fields directly to override
 *       failure-rate: 0.5           # overrides the template default of 0.3
 * </pre>
 *
 * <p>Registered as a Spring bean via {@link ChaosAutoConfiguration}.
 */
public class ExperimentTemplateLoader implements SmartInitializingSingleton {

    private static final Logger log = LoggerFactory.getLogger(ExperimentTemplateLoader.class);

    /** Pre-built template: gradual latency + low exception rate — simulates golden signal degradation. */
    public static final String TEMPLATE_GOLDEN_SIGNAL     = "golden-signal-degradation";

    /** Pre-built template: 5-second latency + JDBC timeout exception — simulates database timeout. */
    public static final String TEMPLATE_DB_TIMEOUT        = "database-timeout";

    /** Pre-built template: jitter latency + SocketTimeoutException — simulates network partition. */
    public static final String TEMPLATE_NETWORK_PARTITION = "network-partition";

    /** Pre-built template: long latency + high failure rate — simulates cascading failure. */
    public static final String TEMPLATE_CASCADING_FAILURE = "cascading-failure";

    /** Pre-built template: short latency + very high failure rate — simulates cache miss storm. */
    public static final String TEMPLATE_CACHE_MISS_STORM  = "cache-miss-storm";

    private final ChaosProperties properties;

    /**
     * Constructs an {@code ExperimentTemplateLoader}.
     *
     * @param properties the HavocFlow configuration; must not be {@code null}
     */
    public ExperimentTemplateLoader(ChaosProperties properties) {
        this.properties = properties;
    }

    /**
     * Merges built-in templates into the scenario map.
     * Called after all singleton beans are instantiated (Spring's {@link SmartInitializingSingleton} contract).
     */
    @Override
    public void afterSingletonsInstantiated() {
        Map<String, ChaosProperties.ScenarioProperties> scenarios = properties.getScenarios();
        Map<String, ChaosProperties.ScenarioProperties> templates = buildTemplates();
        int added = 0;
        for (Map.Entry<String, ChaosProperties.ScenarioProperties> entry : templates.entrySet()) {
            if (!scenarios.containsKey(entry.getKey())) {
                scenarios.put(entry.getKey(), entry.getValue());
                added++;
                log.info("[HavocFlow] Experiment template loaded: '{}'", entry.getKey());
            } else {
                log.debug("[HavocFlow] Experiment template '{}' skipped — user config takes precedence",
                    entry.getKey());
            }
        }
        log.info("[HavocFlow] ExperimentTemplateLoader: {}/{} built-in templates applied",
            added, templates.size());
    }

    /**
     * Returns an ordered list of all built-in template names.
     * Used by {@link io.havocflow.actuator.ChaosEndpoint} to expose available templates.
     *
     * @return unmodifiable list of template names in canonical order
     */
    public static List<String> builtInTemplateNames() {
        List<String> names = new ArrayList<String>();
        names.add(TEMPLATE_GOLDEN_SIGNAL);
        names.add(TEMPLATE_DB_TIMEOUT);
        names.add(TEMPLATE_NETWORK_PARTITION);
        names.add(TEMPLATE_CASCADING_FAILURE);
        names.add(TEMPLATE_CACHE_MISS_STORM);
        return Collections.unmodifiableList(names);
    }

    // -----------------------------------------------------------------------
    // Template definitions
    // -----------------------------------------------------------------------

    private Map<String, ChaosProperties.ScenarioProperties> buildTemplates() {
        Map<String, ChaosProperties.ScenarioProperties> t =
            new LinkedHashMap<String, ChaosProperties.ScenarioProperties>();

        // Golden signal degradation: moderate latency, low failure rate
        // Simulates a service that is "slow but mostly up" — ideal for testing alerting thresholds
        t.put(TEMPLATE_GOLDEN_SIGNAL,
            scenario("500ms", 0.05, "java.lang.RuntimeException"));

        // Database timeout: long latency, moderate failure rate, JDBC timeout exception
        // Note: org.springframework.dao.QueryTimeoutException may not be on classpath in all apps;
        // ChaosEngine.loadExceptionClass() will fall back to RuntimeException gracefully.
        t.put(TEMPLATE_DB_TIMEOUT,
            scenario("5s", 0.30, "org.springframework.dao.QueryTimeoutException"));

        // Network partition: jitter latency (200ms–2s), high failure rate, socket timeout
        t.put(TEMPLATE_NETWORK_PARTITION,
            scenario("200ms-2s", 0.50, "java.net.SocketTimeoutException"));

        // Cascading failure: long latency, high failure rate
        // Simulates a downstream service that is failing and causing upstream timeouts
        t.put(TEMPLATE_CASCADING_FAILURE,
            scenario("3s", 0.80, "java.lang.RuntimeException"));

        // Cache miss storm: short latency (backend still responds), very high failure rate
        // Simulates cache eviction causing all requests to hit the backing store
        t.put(TEMPLATE_CACHE_MISS_STORM,
            scenario("50ms", 0.90, "java.lang.RuntimeException"));

        return t;
    }

    private ChaosProperties.ScenarioProperties scenario(
            String latency, double failureRate, String exception) {
        ChaosProperties.ScenarioProperties s = new ChaosProperties.ScenarioProperties();
        s.setLatency(latency);
        s.setFailureRate(failureRate);
        s.setException(exception);
        return s;
    }
}
