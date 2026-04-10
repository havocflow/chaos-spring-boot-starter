package io.havocflow.engine;

import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses human-friendly latency strings into milliseconds.
 *
 * <p>Supported formats:
 * <ul>
 *   <li>{@code "500ms"} — fixed 500 milliseconds</li>
 *   <li>{@code "2s"}    — fixed 2000 milliseconds</li>
 *   <li>{@code "1m"}    — fixed 60000 milliseconds</li>
 *   <li>{@code "100ms-2s"} — random value between 100 ms and 2000 ms (jitter)</li>
 *   <li>{@code "1s-5s"}    — random value between 1s and 5s</li>
 * </ul>
 */
public final class LatencyParser {

    // Matches a single duration token like "500ms", "2s", "1m"
    private static final Pattern SINGLE  = Pattern.compile("^(\\d+)(ms|s|m)$");
    // Matches a jitter range like "100ms-2s"
    private static final Pattern RANGE   = Pattern.compile("^(\\d+)(ms|s|m)-(\\d+)(ms|s|m)$");

    private LatencyParser() {}

    /**
     * Parse a latency expression and return the resolved millisecond value.
     * For range expressions, a random value within the range is returned each call.
     *
     * @param expression the latency string (e.g. "500ms", "1s-3s")
     * @return milliseconds, or 0 if the expression is blank
     * @throws IllegalArgumentException if the expression is non-blank but unparseable
     */
    public static long parseMillis(String expression) {
        if (expression == null || expression.isBlank()) {
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
            return ThreadLocalRandom.current().nextLong(low, high + 1);
        }

        Matcher single = SINGLE.matcher(expr);
        if (single.matches()) {
            return toMillis(Long.parseLong(single.group(1)), single.group(2));
        }

        throw new IllegalArgumentException(
            "Unrecognised latency format: '" + expr + "'. " +
            "Expected formats: '500ms', '2s', '100ms-2s'");
    }

    private static long toMillis(long value, String unit) {
        return switch (unit) {
            case "ms" -> value;
            case "s"  -> value * 1_000L;
            case "m"  -> value * 60_000L;
            default   -> throw new IllegalArgumentException("Unknown time unit: " + unit);
        };
    }
}
