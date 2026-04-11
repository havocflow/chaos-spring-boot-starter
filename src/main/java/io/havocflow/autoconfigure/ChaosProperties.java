package io.havocflow.autoconfigure;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
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

    /**
     * Comma-separated Spring profile names where {@code chaos.enabled=true} is forbidden.
     * {@link ChaosProductionGuard} enforces this at startup.
     * Default: {@code prod,production,live}.
     */
    private String forbiddenProfiles = "prod,production,live";

    /**
     * Hard upper bound on injected latency in milliseconds.
     * Prevents accidental thread-pool starvation from misconfigured values (e.g. "1000m").
     * Default: 30,000 ms (30 seconds).
     */
    private long maxLatencyMillis = 30_000L;

    /**
     * Optional allowlist of fully-qualified exception class names permitted for injection.
     * When empty (default), any {@link Throwable} subclass is accepted.
     * When non-empty, only listed classes may be used — others fall back to {@link RuntimeException}.
     * Example: {@code ["java.lang.RuntimeException", "java.io.IOException"]}
     */
    private List<String> allowedExceptionClasses = new ArrayList<String>();

    /**
     * Maximum number of chaos injection events retained in the in-memory event store.
     * Older events are evicted when the buffer is full. Default: 100.
     */
    private int eventStoreCapacity = 100;

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
        if (defaultFailureRate < 0.0 || defaultFailureRate > 1.0) {
            throw new IllegalArgumentException(
                "[HavocFlow] defaultFailureRate must be between 0.0 and 1.0, got: " + defaultFailureRate);
        }
        this.defaultFailureRate = defaultFailureRate;
    }

    public boolean isLogGremlins() {
        return logGremlins;
    }

    public void setLogGremlins(boolean logGremlins) {
        this.logGremlins = logGremlins;
    }

    public int getEventStoreCapacity() {
        return eventStoreCapacity;
    }

    public void setEventStoreCapacity(int eventStoreCapacity) {
        if (eventStoreCapacity <= 0) {
            throw new IllegalArgumentException(
                "[HavocFlow] eventStoreCapacity must be > 0, got: " + eventStoreCapacity);
        }
        this.eventStoreCapacity = eventStoreCapacity;
    }

    public List<String> getAllowedExceptionClasses() {
        return allowedExceptionClasses;
    }

    public void setAllowedExceptionClasses(List<String> allowedExceptionClasses) {
        this.allowedExceptionClasses = allowedExceptionClasses;
    }

    public String getForbiddenProfiles() {
        return forbiddenProfiles;
    }

    public void setForbiddenProfiles(String forbiddenProfiles) {
        this.forbiddenProfiles = forbiddenProfiles;
    }

    public long getMaxLatencyMillis() {
        return maxLatencyMillis;
    }

    public void setMaxLatencyMillis(long maxLatencyMillis) {
        if (maxLatencyMillis <= 0) {
            throw new IllegalArgumentException(
                "[HavocFlow] maxLatencyMillis must be > 0, got: " + maxLatencyMillis);
        }
        this.maxLatencyMillis = maxLatencyMillis;
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
            if (failureRate < 0.0 || failureRate > 1.0) {
                throw new IllegalArgumentException(
                    "[HavocFlow] failureRate must be between 0.0 and 1.0, got: " + failureRate);
            }
            this.failureRate = failureRate;
        }

        public String getException() {
            return exception;
        }

        public void setException(String exception) {
            this.exception = exception;
        }

        /** Optional name of a {@link io.havocflow.spi.ChaosStrategy} bean to invoke. */
        private String strategy = "";

        public String getStrategy() { return strategy; }
        public void setStrategy(String strategy) { this.strategy = strategy; }
    }

    // -----------------------------------------------------------------------
    // HTTP fault injection configuration
    // -----------------------------------------------------------------------

    /**
     * Configuration for the HTTP-layer fault injection filter.
     * <pre>
     * chaos:
     *   http-fault:
     *     enabled: true
     *     failure-rate: 0.1
     *     http-status: 503
     *     latency: "200ms"
     *     path-patterns:
     *       - "/api/**"
     * </pre>
     */
    public static class HttpFaultProperties {

        /** Whether HTTP fault injection is active. Default: false. */
        private boolean enabled = false;

        /** Probability (0.0–1.0) of returning an error HTTP status. Default: 0.1. */
        private double failureRate = 0.1;

        /** HTTP status code to return when fault fires. Default: 503. */
        private int httpStatus = 503;

        /** Optional latency to inject before the request is processed. */
        private String latency = "";

        /** URL path patterns that the filter applies to (Ant-style). Empty = all paths. */
        private List<String> pathPatterns = new ArrayList<String>();

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }

        public double getFailureRate() { return failureRate; }
        public void setFailureRate(double failureRate) {
            if (failureRate < 0.0 || failureRate > 1.0) {
                throw new IllegalArgumentException(
                    "[HavocFlow] http-fault.failureRate must be between 0.0 and 1.0, got: " + failureRate);
            }
            this.failureRate = failureRate;
        }

        public int getHttpStatus() { return httpStatus; }
        public void setHttpStatus(int httpStatus) { this.httpStatus = httpStatus; }

        public String getLatency() { return latency; }
        public void setLatency(String latency) { this.latency = latency; }

        public List<String> getPathPatterns() { return pathPatterns; }
        public void setPathPatterns(List<String> pathPatterns) { this.pathPatterns = pathPatterns; }
    }

    private HttpFaultProperties httpFault = new HttpFaultProperties();

    public HttpFaultProperties getHttpFault() { return httpFault; }
    public void setHttpFault(HttpFaultProperties httpFault) { this.httpFault = httpFault; }

    // -----------------------------------------------------------------------
    // Gremlin strategy configuration
    // -----------------------------------------------------------------------

    /**
     * Configuration for the built-in gremlin {@link io.havocflow.spi.ChaosStrategy} plugins.
     * <pre>
     * chaos:
     *   gremlins:
     *     cpu-threads: 0          # 0 = use availableProcessors()
     *     cpu-duration-millis: 1000
     *     memory-allocation-mb: 50
     *     connection-count: 3
     *     connection-hold-millis: 2000
     * </pre>
     */
    public static class GremlinProperties {

        /** CPU-spinning thread count. 0 means use {@link Runtime#availableProcessors()}. */
        private int cpuThreads = 0;

        /** Duration of CPU stress in milliseconds. Capped at 5000ms. */
        private long cpuDurationMillis = 1_000L;

        /** Megabytes to allocate for memory pressure simulation. Capped at 25% of max heap. */
        private int memoryAllocationMb = 50;

        /** Number of connections to hold for pool exhaustion. */
        private int connectionCount = 3;

        /** How long to hold pooled connections in milliseconds. */
        private long connectionHoldMillis = 2_000L;

        public int getCpuThreads()            { return cpuThreads; }
        public void setCpuThreads(int v)      { this.cpuThreads = v; }

        public long getCpuDurationMillis()    { return cpuDurationMillis; }
        public void setCpuDurationMillis(long v) { this.cpuDurationMillis = v; }

        public int getMemoryAllocationMb()    { return memoryAllocationMb; }
        public void setMemoryAllocationMb(int v) { this.memoryAllocationMb = v; }

        public int getConnectionCount()       { return connectionCount; }
        public void setConnectionCount(int v) { this.connectionCount = v; }

        public long getConnectionHoldMillis() { return connectionHoldMillis; }
        public void setConnectionHoldMillis(long v) { this.connectionHoldMillis = v; }
    }

    private GremlinProperties gremlins = new GremlinProperties();

    public GremlinProperties getGremlins()              { return gremlins; }
    public void setGremlins(GremlinProperties gremlins) { this.gremlins = gremlins; }
}
