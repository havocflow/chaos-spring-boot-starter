package io.havocflow.schedule;

import io.havocflow.autoconfigure.ChaosProperties;
import io.havocflow.core.LatencyParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.scheduling.TaskScheduler;

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Manages a time-windowed chaos schedule defined by {@code chaos.schedule.cron} and
 * {@code chaos.schedule.duration}.
 *
 * <p>When the cron expression fires, this scheduler opens a chaos window by setting
 * {@link ChaosProperties#setScheduleWindowActive(boolean) scheduleWindowActive=true}.
 * After the configured {@code duration} elapses, the window is automatically closed
 * ({@code scheduleWindowActive=false}).
 *
 * <p>Before the first cron trigger fires, the window is <em>closed</em> (chaos suppressed)
 * so that applications that enable scheduling do not accidentally inject chaos on startup.
 *
 * <p>Requires Spring's {@link org.springframework.scheduling.TaskScheduler} on the classpath,
 * which is always available when {@code spring-context} is present (Spring Boot auto-configures
 * a {@code ThreadPoolTaskScheduler} by default).
 */
public class ChaosScheduler implements InitializingBean, DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(ChaosScheduler.class);

    private final ChaosProperties properties;
    private final TaskScheduler taskScheduler;

    private final ScheduledExecutorService windowCloser = Executors.newSingleThreadScheduledExecutor(
            r -> {
                Thread t = new Thread(r, "havocflow-window-closer");
                t.setDaemon(true);
                return t;
            });

    private volatile ScheduledFuture<?> closeWindowFuture;

    public ChaosScheduler(ChaosProperties properties, TaskScheduler taskScheduler) {
        this.properties = properties;
        this.taskScheduler = taskScheduler;
    }

    @Override
    public void afterPropertiesSet() {
        ChaosProperties.ScheduleProperties scheduleProps = properties.getSchedule();
        String cron = scheduleProps.getCron();
        if (cron == null || cron.trim().isEmpty()) {
            log.warn("[HavocFlow] chaos.schedule.enabled=true but chaos.schedule.cron is not set — scheduling inactive");
            return;
        }

        // Parse duration once at startup to fail fast on bad config
        long durationMs = LatencyParser.parseMillis(scheduleProps.getDuration());
        if (durationMs <= 0) {
            log.warn("[HavocFlow] chaos.schedule.duration '{}' resolved to 0ms — defaulting to 30 minutes",
                    scheduleProps.getDuration());
            durationMs = 30 * 60 * 1000L;
        }
        final long windowDurationMs = durationMs;

        // Close window initially — chaos is suppressed until the first cron trigger
        properties.setScheduleWindowActive(false);

        // Schedule window-open task using Spring's cron support
        taskScheduler.schedule(new OpenWindowTask(windowDurationMs),
                new org.springframework.scheduling.support.CronTrigger(cron));

        log.info("[HavocFlow] Chaos scheduler armed — cron='{}' duration={}ms", cron, windowDurationMs);
    }

    @Override
    public void destroy() {
        windowCloser.shutdownNow();
        log.info("[HavocFlow] Chaos scheduler shut down");
    }

    // -----------------------------------------------------------------------
    // Inner task
    // -----------------------------------------------------------------------

    private class OpenWindowTask implements Runnable {
        private final long windowDurationMs;

        OpenWindowTask(long windowDurationMs) {
            this.windowDurationMs = windowDurationMs;
        }

        @Override
        public void run() {
            // Cancel any previous close-window timer still running
            ScheduledFuture<?> prev = closeWindowFuture;
            if (prev != null && !prev.isDone()) {
                prev.cancel(false);
            }

            properties.setScheduleWindowActive(true);
            log.info("[HavocFlow] Chaos schedule window OPEN (duration={}ms)", windowDurationMs);

            closeWindowFuture = windowCloser.schedule(() -> {
                properties.setScheduleWindowActive(false);
                log.info("[HavocFlow] Chaos schedule window CLOSED");
            }, windowDurationMs, TimeUnit.MILLISECONDS);
        }
    }
}
