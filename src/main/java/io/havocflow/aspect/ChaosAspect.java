package io.havocflow.aspect;

import io.havocflow.annotation.InjectChaos;
import io.havocflow.config.ChaosProperties;
import io.havocflow.engine.ChaosDecision;
import io.havocflow.engine.ChaosEngine;
import io.havocflow.engine.FailureMode;
import io.havocflow.metrics.ChaosMetricsRecorder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;

import java.lang.reflect.Constructor;
import java.util.Optional;

/**
 * AOP aspect that intercepts all methods annotated with {@link InjectChaos}
 * (either directly or via a class-level annotation) and delegates to
 * {@link ChaosEngine} to decide what chaos to inject.
 *
 * <p>Method-level annotations take priority over class-level annotations.
 *
 * <p>This aspect is only registered when {@code chaos.enabled=true}.
 * It is never present in a production Spring context.
 */
@Slf4j
@Aspect
@RequiredArgsConstructor
public class ChaosAspect {

    private final ChaosEngine engine;
    private final ChaosProperties properties;
    private final Optional<ChaosMetricsRecorder> metricsRecorder;

    /**
     * Intercepts methods annotated with @InjectChaos directly.
     */
    @Around("@annotation(chaos)")
    public Object aroundAnnotatedMethod(ProceedingJoinPoint pjp, InjectChaos chaos)
            throws Throwable {
        return applyChaos(pjp, chaos);
    }

    /**
     * Intercepts all methods on a class annotated with @InjectChaos,
     * unless the method has its own @InjectChaos (handled above).
     */
    @Around("@within(chaos) && !@annotation(io.havocflow.annotation.InjectChaos)")
    public Object aroundAnnotatedClass(ProceedingJoinPoint pjp, InjectChaos chaos)
            throws Throwable {
        return applyChaos(pjp, chaos);
    }

    // -----------------------------------------------------------------------
    // Core logic
    // -----------------------------------------------------------------------

    private Object applyChaos(ProceedingJoinPoint pjp, InjectChaos chaos) throws Throwable {
        String methodName = resolveMethodName(pjp);
        ChaosDecision decision = engine.resolve(chaos);

        if (decision.getMode() == FailureMode.NONE) {
            return pjp.proceed();
        }

        // Record the event before executing so metrics are captured even if
        // the downstream method itself throws
        metricsRecorder.ifPresent(r -> r.recordGremlin(methodName, decision));

        if (properties.isLogGremlins()) {
            log.warn("[HavocFlow] Gremlin fired — method={} mode={} scenario={} latency={}ms",
                    methodName, decision.getMode(), decision.getScenarioLabel(), decision.getLatencyMs());
        }

        // 1. Inject latency
        if (decision.getLatencyMs() > 0) {
            Thread.sleep(decision.getLatencyMs());
        }

        // 2. Throw exception (if mode is EXCEPTION or BOTH)
        if (decision.getMode() == FailureMode.EXCEPTION || decision.getMode() == FailureMode.BOTH) {
            throw buildException(decision.getExceptionClass(), methodName, decision.getScenarioLabel());
        }

        // 3. Proceed normally (mode was LATENCY only)
        return pjp.proceed();
    }

    private Throwable buildException(Class<? extends Throwable> exClass,
                                      String method, String scenario) {
        String message = String.format(
                "[HavocFlow] Chaos exception injected on %s (scenario: %s)", method, scenario);
        try {
            // Try String constructor first
            Constructor<? extends Throwable> ctor = exClass.getConstructor(String.class);
            return ctor.newInstance(message);
        } catch (NoSuchMethodException e) {
            // Fall back to no-arg constructor
            try {
                return exClass.getDeclaredConstructor().newInstance();
            } catch (Exception ex) {
                log.warn("[HavocFlow] Could not instantiate {}, using RuntimeException", exClass.getName());
                return new RuntimeException(message);
            }
        } catch (Exception e) {
            return new RuntimeException(message, e);
        }
    }

    private String resolveMethodName(ProceedingJoinPoint pjp) {
        MethodSignature sig = (MethodSignature) pjp.getSignature();
        return sig.getDeclaringTypeName() + "." + sig.getMethod().getName();
    }
}
