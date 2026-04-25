package io.havocflow.actuator;

import io.havocflow.autoconfigure.ChaosProperties;
import io.havocflow.autoconfigure.ExperimentTemplateLoader;
import io.havocflow.event.ChaosEventStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.boot.actuate.endpoint.annotation.Selector;
import org.springframework.boot.actuate.endpoint.annotation.WriteOperation;

import java.util.HashMap;
import java.util.Map;

/**
 * Spring Boot Actuator endpoint exposed at {@code /actuator/chaos}.
 *
 * <p>Registered automatically when {@code spring-boot-starter-actuator} is on the
 * classpath and {@code chaos.enabled=true}.
 *
 * <p>Supported operations:
 * <ul>
 *   <li>{@code GET  /actuator/chaos} — current chaos status and recent event history</li>
 *   <li>{@code POST /actuator/chaos/dry-run} — toggle dry-run mode at runtime</li>
 * </ul>
 *
 * <p>Full enable/disable of chaos is intentionally NOT exposed at runtime.
 * Use {@code chaos.enabled} as a startup-time guard only.
 */
@Endpoint(id = "chaos")
public class ChaosEndpoint {

    private static final Logger log = LoggerFactory.getLogger(ChaosEndpoint.class);

    private final ChaosProperties properties;
    private final ChaosEventStore eventStore;

    public ChaosEndpoint(ChaosProperties properties, ChaosEventStore eventStore) {
        this.properties = properties;
        this.eventStore = eventStore;
    }

    /**
     * Returns the current chaos configuration and recent injection history.
     */
    @ReadOperation
    public ChaosStatus status() {
        Map<String, Object> scenarioSummary = new HashMap<String, Object>();
        properties.getScenarios().forEach((name, s) -> {
            Map<String, Object> details = new HashMap<String, Object>();
            details.put("latency", s.getLatency());
            details.put("failureRate", s.getFailureRate());
            details.put("exception", s.getException());
            scenarioSummary.put(name, details);
        });

        return new ChaosStatus(
            properties.isEnabled(),
            properties.isDryRun(),
            properties.getDefaultFailureRate(),
            properties.getMaxLatencyMillis(),
            scenarioSummary,
            eventStore.getRecentEvents(),
            ExperimentTemplateLoader.builtInTemplateNames()
        );
    }

    /**
     * Toggles chaos operation mode at runtime.
     *
     * <p>Currently supports {@code dry-run} as the only writable selector.
     * Example: {@code POST /actuator/chaos/dry-run?enabled=true}
     *
     * @param operation the operation name (currently only {@code "dry-run"} is supported)
     * @param enabled   the new boolean value to set
     */
    @WriteOperation
    public void update(@Selector String operation, boolean enabled) {
        if ("dry-run".equals(operation)) {
            properties.setDryRun(enabled);
            log.info("[HavocFlow] Actuator toggled dry-run to: {}", enabled);
        } else {
            throw new IllegalArgumentException(
                "[HavocFlow] Unknown actuator operation: '" + operation
                + "'. Supported: dry-run");
        }
    }
}
