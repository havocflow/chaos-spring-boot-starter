package io.havocflow.core;

import io.havocflow.autoconfigure.ChaosProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Pure utility class that computes the current effective failure rate for a scenario
 * that has fault ramp-up enabled.
 *
 * <p>The rate is linearly interpolated from
 * {@link ChaosProperties.ScenarioProperties.RampUpProperties#getStartFailureRate()}
 * to {@link ChaosProperties.ScenarioProperties.RampUpProperties#getEndFailureRate()}
 * over the configured {@code duration}.
 *
 * <p>The {@code startTimeMillis} is set lazily on the first call — the ramp clock
 * starts the first time any invocation for the scenario is evaluated. After the full
 * duration elapses, the rate is pinned at {@code endFailureRate}.
 *
 * <p>Thread safety: {@code startTimeMillis} is {@code volatile}. In a concurrent burst,
 * two threads may both observe {@code -1L} and both write. The writes are both "now"
 * (within nanoseconds), making the race benign for the ramp-up use case.
 */
public final class RampUpCalculator {

    private static final Logger log = LoggerFactory.getLogger(RampUpCalculator.class);

    private RampUpCalculator() {}

    /**
     * Computes the current failure rate for the given ramp-up configuration.
     *
     * <p>Side effect: if {@code rampUp.startTimeMillis} is {@code -1} (unset),
     * it is set to {@code nowMillis} and an INFO log is emitted (ramp clock starts).
     *
     * @param rampUp     the ramp-up configuration from the scenario; must not be {@code null}
     * @param nowMillis  current epoch time in milliseconds (use {@link System#currentTimeMillis()})
     * @return the interpolated failure rate, clamped to [0.0, 1.0]
     */
    public static double computeCurrentRate(
            ChaosProperties.ScenarioProperties.RampUpProperties rampUp,
            long nowMillis) {

        // Lazy start: record wall-clock of first invocation
        if (rampUp.getStartTimeMillis() < 0L) {
            rampUp.setStartTimeMillis(nowMillis);
            log.info("[HavocFlow] Ramp-up started: startRate={} endRate={} duration={}",
                rampUp.getStartFailureRate(), rampUp.getEndFailureRate(), rampUp.getDuration());
        }

        long durationMs = LatencyParser.parseMillis(rampUp.getDuration());
        if (durationMs <= 0) {
            log.warn("[HavocFlow] ramp-up duration '{}' resolves to 0ms — using endFailureRate",
                rampUp.getDuration());
            return clamp(rampUp.getEndFailureRate());
        }

        long elapsed = nowMillis - rampUp.getStartTimeMillis();
        if (elapsed >= durationMs) {
            return clamp(rampUp.getEndFailureRate());
        }

        // Linear interpolation: start + (end - start) * (elapsed / duration)
        double ratio = (double) elapsed / (double) durationMs;
        double interpolated = rampUp.getStartFailureRate()
            + (rampUp.getEndFailureRate() - rampUp.getStartFailureRate()) * ratio;
        return clamp(interpolated);
    }

    private static double clamp(double rate) {
        return Math.min(1.0, Math.max(0.0, rate));
    }
}
