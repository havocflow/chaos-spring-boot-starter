package io.havocflow;

import io.havocflow.autoconfigure.ChaosProperties;
import io.havocflow.kafka.ChaosKafkaAspect;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link ChaosKafkaAspect}.
 */
class ChaosKafkaAspectTest {

    private ChaosProperties properties;
    private ChaosKafkaAspect aspect;
    private ProceedingJoinPoint pjp;

    @BeforeEach
    void setUp() throws Throwable {
        properties = new ChaosProperties();
        properties.setEnabled(true);

        aspect = new ChaosKafkaAspect(properties);

        // Build a mock ProceedingJoinPoint that returns "result" on proceed()
        pjp = mock(ProceedingJoinPoint.class);
        MethodSignature sig = mock(MethodSignature.class);
        Method method = DummyListener.class.getDeclaredMethod("onMessage", String.class);
        when(sig.getMethod()).thenReturn(method);
        when(sig.getDeclaringTypeName()).thenReturn("io.havocflow.DummyListener");
        when(pjp.getSignature()).thenReturn(sig);
        when(pjp.proceed()).thenReturn("result");
    }

    @Test
    @DisplayName("When kafka.enabled=false, proceeds without chaos")
    void kafkaDisabled_proceedsNormally() throws Throwable {
        properties.getKafka().setEnabled(false);

        Object result = aspect.aroundKafkaListener(pjp);

        assertThat(result).isEqualTo("result");
        verify(pjp, times(1)).proceed();
    }

    @Test
    @DisplayName("When chaos.enabled=false, proceeds without chaos")
    void chaosGloballyDisabled_proceedsNormally() throws Throwable {
        properties.setEnabled(false);
        properties.getKafka().setEnabled(true);

        Object result = aspect.aroundKafkaListener(pjp);

        assertThat(result).isEqualTo("result");
        verify(pjp, times(1)).proceed();
    }

    @Test
    @DisplayName("When schedule window is closed, proceeds without chaos")
    void scheduleWindowClosed_proceedsNormally() throws Throwable {
        properties.getKafka().setEnabled(true);
        properties.setScheduleWindowActive(false);

        Object result = aspect.aroundKafkaListener(pjp);

        assertThat(result).isEqualTo("result");
        verify(pjp, times(1)).proceed();
    }

    @Test
    @DisplayName("failureRate=1.0 always throws the configured exception")
    void failureRateOne_throwsException() {
        properties.getKafka().setEnabled(true);
        properties.getKafka().setFailureRate(1.0);
        properties.getKafka().setException(IllegalStateException.class.getName());

        assertThatThrownBy(() -> aspect.aroundKafkaListener(pjp))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("HavocFlow");
    }

    @Test
    @DisplayName("failureRate=0.0 never throws")
    void failureRateZero_neverThrows() throws Throwable {
        properties.getKafka().setEnabled(true);
        properties.getKafka().setFailureRate(0.0);

        Object result = aspect.aroundKafkaListener(pjp);

        assertThat(result).isEqualTo("result");
        verify(pjp, times(1)).proceed();
    }

    @Test
    @DisplayName("Unknown exception class falls back to RuntimeException")
    void unknownExceptionClass_fallsBackToRuntimeException() {
        properties.getKafka().setEnabled(true);
        properties.getKafka().setFailureRate(1.0);
        properties.getKafka().setException("com.example.NonExistentException");

        assertThatThrownBy(() -> aspect.aroundKafkaListener(pjp))
            .isInstanceOf(RuntimeException.class);
    }

    @Test
    @DisplayName("Latency injection stays within max-latency-millis cap")
    void latency_cappedByMaxLatency() throws Throwable {
        properties.getKafka().setEnabled(true);
        properties.getKafka().setLatency("50ms");
        properties.setMaxLatencyMillis(100L);

        long start = System.currentTimeMillis();
        aspect.aroundKafkaListener(pjp);
        long elapsed = System.currentTimeMillis() - start;

        assertThat(elapsed).isGreaterThanOrEqualTo(40L);
        assertThat(elapsed).isLessThan(500L); // sanity upper bound
        verify(pjp, times(1)).proceed();
    }

    /** Dummy class to provide a real Method reference for the mock signature. */
    @SuppressWarnings("unused")
    static class DummyListener {
        public void onMessage(String message) { }
    }
}
