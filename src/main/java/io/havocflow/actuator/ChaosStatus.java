package io.havocflow.actuator;

import io.havocflow.event.ChaosEvent;

import java.util.List;
import java.util.Map;

/**
 * JSON response body for {@code GET /actuator/chaos}.
 */
public class ChaosStatus {

    private final boolean enabled;
    private final boolean dryRun;
    private final double defaultFailureRate;
    private final long maxLatencyMillis;
    private final Map<String, Object> scenarios;
    private final List<ChaosEvent> recentEvents;
    /** Built-in experiment template names available via {@link io.havocflow.autoconfigure.ExperimentTemplateLoader}. */
    private final List<String> builtInTemplates;

    public ChaosStatus(boolean enabled, boolean dryRun, double defaultFailureRate,
                       long maxLatencyMillis, Map<String, Object> scenarios,
                       List<ChaosEvent> recentEvents, List<String> builtInTemplates) {
        this.enabled = enabled;
        this.dryRun = dryRun;
        this.defaultFailureRate = defaultFailureRate;
        this.maxLatencyMillis = maxLatencyMillis;
        this.scenarios = scenarios;
        this.recentEvents = recentEvents;
        this.builtInTemplates = builtInTemplates;
    }

    public boolean isEnabled()             { return enabled; }
    public boolean isDryRun()              { return dryRun; }
    public double getDefaultFailureRate()  { return defaultFailureRate; }
    public long getMaxLatencyMillis()      { return maxLatencyMillis; }
    public Map<String, Object> getScenarios() { return scenarios; }
    public List<ChaosEvent> getRecentEvents() { return recentEvents; }
    public List<String> getBuiltInTemplates() { return builtInTemplates; }
}
