package io.havocflow.aop;

import io.havocflow.annotation.InjectChaos;
import io.havocflow.autoconfigure.ChaosProperties;
import io.havocflow.core.ChaosDecision;
import io.havocflow.core.ChaosEngine;
import io.havocflow.core.FailureMode;
import io.havocflow.event.ChaosEventStore;
import io.havocflow.metrics.ChaosMetricsRecorder;
import io.havocflow.spi.ChaosStrategy;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Constructor;
import java.util.Collections;
import java.util.List;
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
@Aspect
public class ChaosAspect {

    private static final Logger log = LoggerFactory.getLogger(ChaosAspect.class);

    private final ChaosEngine engine;
    private final ChaosProperties properties;
    private final Optional<ChaosMetricsRecorder> metricsRecorder;
    private final Optional<ChaosEventStore> eventStore;
    private final List<ChaosStrategy> strategies;

    /**
     * Constructs a {@code ChaosAspect}.
     *
     * @param engine          the decision engine; must not be {@code null}
     * @param properties      the HavocFlow configuration; must not be {@code null}
     * @param metricsRecorder optional Micrometer recorder; empty when Micrometer is not on the classpath
     * @param eventStore      event store for injection history
     * @param strategies      list of registered {@link ChaosStrategy} plugins; may be empty
     */
    public ChaosAspect(ChaosEngine engine,
                       ChaosProperties properties,
                       Optional<ChaosMetricsRecorder> metricsRecorder,
                       ChaosEventStore eventStore,
                       List<ChaosStrategy> strategies) {
        this.engine = engine;
        this.properties = properties;
        this.metricsRecorder = metricsRecorder;
        this.eventStore = Optional.ofNullable(eventStore);
        this.strategies = strategies != null ? strategies : Collections.<ChaosStrategy>emptyList();
    }

    /**
     * Intercepts methods annotated with {@code @InjectChaos} directly.
     */
    @Around("@annotation(chaos)")
    public Object aroundAnnotatedMethod(ProceedingJoinPoint pjp, InjectChaos chaos)
            throws Throwable {
        return applyChaos(pjp, chaos);
    }

    /**
     * Intercepts all methods on a class annotated with {@code @InjectChaos},
     * unless the method has its own {@code @InjectChaos} (handled by
     * {@link #aroundAnnotatedMethod}).
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
        ChaosDecision decision = engine.decide(chaos, methodName);

        if (decision.getMode() == FailureMode.NONE) {
            return pjp.proceed();
        }

        // Record the event before executing so metrics are captured even if
        // the downstream method itself throws.
        metricsRecorder.ifPresent(r -> r.recordGremlin(methodName, decision));
        eventStore.ifPresent(s -> s.record(methodName, decision));

        if (properties.isLogGremlins()) {
            log.warn("[HavocFlow] Gremlin fired — method={} mode={} scenario={} latency={}ms",
                    methodName, decision.getMode(), decision.getScenarioName(),
                    decision.getLatencyMillis());
        }

        // 1. Inject latency
        if (decision.getLatencyMillis() > 0) {
            Thread.sleep(decision.getLatencyMillis());
        }

        // 2. Throw exception (if mode is EXCEPTION or LATENCY_AND_EXCEPTION)
        if (decision.shouldThrow()) {
            throw buildException(decision.getExceptionType(), methodName, decision.getScenarioName());
        }

        // 3. Delegate to a registered ChaosStrategy plugin if one matches
        for (ChaosStrategy strategy : strategies) {
            if (strategy.canHandle(decision)) {
                log.warn("[HavocFlow] Delegating to strategy '{}' for method={}",
                    strategy.name(), methodName);
                return strategy.apply(pjp, decision);
            }
        }

        // 4. Proceed normally (mode was LATENCY only, or no matching strategy)
        return pjp.proceed();
    }

    private Throwable buildException(Class<? extends Throwable> exClass,
                                     String method, String scenario) {
        String message = String.format(
                "[HavocFlow] Chaos exception injected on %s (scenario: %s)", method, scenario);
        try {
            Constructor<? extends Throwable> ctor = exClass.getConstructor(String.class);
            return ctor.newInstance(message);
        } catch (NoSuchMethodException e) {
            try {
                return exClass.getDeclaredConstructor().newInstance();
            } catch (Exception ex) {
                log.warn("[HavocFlow] Could not instantiate {}, using RuntimeException",
                        exClass.getName());
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
