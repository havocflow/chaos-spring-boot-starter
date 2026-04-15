package io.havocflow.aop;

import io.havocflow.annotation.InjectChaos;
import io.havocflow.annotation.SuppressChaos;
import io.havocflow.audit.ChaosAuditLogger;
import io.havocflow.autoconfigure.ChaosProperties;
import io.havocflow.core.ChaosDecision;
import io.havocflow.core.ChaosEngine;
import io.havocflow.core.FailureMode;
import io.havocflow.event.ChaosEventStore;
import io.havocflow.metrics.ChaosMetricsRecorder;
import io.havocflow.notify.ChaosWebhookNotifier;
import io.havocflow.otel.ChaosOtelSpanRecorder;
import io.havocflow.spi.ChaosStrategy;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
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
    private final Optional<ChaosAuditLogger> auditLogger;
    private final List<ChaosStrategy> strategies;
    private final Optional<ChaosOtelSpanRecorder> otelRecorder;
    private final Optional<ChaosWebhookNotifier> webhookNotifier;

    /**
     * Constructs a {@code ChaosAspect}.
     *
     * @param engine          the decision engine; must not be {@code null}
     * @param properties      the HavocFlow configuration; must not be {@code null}
     * @param metricsRecorder optional Micrometer recorder; empty when Micrometer is not on the classpath
     * @param eventStore      event store for injection history
     * @param auditLogger     optional structured audit logger
     * @param strategies      list of registered {@link ChaosStrategy} plugins; may be empty
     * @param otelRecorder    optional OpenTelemetry span event recorder
     * @param webhookNotifier optional webhook/Slack notifier
     */
    public ChaosAspect(ChaosEngine engine,
                       ChaosProperties properties,
                       Optional<ChaosMetricsRecorder> metricsRecorder,
                       ChaosEventStore eventStore,
                       Optional<ChaosAuditLogger> auditLogger,
                       List<ChaosStrategy> strategies,
                       Optional<ChaosOtelSpanRecorder> otelRecorder,
                       Optional<ChaosWebhookNotifier> webhookNotifier) {
        this.engine = engine;
        this.properties = properties;
        this.metricsRecorder = metricsRecorder;
        this.eventStore = Optional.ofNullable(eventStore);
        this.auditLogger = auditLogger != null ? auditLogger : Optional.<ChaosAuditLogger>empty();
        this.strategies = strategies != null ? strategies : Collections.<ChaosStrategy>emptyList();
        this.otelRecorder = otelRecorder != null ? otelRecorder : Optional.<ChaosOtelSpanRecorder>empty();
        this.webhookNotifier = webhookNotifier != null ? webhookNotifier : Optional.<ChaosWebhookNotifier>empty();
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
    @Around("@within(chaos) && !@annotation(io.havocflow.annotation.InjectChaos) && !@annotation(io.havocflow.annotation.SuppressChaos)")
    public Object aroundAnnotatedClass(ProceedingJoinPoint pjp, InjectChaos chaos)
            throws Throwable {
        return applyChaos(pjp, chaos);
    }

    /**
     * Intercepts public methods where the target class implements an interface
     * annotated with {@code @InjectChaos} (at the interface type or method level).
     *
     * <p>This enables {@code @InjectChaos} on Spring {@code @Component} interfaces so
     * that any concrete bean implementing the interface receives chaos injection without
     * annotating the implementation class directly.
     *
     * <p>Method-level interface annotation takes priority over interface type annotation.
     * This advice only fires when neither the concrete method nor its declaring class
     * carries {@code @InjectChaos} directly (those cases are handled by the two advices above).
     */
    @Around("execution(public * *(..)) && !@annotation(io.havocflow.annotation.InjectChaos) && !@annotation(io.havocflow.annotation.SuppressChaos) && !@within(io.havocflow.annotation.InjectChaos) && !within(io.havocflow.autoconfigure..*) && !within(io.havocflow.aop..*) && !within(org.springframework..*)")
    public Object aroundInterfaceAnnotation(ProceedingJoinPoint pjp) throws Throwable {
        InjectChaos chaos = resolveInterfaceAnnotation(pjp);
        if (chaos == null) {
            return pjp.proceed();
        }
        return applyChaos(pjp, chaos);
    }

    /**
     * Scans the target object's implemented interfaces for an {@code @InjectChaos} annotation.
     * Checks method-level annotation on the interface method first; falls back to the
     * interface type annotation. Returns {@code null} if no annotation is found.
     */
    private InjectChaos resolveInterfaceAnnotation(ProceedingJoinPoint pjp) {
        Object target = pjp.getTarget();
        if (target == null) {
            return null;
        }
        MethodSignature sig = (MethodSignature) pjp.getSignature();
        String methodName = sig.getMethod().getName();
        Class<?>[] paramTypes = sig.getMethod().getParameterTypes();

        for (Class<?> iface : target.getClass().getInterfaces()) {
            // Check for method-level annotation on the interface first
            try {
                Method ifaceMethod = iface.getMethod(methodName, paramTypes);
                InjectChaos methodAnnotation = ifaceMethod.getAnnotation(InjectChaos.class);
                if (methodAnnotation != null) {
                    return methodAnnotation;
                }
            } catch (NoSuchMethodException ignored) {
                // this interface does not declare the method — continue
            }
            // Fall back to interface type-level annotation
            InjectChaos typeAnnotation = iface.getAnnotation(InjectChaos.class);
            if (typeAnnotation != null) {
                return typeAnnotation;
            }
        }
        return null;
    }

    // -----------------------------------------------------------------------
    // Core logic
    // -----------------------------------------------------------------------

    private Object applyChaos(ProceedingJoinPoint pjp, InjectChaos chaos) throws Throwable {
        // Belt-and-suspenders: skip if the concrete method carries @SuppressChaos
        // (handles any future advice path that bypasses the pointcut exclusion).
        Method concreteMethod = ((MethodSignature) pjp.getSignature()).getMethod();
        if (concreteMethod.isAnnotationPresent(SuppressChaos.class)) {
            return pjp.proceed();
        }

        // If a cron schedule is configured and the window is currently closed, pass through
        if (!properties.isScheduleWindowActive()) {
            return pjp.proceed();
        }

        String methodName = resolveMethodName(pjp);
        ChaosDecision decision = engine.decide(chaos, methodName);

        if (decision.getMode() == FailureMode.NONE) {
            return pjp.proceed();
        }

        // Record the event before executing so metrics are captured even if
        // the downstream method itself throws.
        metricsRecorder.ifPresent(r -> r.recordGremlin(methodName, decision));
        eventStore.ifPresent(s -> s.record(methodName, decision));
        auditLogger.ifPresent(a -> a.record(methodName, decision, Thread.currentThread().getName()));
        otelRecorder.ifPresent(o -> o.record(methodName, decision));
        webhookNotifier.ifPresent(n -> n.notifyAsync(methodName, decision));

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
