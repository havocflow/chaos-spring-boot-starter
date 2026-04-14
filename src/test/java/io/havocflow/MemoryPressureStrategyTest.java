package io.havocflow;

import io.havocflow.core.ChaosDecision;
import io.havocflow.core.FailureMode;
import io.havocflow.gremlins.MemoryPressureStrategy;
import org.aspectj.lang.ProceedingJoinPoint;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MemoryPressureStrategyTest {

    private static ChaosDecision memDecision() {
        return ChaosDecision.builder()
                .mode(FailureMode.NONE)
                .latencyMillis(0)
                .scenarioName("memory-pressure")
                .build();
    }

    @Test
    @DisplayName("apply() calls pjp.proceed() and returns its result")
    void apply_callsProceed_returnsResult() throws Throwable {
        ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
        when(pjp.proceed()).thenReturn("ok");

        MemoryPressureStrategy strategy = new MemoryPressureStrategy(10);
        Object result = strategy.apply(pjp, memDecision());

        verify(pjp, times(1)).proceed();
        assertThat(result).isEqualTo("ok");
    }

    @Test
    @DisplayName("Very large allocation request does not cause OutOfMemoryError (25% cap)")
    void allocationCap_doesNotOOM() {
        ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);

        // Request an absurdly large number — must be capped at 25% of max heap
        MemoryPressureStrategy strategy = new MemoryPressureStrategy(Integer.MAX_VALUE / (1024 * 1024) + 1);

        assertThatCode(() -> strategy.apply(pjp, memDecision())).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Zero allocationMb defaults to 50MB without error")
    void zeroAllocationMb_defaults50Mb() {
        ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
        MemoryPressureStrategy strategy = new MemoryPressureStrategy(0);

        assertThatCode(() -> strategy.apply(pjp, memDecision())).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("apply() always calls proceed even when allocation is near the cap")
    void apply_alwaysCallsProceed() throws Throwable {
        ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
        when(pjp.proceed()).thenReturn(null);

        MemoryPressureStrategy strategy = new MemoryPressureStrategy(100);
        strategy.apply(pjp, memDecision());

        verify(pjp, times(1)).proceed();
    }

    @Test
    @DisplayName("canHandle returns true for 'memory-pressure' scenario")
    void canHandle_memoryPressureScenario() {
        MemoryPressureStrategy strategy = new MemoryPressureStrategy(10);
        assertThat(strategy.canHandle(memDecision())).isTrue();
    }

    @Test
    @DisplayName("canHandle returns false for other scenario names")
    void canHandle_otherScenario_returnsFalse() {
        MemoryPressureStrategy strategy = new MemoryPressureStrategy(10);
        ChaosDecision other = ChaosDecision.builder()
                .mode(FailureMode.NONE)
                .scenarioName("cpu-stress")
                .build();
        assertThat(strategy.canHandle(other)).isFalse();
    }
}
