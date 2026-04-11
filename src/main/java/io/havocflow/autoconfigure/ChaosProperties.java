package io.havocflow.autoconfigure;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.HashMap;
import java.util.Map;

/**
 * All HavocFlow configuration, bound from {@code application.yml} under the {@code chaos} prefix.
 *
 * <pre>
 * chaos:
 *   enabled: true
 *   dry-run: false
 *   default-failure-rate: 0.05
 *   log-gremlins: true
 *   scenarios:
 *     db-timeout:
 *       latency: "3s"
 *       failure-rate: 0.2
 *       exception: org.springframework.dao.QueryTimeoutException
 *     network-partition:
 *       latency: "100ms-500ms"
 *       failure-rate: 0.3
 *       exception: java.net.SocketTimeoutException
 * </pre>
 */
@ConfigurationProperties(prefix = "chaos")
public class ChaosProperties {

    /** Master switch. Must be explicitly {@code true} — never defaults on. */
    private boolean enabled = false;

    /**
     * When {@code true}, logs what chaos would fire but does not actually inject
     * latency or throw exceptions. Safe to leave enabled in CI.
     */
    private boolean dryRun = false;

    /** Fallback failure rate when {@code @InjectChaos} has no {@code failureRate} set. */
    private double defaultFailureRate = 0.0;

    /** Whether to write a WARN log every time a gremlin fires. */
    private boolean logGremlins = true;

    /** Named scenario definitions. Key = scenario name used in {@code @InjectChaos(scenario=...)}. */
    private Map<String, ScenarioProperties> scenarios = new HashMap<String, ScenarioProperties>();

    // -----------------------------------------------------------------------
    // Getters / Setters
    // -----------------------------------------------------------------------

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isDryRun() {
        return dryRun;
    }

    public void setDryRun(boolean dryRun) {
        this.dryRun = dryRun;
    }

    public double getDefaultFailureRate() {
        return defaultFailureRate;
    }

    public void setDefaultFailureRate(double defaultFailureRate) {
        this.defaultFailureRate = defaultFailureRate;
    }

    public boolean isLogGremlins() {
        return logGremlins;
    }

    public void setLogGremlins(boolean logGremlins) {
        this.logGremlins = logGremlins;
    }

    public Map<String, ScenarioProperties> getScenarios() {
        return scenarios;
    }

    public void setScenarios(Map<String, ScenarioProperties> scenarios) {
        this.scenarios = scenarios;
    }

    // -----------------------------------------------------------------------
    // Nested scenario definition
    // -----------------------------------------------------------------------

    /**
     * Properties for a named chaos scenario defined under {@code chaos.scenarios.<name>}.
     *
     * <p>When a method is annotated with {@code @InjectChaos(scenario = "my-scenario")},
     * HavocFlow looks up the matching {@code ScenarioProperties} and uses its values
     * instead of the annotation's inline attributes.
     */
    public static class ScenarioProperties {

        /**
         * Latency expression for this scenario (e.g. {@code "3s"}, {@code "100ms-500ms"}).
         * Leave blank for no latency.
         */
        private String latency = "";

        /**
         * Probability (0.0–1.0) of throwing an exception on each invocation.
         */
        private double failureRate = 0.0;

        /**
         * Fully-qualified class name of the exception to throw.
         * The class must have a single-{@code String} constructor.
         */
        private String exception = "java.lang.RuntimeException";

        public String getLatency() {
            return latency;
        }

        public void setLatency(String latency) {
            this.latency = latency;
        }

        public double getFailureRate() {
            return failureRate;
        }

        public void setFailureRate(double failureRate) {
            this.failureRate = failureRate;
        }

        public String getException() {
            return exception;
        }

        public void setException(String exception) {
            this.exception = exception;
        }
    }
}
