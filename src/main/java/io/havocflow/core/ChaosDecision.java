package io.havocflow.core;

/**
 * Immutable value object representing the chaos engine's decision for a single
 * method invocation.
 *
 * <p>Instances are created through the nested {@link Builder} or the
 * {@link #none()} factory method. The {@link io.havocflow.aop.ChaosAspect}
 * reads this object to decide whether to sleep, throw, or proceed normally.
 *
 * <pre>{@code
 * ChaosDecision d = ChaosDecision.builder()
 *     .mode(FailureMode.LATENCY)
 *     .latencyMillis(500)
 *     .scenarioName("inline")
 *     .build();
 * }</pre>
 */
public final class ChaosDecision {

    private final FailureMode mode;
    private final long latencyMillis;
    private final Class<? extends Throwable> exceptionType;
    private final String scenarioName;

    private ChaosDecision(Builder builder) {
        this.mode = builder.mode;
        this.latencyMillis = builder.latencyMillis;
        this.exceptionType = builder.exceptionType;
        this.scenarioName = builder.scenarioName;
    }

    /**
     * Factory method for the no-op decision — no chaos, proceed normally.
     *
     * @return a {@code ChaosDecision} with mode {@link FailureMode#NONE}
     */
    public static ChaosDecision none() {
        return builder()
                .mode(FailureMode.NONE)
                .latencyMillis(0)
                .scenarioName("none")
                .build();
    }

    /**
     * Returns a new {@link Builder}.
     *
     * @return a fresh builder instance
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * The failure mode chosen for this invocation.
     *
     * @return never {@code null}
     */
    public FailureMode getMode() {
        return mode;
    }

    /**
     * The number of milliseconds of artificial latency to inject.
     * Zero means no latency.
     *
     * @return latency in milliseconds, &ge; 0
     */
    public long getLatencyMillis() {
        return latencyMillis;
    }

    /**
     * The exception class to instantiate and throw.
     * {@code null} when the mode does not involve throwing ({@link FailureMode#NONE}
     * or {@link FailureMode#LATENCY}).
     *
     * @return exception class, or {@code null}
     */
    public Class<? extends Throwable> getExceptionType() {
        return exceptionType;
    }

    /**
     * Human-readable label identifying how this decision was made —
     * either a named scenario key or {@code "inline"} for annotation-attribute resolution.
     *
     * @return never {@code null}
     */
    public String getScenarioName() {
        return scenarioName;
    }

    /**
     * Convenience predicate: {@code true} when this decision will cause an exception to be thrown.
     *
     * @return {@code true} if mode is {@link FailureMode#EXCEPTION} or
     *         {@link FailureMode#LATENCY_AND_EXCEPTION}
     */
    public boolean shouldThrow() {
        return mode == FailureMode.EXCEPTION || mode == FailureMode.LATENCY_AND_EXCEPTION;
    }

    @Override
    public String toString() {
        return "ChaosDecision{mode=" + mode
                + ", latencyMillis=" + latencyMillis
                + ", exceptionType=" + exceptionType
                + ", scenarioName='" + scenarioName + "'}";
    }

    // -----------------------------------------------------------------------
    // Builder
    // -----------------------------------------------------------------------

    /**
     * Fluent builder for {@link ChaosDecision}.
     */
    public static final class Builder {

        private FailureMode mode = FailureMode.NONE;
        private long latencyMillis = 0;
        private Class<? extends Throwable> exceptionType;
        private String scenarioName = "none";

        private Builder() {}

        /** @see ChaosDecision#getMode() */
        public Builder mode(FailureMode mode) {
            this.mode = mode;
            return this;
        }

        /** @see ChaosDecision#getLatencyMillis() */
        public Builder latencyMillis(long latencyMillis) {
            this.latencyMillis = latencyMillis;
            return this;
        }

        /** @see ChaosDecision#getExceptionType() */
        public Builder exceptionType(Class<? extends Throwable> exceptionType) {
            this.exceptionType = exceptionType;
            return this;
        }

        /** @see ChaosDecision#getScenarioName() */
        public Builder scenarioName(String scenarioName) {
            this.scenarioName = scenarioName;
            return this;
        }

        /**
         * Builds and returns the {@link ChaosDecision}.
         *
         * @return a new immutable {@code ChaosDecision}
         */
        public ChaosDecision build() {
            return new ChaosDecision(this);
        }
    }
}
