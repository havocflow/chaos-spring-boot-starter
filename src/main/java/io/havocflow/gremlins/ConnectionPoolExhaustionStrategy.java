package io.havocflow.gremlins;

import io.havocflow.autoconfigure.ChaosProperties;
import io.havocflow.core.ChaosDecision;
import io.havocflow.spi.ChaosStrategy;
import org.aspectj.lang.ProceedingJoinPoint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * {@link ChaosStrategy} that exhausts a connection pool by acquiring and holding
 * connections without releasing them for a configurable duration.
 *
 * <p>Connections are always returned in a {@code finally} block to prevent permanent
 * pool exhaustion. The hold duration is capped at {@code chaos.max-latency-millis}
 * (default 30s) as an additional safety guard.
 *
 * <p>Only registered when a {@link DataSource} bean is present in the context.
 *
 * <p>Activate by referencing the {@code "connection-pool-exhaustion"} scenario name:
 * <pre>
 * chaos:
 *   scenarios:
 *     my-db-scenario:
 *       strategy: connection-pool-exhaustion
 *       failure-rate: 0.1
 * </pre>
 */
public class ConnectionPoolExhaustionStrategy implements ChaosStrategy {

    private static final Logger log = LoggerFactory.getLogger(ConnectionPoolExhaustionStrategy.class);

    private static final long HARD_CAP_MILLIS = 5_000L;

    private final DataSource dataSource;
    private final int connectionCount;
    private final long holdDurationMillis;

    /**
     * @param dataSource        the pool to exhaust connections from
     * @param connectionCount   number of connections to hold simultaneously; must be &gt; 0
     * @param holdDurationMillis how long to hold connections in milliseconds
     * @param maxLatencyMillis  global cap from {@link ChaosProperties#getMaxLatencyMillis()}
     */
    public ConnectionPoolExhaustionStrategy(DataSource dataSource, int connectionCount,
                                            long holdDurationMillis, long maxLatencyMillis) {
        this.dataSource = dataSource;
        this.connectionCount = connectionCount > 0 ? connectionCount : 3;
        // Cap hold duration to min(HARD_CAP, maxLatencyMillis)
        this.holdDurationMillis = Math.min(
            holdDurationMillis > 0 ? holdDurationMillis : 2_000L,
            Math.min(HARD_CAP_MILLIS, maxLatencyMillis)
        );
    }

    @Override
    public String name() {
        return "connection-pool-exhaustion";
    }

    @Override
    public boolean canHandle(ChaosDecision decision) {
        return "connection-pool-exhaustion".equals(decision.getScenarioName());
    }

    @Override
    public Object apply(ProceedingJoinPoint pjp, ChaosDecision decision) throws Throwable {
        List<Connection> held = new ArrayList<Connection>();
        try {
            for (int i = 0; i < connectionCount; i++) {
                try {
                    held.add(dataSource.getConnection());
                } catch (SQLException e) {
                    // Pool may already be exhausted — stop acquiring, that's the point
                    log.warn("[HavocFlow][connection-pool-exhaustion] Pool exhausted after {} connections", held.size());
                    break;
                }
            }
            log.warn("[HavocFlow][connection-pool-exhaustion] Holding {} connections for {}ms",
                held.size(), holdDurationMillis);
            Thread.sleep(holdDurationMillis);
            return pjp.proceed();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return pjp.proceed();
        } finally {
            for (Connection c : held) {
                try {
                    c.close();
                } catch (SQLException ignored) {
                    // best-effort release
                }
            }
        }
    }
}
