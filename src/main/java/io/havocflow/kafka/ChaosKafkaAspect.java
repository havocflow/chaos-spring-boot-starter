package io.havocflow.kafka;

import io.havocflow.autoconfigure.ChaosProperties;
import io.havocflow.core.LatencyParser;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Constructor;
import java.util.concurrent.ThreadLocalRandom;

/**
 * AOP aspect that applies chaos injection globally to all methods annotated with
 * {@code @KafkaListener}, without requiring {@code @InjectChaos} on each listener.
 *
 * <p>Activated when {@code spring-kafka} is on the classpath <strong>and</strong>
 * {@code chaos.kafka.enabled=true}. Simulates consumer lag (latency) and processing
 * failures (exceptions) to test Kafka consumer resilience.
 *
 * <p>Example configuration:
 * <pre>
 * chaos:
 *   enabled: true
 *   kafka:
 *     enabled: true
 *     latency: "500ms"
 *     failure-rate: 0.1
 *     exception: org.springframework.kafka.KafkaException
 * </pre>
 *
 * <p>This aspect respects {@code chaos.schedule.*} time windows and the global
 * {@code chaos.max-latency-millis} cap.
 */
@Aspect
public class ChaosKafkaAspect {

    private static final Logger log = LoggerFactory.getLogger(ChaosKafkaAspect.class);

    private final ChaosProperties properties;

    /**
     * Constructs a {@code ChaosKafkaAspect}.
     *
     * @param properties the HavocFlow configuration; must not be {@code null}
     */
    public ChaosKafkaAspect(ChaosProperties properties) {
        this.properties = properties;
    }

    /**
     * Intercepts all methods annotated with {@code @KafkaListener} and applies
     * chaos based on {@code chaos.kafka.*} configuration.
     */
    @Around("@annotation(org.springframework.kafka.annotation.KafkaListener)")
    public Object aroundKafkaListener(ProceedingJoinPoint pjp) throws Throwable {
        return applyKafkaChaos(pjp);
    }

    private Object applyKafkaChaos(ProceedingJoinPoint pjp) throws Throwable {
        ChaosProperties.KafkaProperties kafka = properties.getKafka();

        // Guards: pass through if chaos is globally off, kafka chaos is off, or outside schedule window
        if (!properties.isEnabled() || !kafka.isEnabled()) {
            return pjp.proceed();
        }
        if (!properties.isScheduleWindowActive()) {
            return pjp.proceed();
        }

        String methodName = resolveMethodName(pjp);

        // Inject latency (consumer lag simulation)
        String latencyExpr = kafka.getLatency();
        if (latencyExpr != null && !latencyExpr.trim().isEmpty()) {
            long rawMs = LatencyParser.parseMillis(latencyExpr);
            long latencyMs = Math.min(rawMs, properties.getMaxLatencyMillis());
            if (latencyMs > 0) {
                if (properties.isLogGremlins()) {
                    log.warn("[HavocFlow][Kafka] Injecting {}ms consumer lag on {}", latencyMs, methodName);
                }
                try {
                    Thread.sleep(latencyMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }

        // Inject exception (processing failure simulation)
        if (ThreadLocalRandom.current().nextDouble() < kafka.getFailureRate()) {
            Throwable ex = buildException(kafka.getException(), methodName);
            if (properties.isLogGremlins()) {
                log.warn("[HavocFlow][Kafka] Injecting exception {} on {}",
                    ex.getClass().getName(), methodName);
            }
            throw ex;
        }

        return pjp.proceed();
    }

    private String resolveMethodName(ProceedingJoinPoint pjp) {
        MethodSignature sig = (MethodSignature) pjp.getSignature();
        return sig.getDeclaringTypeName() + "." + sig.getMethod().getName();
    }

    /**
     * Loads and instantiates the configured exception class.
     * Falls back to {@link RuntimeException} if the class cannot be found or instantiated.
     */
    private Throwable buildException(String exceptionClassName, String methodName) {
        String message = String.format(
            "[HavocFlow][Kafka] Chaos exception injected on %s", methodName);
        try {
            Class<?> clazz = Class.forName(exceptionClassName);
            if (!Throwable.class.isAssignableFrom(clazz)) {
                log.warn("[HavocFlow][Kafka] '{}' is not a Throwable subclass — using RuntimeException",
                    exceptionClassName);
                return new RuntimeException(message);
            }
            Class<? extends Throwable> exClass = clazz.asSubclass(Throwable.class);
            try {
                Constructor<? extends Throwable> ctor = exClass.getConstructor(String.class);
                return ctor.newInstance(message);
            } catch (NoSuchMethodException e) {
                return exClass.getDeclaredConstructor().newInstance();
            }
        } catch (Exception e) {
            log.warn("[HavocFlow][Kafka] Could not instantiate '{}', using RuntimeException: {}",
                exceptionClassName, e.getMessage());
            return new RuntimeException(message);
        }
    }
}
