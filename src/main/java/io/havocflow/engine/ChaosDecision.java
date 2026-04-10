package io.havocflow.engine;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class ChaosDecision {
    private final FailureMode mode;
    private final long latencyMs;
    private final Class<? extends Throwable> exceptionClass;
    private final String scenarioLabel;

    public static ChaosDecision none() {
        return ChaosDecision.builder()
                .mode(FailureMode.NONE)
                .latencyMs(0)
                .scenarioLabel("none")
                .build();
    }
}
