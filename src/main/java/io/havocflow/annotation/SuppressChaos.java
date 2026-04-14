package io.havocflow.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Excludes a specific method from chaos injection when the enclosing class (or an
 * interface it implements) carries a class-level {@link InjectChaos} annotation.
 *
 * <p>Example — exempt the write path from chaos while leaving reads affected:
 * <pre>{@code
 * @InjectChaos(latency = "200ms", failureRate = 0.1)
 * public class OrderService {
 *
 *     public Order findById(String id) { ... }   // chaos applied
 *
 *     @SuppressChaos                              // excluded
 *     public Order save(Order order) { ... }
 * }
 * }</pre>
 *
 * <p>This annotation has no effect when placed on a method that also carries
 * {@code @InjectChaos} directly — in that case the method-level annotation wins.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface SuppressChaos {}
