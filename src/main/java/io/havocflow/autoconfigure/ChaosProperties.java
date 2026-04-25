package io.havocflow.autoconfigure;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

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
    private volatile boolean dryRun = false;

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
    private Map<String, ScenarioProperties> scenarios = new ConcurrentHashMap<String, ScenarioProperties>();

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

        /**
         * Optional name of a base scenario to inherit from.
         * Fields set in this scenario override the base; unset fields fall back to the base value.
         * <pre>
         * chaos:
         *   scenarios:
         *     base-latency:
         *       latency: "100ms"
         *       failure-rate: 0.05
         *     db-timeout:
         *       extends: base-latency
         *       latency: "3s"          # overrides base latency
         *       # failure-rate inherited from base-latency (0.05)
         * </pre>
         */
        private String extendsScenario = "";

        public String getExtendsScenario() { return extendsScenario; }
        public void setExtendsScenario(String extendsScenario) { this.extendsScenario = extendsScenario; }

        /**
         * Runtime flag for lifecycle-aware chaos activation (e.g. via {@link io.havocflow.test.ChaosContainerSupport}).
         * When {@code false}, this scenario does not inject any chaos regardless of its configured rates.
         * Defaults to {@code true} — scenarios are active unless explicitly toggled off.
         * Declared {@code volatile} for cross-thread visibility.
         */
        private volatile boolean active = true;

        public boolean isActive()           { return active; }
        public void setActive(boolean active) { this.active = active; }

        /**
         * Configuration for gradual fault probability ramp-up.
         *
         * <p>When enabled, the effective {@code failureRate} is linearly interpolated from
         * {@code startFailureRate} to {@code endFailureRate} over the configured {@code duration}.
         * The ramp clock starts on the first invocation of the scenario.
         *
         * <pre>
         * chaos:
         *   scenarios:
         *     my-scenario:
         *       latency: "200ms"
         *       ramp-up:
         *         enabled: true
         *         duration: "10m"
         *         start-failure-rate: 0.0
         *         end-failure-rate: 0.5
         * </pre>
         */
        public static class RampUpProperties {

            /** Whether fault ramp-up is active for this scenario. Default: {@code false}. */
            private boolean enabled = false;

            /**
             * Duration over which to ramp from {@code startFailureRate} to {@code endFailureRate}.
             * Supports the same formats as {@code @InjectChaos(latency)}: e.g. {@code "10m"}, {@code "30s"}.
             * Default: {@code "10m"}.
             */
            private String duration = "10m";

            /** Failure rate at the start of the ramp (t=0). Range: 0.0–1.0. Default: {@code 0.0}. */
            private double startFailureRate = 0.0;

            /** Failure rate at the end of the ramp (t=duration). Range: 0.0–1.0. Default: {@code 1.0}. */
            private double endFailureRate = 1.0;

            /**
             * Epoch-millisecond timestamp of the first invocation.
             * Set lazily by {@link io.havocflow.core.RampUpCalculator} on the first call.
             * Not a configuration property — not bound from {@code application.yml}.
             * Declared {@code volatile} for cross-thread visibility (benign single-write race).
             */
            private volatile long startTimeMillis = -1L;

            public boolean isEnabled()              { return enabled; }
            public void setEnabled(boolean v)       { this.enabled = v; }
            public String getDuration()             { return duration; }
            public void setDuration(String v)       { this.duration = v; }
            public double getStartFailureRate()     { return startFailureRate; }
            public void setStartFailureRate(double v) {
                if (v < 0.0 || v > 1.0) {
                    throw new IllegalArgumentException(
                        "[HavocFlow] ramp-up startFailureRate must be between 0.0 and 1.0, got: " + v);
                }
                this.startFailureRate = v;
            }
            public double getEndFailureRate()       { return endFailureRate; }
            public void setEndFailureRate(double v) {
                if (v < 0.0 || v > 1.0) {
                    throw new IllegalArgumentException(
                        "[HavocFlow] ramp-up endFailureRate must be between 0.0 and 1.0, got: " + v);
                }
                this.endFailureRate = v;
            }
            public long getStartTimeMillis()        { return startTimeMillis; }
            public void setStartTimeMillis(long v)  { this.startTimeMillis = v; }
        }

        private RampUpProperties rampUp = new RampUpProperties();

        public RampUpProperties getRampUp()              { return rampUp; }
        public void setRampUp(RampUpProperties rampUp)   { this.rampUp = rampUp; }
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

        /** Response body text written when an HTTP fault fires. Default: "[HavocFlow] HTTP fault injected". */
        private String responseBody = "[HavocFlow] HTTP fault injected";

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

        public String getResponseBody() { return responseBody; }
        public void setResponseBody(String responseBody) { this.responseBody = responseBody; }
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

    // -----------------------------------------------------------------------
    // Scheduling configuration
    // -----------------------------------------------------------------------

    /**
     * Configuration for time-windowed chaos activation.
     * <pre>
     * chaos:
     *   schedule:
     *     enabled: true
     *     cron: "0 0 10 * * MON-FRI"   # every weekday at 10:00
     *     duration: "30m"               # active for 30 minutes then auto-off
     * </pre>
     */
    public static class ScheduleProperties {

        /** Whether cron-based chaos scheduling is enabled. Default: false. */
        private boolean enabled = false;

        /**
         * Spring cron expression for when the chaos window opens.
         * Uses six-field format: {@code second minute hour day-of-month month day-of-week}.
         */
        private String cron = "";

        /**
         * How long each chaos window stays open before auto-disabling.
         * Supports the same format as {@code @InjectChaos(latency)}: e.g. {@code "30m"}, {@code "5s"}.
         * Default: {@code "30m"}.
         */
        private String duration = "30m";

        public boolean isEnabled()          { return enabled; }
        public void setEnabled(boolean v)   { this.enabled = v; }
        public String getCron()             { return cron; }
        public void setCron(String v)       { this.cron = v; }
        public String getDuration()         { return duration; }
        public void setDuration(String v)   { this.duration = v; }
    }

    private ScheduleProperties schedule = new ScheduleProperties();

    public ScheduleProperties getSchedule()               { return schedule; }
    public void setSchedule(ScheduleProperties schedule)  { this.schedule = schedule; }

    /**
     * Runtime flag set by {@link io.havocflow.schedule.ChaosScheduler} when scheduling is active.
     * When {@code false}, {@code ChaosAspect} passes all calls through without injecting chaos.
     * Defaults to {@code true} so that non-scheduled setups are unaffected.
     */
    private volatile boolean scheduleWindowActive = true;

    public boolean isScheduleWindowActive()               { return scheduleWindowActive; }
    public void setScheduleWindowActive(boolean active)   { this.scheduleWindowActive = active; }

    // -----------------------------------------------------------------------
    // Audit log configuration
    // -----------------------------------------------------------------------

    /**
     * Configuration for the structured chaos audit log.
     * <pre>
     * chaos:
     *   audit-log:
     *     enabled: true
     *     path: /var/log/havocflow-audit.jsonl
     * </pre>
     */
    public static class AuditLogProperties {

        /** Whether the audit log is enabled. Default: false. */
        private boolean enabled = false;

        /**
         * Path of the append-only JSON-lines audit file.
         * Relative paths are resolved from the working directory.
         * Default: {@code havocflow-audit.jsonl}.
         */
        private String path = "havocflow-audit.jsonl";

        public boolean isEnabled()        { return enabled; }
        public void setEnabled(boolean v) { this.enabled = v; }
        public String getPath()           { return path; }
        public void setPath(String v)     { this.path = v; }
    }

    private AuditLogProperties auditLog = new AuditLogProperties();

    public AuditLogProperties getAuditLog()               { return auditLog; }
    public void setAuditLog(AuditLogProperties auditLog)  { this.auditLog = auditLog; }

    // -----------------------------------------------------------------------
    // Kafka chaos configuration
    // -----------------------------------------------------------------------

    /**
     * Configuration for Spring Kafka consumer chaos injection.
     *
     * <p>When enabled, chaos is applied globally to all {@code @KafkaListener} methods
     * without requiring {@code @InjectChaos} on each listener.
     *
     * <pre>
     * chaos:
     *   kafka:
     *     enabled: true
     *     latency: "500ms"
     *     failure-rate: 0.1
     *     exception: org.springframework.kafka.KafkaException
     * </pre>
     */
    public static class KafkaProperties {

        /** Whether Kafka chaos injection is active. Default: {@code false}. */
        private boolean enabled = false;

        /**
         * Latency to inject before each {@code @KafkaListener} invocation.
         * Supports the same formats as {@code @InjectChaos(latency)}: e.g. {@code "200ms"}, {@code "1s-3s"}.
         * Default: empty (no latency).
         */
        private String latency = "";

        /** Probability (0.0–1.0) that an invocation throws the configured exception. Default: {@code 0.0}. */
        private double failureRate = 0.0;

        /**
         * Fully-qualified name of the exception class to throw when failure fires.
         * Default: {@code java.lang.RuntimeException}.
         */
        private String exception = "java.lang.RuntimeException";

        public boolean isEnabled()          { return enabled; }
        public void setEnabled(boolean v)   { this.enabled = v; }
        public String getLatency()          { return latency; }
        public void setLatency(String v)    { this.latency = v; }
        public String getException()        { return exception; }
        public void setException(String v)  { this.exception = v; }

        public double getFailureRate() { return failureRate; }
        public void setFailureRate(double v) {
            if (v < 0.0 || v > 1.0) {
                throw new IllegalArgumentException(
                    "[HavocFlow] chaos.kafka.failure-rate must be between 0.0 and 1.0, got: " + v);
            }
            this.failureRate = v;
        }
    }

    private KafkaProperties kafka = new KafkaProperties();

    public KafkaProperties getKafka()               { return kafka; }
    public void setKafka(KafkaProperties kafka)     { this.kafka = kafka; }

    // -----------------------------------------------------------------------
    // Notification (webhook/Slack) configuration
    // -----------------------------------------------------------------------

    /**
     * Configuration for webhook and Slack notifications fired when a gremlin activates.
     *
     * <pre>
     * chaos:
     *   notifications:
     *     enabled: true
     *     webhook-url: https://hooks.slack.com/services/...
     *     modes:
     *       - EXCEPTION
     *       - LATENCY_AND_EXCEPTION
     * </pre>
     */
    public static class NotificationProperties {

        /** Whether webhook notifications are active. Default: {@code false}. */
        private boolean enabled = false;

        /**
         * Target URL for the JSON POST. Required when {@code enabled=true}.
         * Works with any webhook endpoint including Slack Incoming Webhooks.
         */
        private String webhookUrl = "";

        /**
         * Filter: only send a notification when the fired {@link io.havocflow.core.FailureMode}
         * name is in this list. An empty list means notify on ALL modes (except NONE).
         * Valid values: {@code LATENCY}, {@code EXCEPTION}, {@code LATENCY_AND_EXCEPTION}.
         */
        private List<String> modes = new ArrayList<String>();

        public boolean isEnabled()            { return enabled; }
        public void setEnabled(boolean v)     { this.enabled = v; }
        public String getWebhookUrl()         { return webhookUrl; }
        public void setWebhookUrl(String v)   { this.webhookUrl = v; }
        public List<String> getModes()        { return modes; }
        public void setModes(List<String> v)  { this.modes = v; }
    }

    private NotificationProperties notifications = new NotificationProperties();

    public NotificationProperties getNotifications()                    { return notifications; }
    public void setNotifications(NotificationProperties notifications)  { this.notifications = notifications; }

    // -----------------------------------------------------------------------
    // Runtime lifecycle helpers (01.04.00)
    // -----------------------------------------------------------------------

    /**
     * Temporarily activates the named scenario for the given duration, then deactivates it.
     *
     * <p>If the scenario is not found, this method is a no-op.
     * Activation and deactivation are driven by {@link ScenarioProperties#setActive(boolean)}.
     * The restore runs on a background daemon thread so the calling thread is not blocked.
     *
     * <p>Primary use case: {@link io.havocflow.test.ChaosContainerSupport} ties chaos lifecycle
     * to Testcontainers container start/stop events.
     *
     * @param name            the scenario name as defined in {@code chaos.scenarios.*}
     * @param durationSeconds how many seconds to hold the activated state before reverting
     */
    public void activateScenario(final String name, final int durationSeconds) {
        final ScenarioProperties scenario = scenarios.get(name);
        if (scenario == null) {
            return;
        }
        scenario.setActive(true);
        Thread restorer = new Thread(new Runnable() {
            public void run() {
                try {
                    Thread.sleep(durationSeconds * 1000L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                scenario.setActive(false);
            }
        }, "havocflow-scenario-restorer");
        restorer.setDaemon(true);
        restorer.start();
    }
}
