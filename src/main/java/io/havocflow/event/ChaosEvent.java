package io.havocflow.event;

import java.time.Instant;

/**
 * Immutable record of a single chaos injection event captured by the
 * {@link ChaosEventStore}.
 */
public final class ChaosEvent {

    private final Instant timestamp;
    private final String method;
    private final String mode;
    private final long latencyMs;
    private final String scenario;

    public ChaosEvent(Instant timestamp, String method, String mode,
                      long latencyMs, String scenario) {
        this.timestamp = timestamp;
        this.method = method;
        this.mode = mode;
        this.latencyMs = latencyMs;
        this.scenario = scenario;
    }

    public Instant getTimestamp() { return timestamp; }
    public String getMethod()    { return method; }
    public String getMode()      { return mode; }
    public long getLatencyMs()   { return latencyMs; }
    public String getScenario()  { return scenario; }

    @Override
    public String toString() {
        return "ChaosEvent{timestamp=" + timestamp
            + ", method='" + method + '\''
            + ", mode='" + mode + '\''
            + ", latencyMs=" + latencyMs
            + ", scenario='" + scenario + '\''
            + '}';
    }
}
