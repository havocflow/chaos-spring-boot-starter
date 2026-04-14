package io.havocflow;

import io.havocflow.core.ChaosDecision;
import io.havocflow.core.FailureMode;
import io.havocflow.gremlins.ConnectionPoolExhaustionStrategy;
import org.aspectj.lang.ProceedingJoinPoint;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ConnectionPoolExhaustionStrategyTest {

    private DataSource dataSource;
    private Connection connection;
    private ProceedingJoinPoint pjp;

    @BeforeEach
    void setUp() throws Throwable {
        dataSource = mock(DataSource.class);
        connection = mock(Connection.class);
        when(dataSource.getConnection()).thenReturn(connection);
        pjp = mock(ProceedingJoinPoint.class);
        when(pjp.proceed()).thenReturn("ok");
    }

    private static ChaosDecision poolDecision() {
        return ChaosDecision.builder()
                .mode(FailureMode.NONE)
                .latencyMillis(0)
                .scenarioName("connection-pool-exhaustion")
                .build();
    }

    private ConnectionPoolExhaustionStrategy strategy(int connectionCount, long holdMs) {
        // maxLatencyMillis=5000 so cap = min(HARD_CAP=5000, maxLatency=5000)=5000
        return new ConnectionPoolExhaustionStrategy(dataSource, connectionCount, holdMs, 5_000L);
    }

    @Test
    @DisplayName("apply() acquires and releases each connection")
    void apply_acquiresAndReleasesConnections() throws Throwable {
        ConnectionPoolExhaustionStrategy s = strategy(2, 1L);
        s.apply(pjp, poolDecision());

        // 2 connections acquired
        verify(dataSource, times(2)).getConnection();
        // Each must be closed exactly once
        verify(connection, times(2)).close();
    }

    @Test
    @DisplayName("apply() releases connections even when pjp.proceed() throws")
    void apply_releasesConnections_evenWhenProceedThrows() throws Throwable {
        when(pjp.proceed()).thenThrow(new RuntimeException("boom"));
        ConnectionPoolExhaustionStrategy s = strategy(2, 1L);

        assertThatThrownBy(() -> s.apply(pjp, poolDecision()))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("boom");

        // Connections must still be released despite the exception
        verify(connection, times(2)).close();
    }

    @Test
    @DisplayName("apply() calls pjp.proceed() exactly once")
    void apply_callsProceedOnce() throws Throwable {
        ConnectionPoolExhaustionStrategy s = strategy(1, 1L);
        s.apply(pjp, poolDecision());
        verify(pjp, times(1)).proceed();
    }

    @Test
    @DisplayName("Pool already exhausted (SQLException) — still calls pjp.proceed()")
    void poolExhausted_sqlException_stillProceed() throws Throwable {
        when(dataSource.getConnection()).thenThrow(new SQLException("pool exhausted"));
        ConnectionPoolExhaustionStrategy s = strategy(3, 1L);

        Object result = s.apply(pjp, poolDecision());

        verify(pjp, atLeastOnce()).proceed();
        assertThat(result).isEqualTo("ok");
    }

    @Test
    @DisplayName("canHandle returns true for 'connection-pool-exhaustion' scenario")
    void canHandle_connectionPoolExhaustionScenario() {
        ConnectionPoolExhaustionStrategy s = strategy(1, 1L);
        assertThat(s.canHandle(poolDecision())).isTrue();
    }

    @Test
    @DisplayName("canHandle returns false for other scenario names")
    void canHandle_otherScenario_returnsFalse() {
        ConnectionPoolExhaustionStrategy s = strategy(1, 1L);
        ChaosDecision other = ChaosDecision.builder()
                .mode(FailureMode.NONE)
                .scenarioName("cpu-stress")
                .build();
        assertThat(s.canHandle(other)).isFalse();
    }
}
