package io.havocflow.event;

import io.havocflow.core.ChaosDecision;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;

/**
 * Thread-safe in-memory ring buffer storing the most recent chaos injection events.
 *
 * <p>When the buffer is full the oldest event is evicted to make room for the new one.
 * The capacity is controlled by {@code chaos.event-store-capacity} (default: 100).
 *
 * <p>Events are accessible via {@link #getRecentEvents()} and exposed through the
 * optional {@code /actuator/chaos} endpoint.
 */
public class ChaosEventStore {

    private final int capacity;
    private final Queue<ChaosEvent> events;

    public ChaosEventStore(int capacity) {
        this.capacity = capacity;
        this.events = new ArrayDeque<ChaosEvent>(capacity);
    }

    /**
     * Records a new chaos event. Evicts the oldest entry when the buffer is full.
     *
     * @param methodName the fully-qualified method name where chaos fired
     * @param decision   the decision that was applied
     */
    public void record(String methodName, ChaosDecision decision) {
        ChaosEvent event = new ChaosEvent(
            Instant.now(),
            methodName,
            decision.getMode().name(),
            decision.getLatencyMillis(),
            decision.getScenarioName()
        );
        synchronized (events) {
            if (events.size() >= capacity) {
                events.poll();
            }
            events.offer(event);
        }
    }

    /**
     * Returns a snapshot of all stored events, oldest first.
     *
     * @return an immutable copy of the event buffer
     */
    public List<ChaosEvent> getRecentEvents() {
        synchronized (events) {
            return new ArrayList<ChaosEvent>(events);
        }
    }

    /**
     * Returns the number of events currently stored.
     */
    public int size() {
        synchronized (events) {
            return events.size();
        }
    }
}
