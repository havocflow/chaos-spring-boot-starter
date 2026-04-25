package io.havocflow.test;

import io.havocflow.autoconfigure.ChaosProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.GenericContainer;

/**
 * Utility class that integrates HavocFlow chaos scenarios with Testcontainers container
 * lifecycle events.
 *
 * <p>When a container wrapped by this utility starts, the associated chaos scenario's
 * {@link ChaosProperties.ScenarioProperties#setActive(boolean) active} flag is set to
 * {@code true}. When the container stops, the flag is set back to {@code false}. This
 * allows tests to model "chaos when a downstream service is degraded" using real containers.
 *
 * <p>Usage:
 * <pre>
 * // In your integration test setup:
 * GenericContainer&lt;?&gt; postgres = new GenericContainer&lt;&gt;("postgres:15")
 *     .withExposedPorts(5432);
 *
 * ChaosContainerSupport.LifecycleAwareChaosContainer&lt;?&gt; chaosContainer =
 *     ChaosContainerSupport.withScenario(postgres, chaosProperties, "database-timeout");
 *
 * chaosContainer.start();  // chaos scenario activates
 * // ... run test code that triggers @InjectChaos(scenario = "database-timeout") ...
 * chaosContainer.stop();   // chaos scenario deactivates
 * </pre>
 *
 * <p>The scenario's {@code active} flag works in concert with the failure rate: chaos is only
 * injected when <em>both</em> the flag is {@code true} and the random dice roll is within the
 * configured {@code failureRate}. If the scenario is not found by name, a warning is logged
 * and no chaos is activated (no exception is thrown).
 *
 * <p>Registered as an optional Spring bean via
 * {@link io.havocflow.autoconfigure.ChaosAutoConfiguration} when
 * {@code org.testcontainers.containers.GenericContainer} is on the classpath.
 */
public class ChaosContainerSupport {

    /**
     * Default constructor — used when registered as a Spring bean.
     * Static factory methods do not require an instance.
     */
    public ChaosContainerSupport() {}

    /**
     * Wraps the given container in a {@link LifecycleAwareChaosContainer} that activates
     * the named chaos scenario on container start and deactivates it on container stop.
     *
     * @param container   the container to wrap; must not be {@code null}
     * @param properties  the HavocFlow configuration; must not be {@code null}
     * @param scenarioName the chaos scenario name as defined in {@code chaos.scenarios.*};
     *                     if unknown, a warning is logged and no chaos is activated
     * @param <C>         the concrete container type
     * @return a lifecycle wrapper that delegates all calls to the original container
     */
    @SuppressWarnings("rawtypes")
    public static <C extends GenericContainer> LifecycleAwareChaosContainer<C> withScenario(
            C container,
            ChaosProperties properties,
            String scenarioName) {
        return new LifecycleAwareChaosContainer<C>(container, properties, scenarioName);
    }

    // -----------------------------------------------------------------------
    // Lifecycle wrapper
    // -----------------------------------------------------------------------

    /**
     * Thin wrapper around a {@link GenericContainer} that activates/deactivates a HavocFlow
     * chaos scenario in sync with the container's start/stop lifecycle.
     *
     * @param <C> the wrapped container type
     */
    @SuppressWarnings("rawtypes")
    public static class LifecycleAwareChaosContainer<C extends GenericContainer> {

        private static final Logger log = LoggerFactory.getLogger(LifecycleAwareChaosContainer.class);

        private final C delegate;
        private final ChaosProperties properties;
        private final String scenarioName;

        LifecycleAwareChaosContainer(C delegate, ChaosProperties properties, String scenarioName) {
            this.delegate = delegate;
            this.properties = properties;
            this.scenarioName = scenarioName;
        }

        /**
         * Starts the underlying container, then activates the chaos scenario.
         * The scenario's {@code active} flag is set to {@code true} after a successful start.
         */
        public void start() {
            delegate.start();
            activateChaos();
        }

        /**
         * Deactivates the chaos scenario, then stops the underlying container.
         * The scenario's {@code active} flag is set to {@code false} before stopping,
         * even if the stop itself throws.
         */
        public void stop() {
            deactivateChaos();
            try {
                delegate.stop();
            } catch (RuntimeException e) {
                log.warn("[HavocFlow][Container] Container stop threw an exception", e);
                throw e;
            }
        }

        /**
         * Returns the underlying delegate container for direct test access (e.g. to call
         * {@code getMappedPort()} or other Testcontainers-specific methods).
         *
         * @return the wrapped container
         */
        public C getDelegate() {
            return delegate;
        }

        private void activateChaos() {
            ChaosProperties.ScenarioProperties scenario = properties.getScenarios().get(scenarioName);
            if (scenario == null) {
                log.warn("[HavocFlow][Container] Scenario '{}' not found — no chaos activated. "
                    + "Make sure it is defined under chaos.scenarios.{} in application.yml",
                    scenarioName, scenarioName);
                return;
            }
            scenario.setActive(true);
            log.info("[HavocFlow][Container] Container started — chaos scenario '{}' ACTIVATED "
                + "(failureRate={})", scenarioName, scenario.getFailureRate());
        }

        private void deactivateChaos() {
            ChaosProperties.ScenarioProperties scenario = properties.getScenarios().get(scenarioName);
            if (scenario == null) {
                return;
            }
            scenario.setActive(false);
            log.info("[HavocFlow][Container] Container stopped — chaos scenario '{}' DEACTIVATED",
                scenarioName);
        }
    }
}
