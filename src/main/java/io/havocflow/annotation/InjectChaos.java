package io.havocflow.annotation;

import java.lang.annotation.*;

/**
 * Injects chaos (latency, exceptions) into a method or all methods of a class.
 * Only active when {@code chaos.enabled=true}. Never leaks into production.
 *
 * <pre>{@code
 * @InjectChaos(latency = "2s", failureRate = 0.1)
 * public Order findById(Long id) { ... }
 *
 * @Service
 * @InjectChaos(scenario = "db-timeout")
 * public class PaymentService { ... }
 * }</pre>
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface InjectChaos {

    /**
     * Delay to inject. Formats: "500ms", "2s", "100ms-2s" (jitter range).
     */
    String latency() default "";

    /**
     * Probability (0.0–1.0) that this invocation throws an exception.
     */
    double failureRate() default 0.0;

    /**
     * Exception class to throw. Must have a single-String constructor.
     */
    Class<? extends Throwable> exception() default RuntimeException.class;

    /**
     * Named scenario from chaos.scenarios.* — overrides other fields when set.
     */
    String scenario() default "";

    /**
     * Allow runtime override via application properties.
     */
    boolean overridable() default true;
}
