package io.havocflow;

import io.havocflow.autoconfigure.ChaosProperties;
import io.havocflow.core.RampUpCalculator;
import org.assertj.core.data.Offset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link RampUpCalculator}.
 */
class RampUpCalculatorTest {

    private ChaosProperties.ScenarioProperties.RampUpProperties rampUp(
            String duration, double start, double end) {
        ChaosProperties.ScenarioProperties.RampUpProperties r =
            new ChaosProperties.ScenarioProperties.RampUpProperties();
        r.setDuration(duration);
        r.setStartFailureRate(start);
        r.setEndFailureRate(end);
        return r;
    }

    @Test
    @DisplayName("At t=0 (first call), rate equals startFailureRate and startTimeMillis is set")
    void atStart_returnsStartRate_andSetsStartTime() {
        ChaosProperties.ScenarioProperties.RampUpProperties r = rampUp("10m", 0.0, 1.0);
        assertThat(r.getStartTimeMillis()).isEqualTo(-1L);

        long now = System.currentTimeMillis();
        double rate = RampUpCalculator.computeCurrentRate(r, now);

        assertThat(rate).isEqualTo(0.0, Offset.offset(0.01));
        assertThat(r.getStartTimeMillis()).isGreaterThanOrEqualTo(now);
    }

    @Test
    @DisplayName("At t=duration, rate equals endFailureRate")
    void atEnd_returnsEndRate() {
        ChaosProperties.ScenarioProperties.RampUpProperties r = rampUp("10m", 0.0, 1.0);
        long start = System.currentTimeMillis();
        r.setStartTimeMillis(start);
        long durationMs = 10L * 60 * 1000;

        double rate = RampUpCalculator.computeCurrentRate(r, start + durationMs);

        assertThat(rate).isEqualTo(1.0, Offset.offset(0.001));
    }

    @Test
    @DisplayName("Beyond duration, rate is pinned at endFailureRate")
    void beyondDuration_pinnedAtEnd() {
        ChaosProperties.ScenarioProperties.RampUpProperties r = rampUp("10m", 0.0, 0.8);
        long start = System.currentTimeMillis();
        r.setStartTimeMillis(start);
        long wayPast = 60L * 60 * 1000; // 1 hour after start

        double rate = RampUpCalculator.computeCurrentRate(r, start + wayPast);

        assertThat(rate).isEqualTo(0.8, Offset.offset(0.001));
    }

    @Test
    @DisplayName("At t=duration/2, rate is the midpoint between start and end")
    void atHalfDuration_returnsMidpoint() {
        ChaosProperties.ScenarioProperties.RampUpProperties r = rampUp("10m", 0.0, 1.0);
        long start = System.currentTimeMillis();
        r.setStartTimeMillis(start);
        long halfDuration = 5L * 60 * 1000;

        double rate = RampUpCalculator.computeCurrentRate(r, start + halfDuration);

        assertThat(rate).isBetween(0.49, 0.51);
    }

    @Test
    @DisplayName("At t=duration/4, rate is 25% of the way from start to end")
    void atQuarterDuration_returnsQuarterPoint() {
        ChaosProperties.ScenarioProperties.RampUpProperties r = rampUp("10m", 0.0, 1.0);
        long start = System.currentTimeMillis();
        r.setStartTimeMillis(start);
        long quarterDuration = 2L * 60 * 1000 + 30 * 1000; // 2m30s

        double rate = RampUpCalculator.computeCurrentRate(r, start + quarterDuration);

        assertThat(rate).isBetween(0.24, 0.26);
    }

    @Test
    @DisplayName("Non-zero start rate: interpolation starts from startFailureRate, not 0")
    void nonZeroStartRate_interpolatesFromStart() {
        ChaosProperties.ScenarioProperties.RampUpProperties r = rampUp("10m", 0.2, 0.8);
        long start = System.currentTimeMillis();
        r.setStartTimeMillis(start);

        // At t=0, should be 0.2
        assertThat(RampUpCalculator.computeCurrentRate(r, start))
            .isEqualTo(0.2, Offset.offset(0.01));

        // At t=duration, should be 0.8
        assertThat(RampUpCalculator.computeCurrentRate(r, start + 10L * 60 * 1000))
            .isEqualTo(0.8, Offset.offset(0.01));

        // At t=duration/2, should be 0.5
        assertThat(RampUpCalculator.computeCurrentRate(r, start + 5L * 60 * 1000))
            .isBetween(0.49, 0.51);
    }

    @Test
    @DisplayName("setStartFailureRate rejects values below 0.0")
    void setStartFailureRate_rejectsBelowZero() {
        ChaosProperties.ScenarioProperties.RampUpProperties r =
            new ChaosProperties.ScenarioProperties.RampUpProperties();
        assertThatThrownBy(() -> r.setStartFailureRate(-0.1))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("startFailureRate");
    }

    @Test
    @DisplayName("setEndFailureRate rejects values above 1.0")
    void setEndFailureRate_rejectsAboveOne() {
        ChaosProperties.ScenarioProperties.RampUpProperties r =
            new ChaosProperties.ScenarioProperties.RampUpProperties();
        assertThatThrownBy(() -> r.setEndFailureRate(1.5))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("endFailureRate");
    }

    @Test
    @DisplayName("Zero-duration falls back to endFailureRate immediately")
    void zeroDuration_returnsEndRate() {
        ChaosProperties.ScenarioProperties.RampUpProperties r = rampUp("0ms", 0.0, 0.7);
        long now = System.currentTimeMillis();

        double rate = RampUpCalculator.computeCurrentRate(r, now);

        assertThat(rate).isEqualTo(0.7, Offset.offset(0.001));
    }
}
