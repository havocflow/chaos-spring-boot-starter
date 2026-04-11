package io.havocflow.gremlins;

import io.havocflow.core.ChaosDecision;
import io.havocflow.spi.ChaosStrategy;
import org.aspectj.lang.ProceedingJoinPoint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.ref.SoftReference;

/**
 * {@link ChaosStrategy} that allocates heap memory to simulate memory pressure.
 *
 * <p>The allocation is held in a {@link SoftReference} so the JVM can reclaim it
 * under genuine memory pressure before throwing {@link OutOfMemoryError}.
 * This makes the strategy safe for use in tests and non-production environments.
 *
 * <p>The allocation is released in a {@code finally} block after the intercepted
 * method returns.
 *
 * <p>Activate by referencing the {@code "memory-pressure"} scenario name:
 * <pre>
 * chaos:
 *   scenarios:
 *     my-mem-scenario:
 *       strategy: memory-pressure
 *       failure-rate: 0.2
 * </pre>
 */
public class MemoryPressureStrategy implements ChaosStrategy {

    private static final Logger log = LoggerFactory.getLogger(MemoryPressureStrategy.class);

    private final int allocationMb;

    /**
     * Creates a strategy that will allocate the given number of megabytes.
     *
     * <p>The actual allocation is automatically capped to 25% of max heap to prevent OOM.
     *
     * @param allocationMb target allocation in megabytes; must be &gt; 0
     */
    public MemoryPressureStrategy(int allocationMb) {
        this.allocationMb = allocationMb > 0 ? allocationMb : 50;
    }

    @Override
    public String name() {
        return "memory-pressure";
    }

    @Override
    public boolean canHandle(ChaosDecision decision) {
        return "memory-pressure".equals(decision.getScenarioName());
    }

    @Override
    public Object apply(ProceedingJoinPoint pjp, ChaosDecision decision) throws Throwable {
        long maxHeap = Runtime.getRuntime().maxMemory();
        long cap = maxHeap / 4;  // hard cap at 25% of max heap
        long requested = (long) allocationMb * 1024 * 1024;
        long actual = Math.min(requested, cap);

        log.warn("[HavocFlow][memory-pressure] Allocating {}MB (capped from {}MB, max-heap={}MB)",
            actual / (1024 * 1024), allocationMb, maxHeap / (1024 * 1024));

        // SoftReference allows GC to reclaim under genuine memory pressure
        SoftReference<byte[]> allocation = new SoftReference<byte[]>(new byte[(int) actual]);

        try {
            return pjp.proceed();
        } finally {
            // Release reference — allow GC to collect after method returns
            allocation.clear();
        }
    }
}
