package io.havocflow.spi;

import io.havocflow.core.ChaosDecision;
import org.aspectj.lang.ProceedingJoinPoint;

/**
 * Extension point for adding custom chaos fault types to HavocFlow.
 *
 * <h3>How to register a strategy</h3>
 * <p>Implement this interface and register your implementation as a Spring bean.
 * HavocFlow will automatically discover all {@code ChaosStrategy} beans in the
 * application context and wire them into the {@link io.havocflow.aop.ChaosAspect}.
 *
 * <pre>{@code
 * @Component
 * public class CpuStressStrategy implements ChaosStrategy {
 *
 *     @Override
 *     public String name() { return "cpu-stress"; }
 *
 *     @Override
 *     public boolean canHandle(ChaosDecision decision) {
 *         return "cpu-stress".equals(decision.getScenarioName());
 *     }
 *
 *     @Override
 *     public Object apply(ProceedingJoinPoint pjp, ChaosDecision decision) throws Throwable {
 *         // inject custom fault here
 *         return pjp.proceed();
 *     }
 * }
 * }</pre>
 *
 * <h3>Invocation order</h3>
 * <p>Strategies are evaluated after the standard latency injection but before
 * the call to {@code pjp.proceed()}. The first strategy whose {@link #canHandle}
 * returns {@code true} is invoked. All subsequent strategies are skipped.
 *
 * <h3>Contract</h3>
 * <p>Implementations must either:
 * <ul>
 *   <li>return the result of {@code pjp.proceed()} (normal case)</li>
 *   <li>return a custom value (e.g. an empty response)</li>
 *   <li>throw a chaos-specific exception</li>
 * </ul>
 */
public interface ChaosStrategy {

    /**
     * Unique identifier for this strategy. Used for logging and scenario matching.
     *
     * @return a short, lowercase, hyphen-separated name (e.g. {@code "cpu-stress"})
     */
    String name();

    /**
     * Determines whether this strategy should handle the given decision.
     *
     * @param decision the resolved chaos decision for the current invocation
     * @return {@code true} if this strategy should apply
     */
    boolean canHandle(ChaosDecision decision);

    /**
     * Applies the chaos strategy.
     *
     * @param pjp      the intercepted join point; call {@code pjp.proceed()} to continue normally
     * @param decision the resolved chaos decision
     * @return the return value of the intercepted method (or a custom value)
     * @throws Throwable to propagate chaos-injected exceptions or re-throw from {@code pjp.proceed()}
     */
    Object apply(ProceedingJoinPoint pjp, ChaosDecision decision) throws Throwable;
}
