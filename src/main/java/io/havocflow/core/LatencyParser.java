package io.havocflow.core;

import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses human-friendly latency strings into milliseconds.
 *
 * <p>Supported formats:
 * <ul>
 *   <li>{@code "500ms"} — fixed 500 milliseconds</li>
 *   <li>{@code "2s"}    — fixed 2 000 milliseconds</li>
 *   <li>{@code "1m"}    — fixed 60 000 milliseconds</li>
 *   <li>{@code "100ms-2s"} — random value between 100 ms and 2 000 ms (jitter)</li>
 *   <li>{@code "1s-5s"}    — random value between 1 s and 5 s</li>
 * </ul>
 *
 * <p>This class is intentionally dependency-free and uses only {@code java.util}
 * so it can be reused in any Java 8+ environment.
 */
public final class LatencyParser {

    /** Matches a single duration token like {@code 500ms}, {@code 2s}, {@code 1m}. */
    private static final Pattern SINGLE = Pattern.compile("^(\\d+)(ms|s|m)$");

    /** Matches a jitter range like {@code 100ms-2s} or {@code 1s-5s}. */
    private static final Pattern RANGE  = Pattern.compile("^(\\d+)(ms|s|m)-(\\d+)(ms|s|m)$");

    private LatencyParser() {}

    /**
     * Parses a latency expression and returns the resolved millisecond value.
     * For range expressions a random value within {@code [low, high]} is returned on each call.
     *
     * @param expression the latency string (e.g. {@code "500ms"}, {@code "1s-3s"})
     * @return milliseconds, or {@code 0} if the expression is {@code null} or blank
     * @throws IllegalArgumentException if the expression is non-blank but cannot be parsed
     */
    public static long parseMillis(String expression) {
        if (expression == null || expression.trim().isEmpty()) {
            return 0L;
        }

        String expr = expression.trim();

        Matcher range = RANGE.matcher(expr);
        if (range.matches()) {
            long low  = toMillis(Long.parseLong(range.group(1)), range.group(2));
            long high = toMillis(Long.parseLong(range.group(3)), range.group(4));
            if (low > high) {
                throw new IllegalArgumentException(
                    "Latency range lower bound must be <= upper bound: " + expr);
            }
            // When low == high, (high - low) == 0, so nextDouble() * 0 == 0, returning exactly low.
            // Using (high - low) instead of (high - low + 1) keeps the result strictly within [low, high].
            return low + (long) (ThreadLocalRandom.current().nextDouble() * (high - low));
        }

        Matcher single = SINGLE.matcher(expr);
        if (single.matches()) {
            return toMillis(Long.parseLong(single.group(1)), single.group(2));
        }

        throw new IllegalArgumentException(
            "Unrecognised latency format: '" + expr + "'. "
            + "Expected formats: '500ms', '2s', '1m', '100ms-2s'");
    }

    private static long toMillis(long value, String unit) {
        // Guard against values that would overflow or produce absurdly large sleeps.
        // The hard cap in ChaosEngine (default 30 s) enforces the runtime ceiling,
        // but we reject clearly unreasonable values here at parse time.
        if (value > 999_999_999L) {
            throw new IllegalArgumentException(
                "Latency value " + value + unit + " is too large (max numeric value: 999999999)");
        }
        switch (unit) {
            case "ms": return value;
            case "s":  return value * 1_000L;
            case "m":  return value * 60_000L;
            default:   throw new IllegalArgumentException("Unknown time unit: " + unit);
        }
    }
}
