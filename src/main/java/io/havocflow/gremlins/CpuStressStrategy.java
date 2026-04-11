package io.havocflow.gremlins;

import io.havocflow.core.ChaosDecision;
import io.havocflow.spi.ChaosStrategy;
import org.aspectj.lang.ProceedingJoinPoint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * {@link ChaosStrategy} that spins background threads to simulate CPU pressure.
 *
 * <p>Worker threads are started before the intercepted method proceeds and terminate
 * after {@code chaos.gremlins.cpu-duration-millis} (default: 1000ms, max: 5000ms).
 * The calling thread is not blocked — the method proceeds immediately under CPU load.
 *
 * <p>Activate by referencing the {@code "cpu-stress"} scenario name:
 * <pre>
 * chaos:
 *   scenarios:
 *     my-cpu-scenario:
 *       strategy: cpu-stress
 *       failure-rate: 0.3
 * </pre>
 */
public class CpuStressStrategy implements ChaosStrategy {

    private static final Logger log = LoggerFactory.getLogger(CpuStressStrategy.class);

    private static final long MAX_DURATION_MILLIS = 5_000L;
    private static final long DEFAULT_DURATION_MILLIS = 1_000L;

    private final int threads;
    private final long durationMillis;

    /**
     * Creates a strategy using the specified thread count and duration.
     *
     * @param threads       number of CPU-spinning threads; 0 means use {@link Runtime#availableProcessors()}
     * @param durationMillis duration of CPU stress in milliseconds; capped at 5000ms
     */
    public CpuStressStrategy(int threads, long durationMillis) {
        this.threads = threads > 0 ? threads : Runtime.getRuntime().availableProcessors();
        this.durationMillis = Math.min(durationMillis > 0 ? durationMillis : DEFAULT_DURATION_MILLIS,
            MAX_DURATION_MILLIS);
    }

    @Override
    public String name() {
        return "cpu-stress";
    }

    @Override
    public boolean canHandle(ChaosDecision decision) {
        return "cpu-stress".equals(decision.getScenarioName());
    }

    @Override
    public Object apply(ProceedingJoinPoint pjp, ChaosDecision decision) throws Throwable {
        startStress();
        return pjp.proceed();
    }

    void startStress() {
        int threadCount = this.threads;
        long duration = this.durationMillis;
        log.warn("[HavocFlow][cpu-stress] Spinning {} threads for {}ms", threadCount, duration);

        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        long deadline = System.currentTimeMillis() + duration;
        for (int i = 0; i < threadCount; i++) {
            executor.submit(new Runnable() {
                @Override
                public void run() {
                    // Intentional CPU burn — each iteration does a tiny computation
                    while (System.currentTimeMillis() < deadline) {
                        Math.sqrt(Math.random());
                    }
                }
            });
        }
        executor.shutdown();
        // Stress runs in background — executor will be GC'd after threads finish
    }
}
