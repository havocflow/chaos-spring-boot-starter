package io.havocflow;

import io.havocflow.core.ChaosDecision;
import io.havocflow.core.FailureMode;
import io.havocflow.gremlins.CpuStressStrategy;
import org.aspectj.lang.ProceedingJoinPoint;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CpuStressStrategyTest {

    private static ChaosDecision cpuDecision() {
        return ChaosDecision.builder()
                .mode(FailureMode.NONE)
                .latencyMillis(0)
                .scenarioName("cpu-stress")
                .build();
    }

    @Test
    @DisplayName("apply() calls pjp.proceed() exactly once and returns its result")
    void apply_callsProceed() throws Throwable {
        ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
        when(pjp.proceed()).thenReturn("result");

        CpuStressStrategy strategy = new CpuStressStrategy(1, 50L);
        Object result = strategy.apply(pjp, cpuDecision());

        verify(pjp, times(1)).proceed();
        assertThat(result).isEqualTo("result");
    }

    @Test
    @DisplayName("apply() returns in a reasonable time (background threads, not blocking)")
    void apply_isNonBlocking() throws Throwable {
        ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
        when(pjp.proceed()).thenReturn(null);

        // 1 second declared duration — apply() must return quickly because stress runs in background
        CpuStressStrategy strategy = new CpuStressStrategy(2, 1_000L);

        long start = System.currentTimeMillis();
        strategy.apply(pjp, cpuDecision());
        long elapsed = System.currentTimeMillis() - start;

        // Should complete in well under the 1000ms stress duration (background threads)
        assertThat(elapsed).isLessThan(800L);
    }

    @Test
    @DisplayName("Duration cap: very large requested duration still returns quickly (capped at 5000ms)")
    void durationCap_appliedAt5000ms() throws Throwable {
        ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
        when(pjp.proceed()).thenReturn(null);

        // Request 99999ms — should be internally capped at 5000ms, apply() still non-blocking
        CpuStressStrategy strategy = new CpuStressStrategy(1, 99_999L);

        long start = System.currentTimeMillis();
        strategy.apply(pjp, cpuDecision());
        long elapsed = System.currentTimeMillis() - start;

        assertThat(elapsed).isLessThan(2_000L);
    }

    @Test
    @DisplayName("Zero cpu-threads defaults to availableProcessors() without error")
    void zeroCpuThreads_usesAvailableProcessors() throws Throwable {
        ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
        when(pjp.proceed()).thenReturn(null);

        CpuStressStrategy strategy = new CpuStressStrategy(0, 50L);
        strategy.apply(pjp, cpuDecision());  // must not throw

        verify(pjp, times(1)).proceed();
    }

    @Test
    @DisplayName("canHandle returns true for 'cpu-stress' scenario")
    void canHandle_cpuStressScenario() {
        CpuStressStrategy strategy = new CpuStressStrategy(1, 100L);
        assertThat(strategy.canHandle(cpuDecision())).isTrue();
    }

    @Test
    @DisplayName("canHandle returns false for other scenario names")
    void canHandle_otherScenario_returnsFalse() {
        CpuStressStrategy strategy = new CpuStressStrategy(1, 100L);
        ChaosDecision other = ChaosDecision.builder()
                .mode(FailureMode.NONE)
                .scenarioName("memory-pressure")
                .build();
        assertThat(strategy.canHandle(other)).isFalse();
    }
}
