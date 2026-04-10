package io.havocflow.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.HashMap;
import java.util.Map;

/**
 * All HavocFlow configuration, bound from application.yml under the "chaos" prefix.
 *
 * <pre>
 * chaos:
 *   enabled: true
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
@Data
@ConfigurationProperties(prefix = "chaos")
public class ChaosProperties {

    /** Master switch. Must be explicitly true — never defaults on. */
    private boolean enabled = false;

    /** Fallback failure rate when @InjectChaos has no failureRate set. */
    private double defaultFailureRate = 0.0;

    /** Whether to write a WARN log every time a gremlin fires. */
    private boolean logGremlins = true;

    /** Named scenario definitions. Key = scenario name used in @InjectChaos(scenario=...) */
    private Map<String, ChaosScenarioProperties> scenarios = new HashMap<>();
}
