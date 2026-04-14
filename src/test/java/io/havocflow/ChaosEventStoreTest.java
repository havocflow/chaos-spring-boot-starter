package io.havocflow;

import io.havocflow.core.ChaosDecision;
import io.havocflow.core.FailureMode;
import io.havocflow.event.ChaosEvent;
import io.havocflow.event.ChaosEventStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class ChaosEventStoreTest {

    private static ChaosDecision decision(String scenario) {
        return ChaosDecision.builder()
                .mode(FailureMode.LATENCY)
                .latencyMillis(10)
                .scenarioName(scenario)
                .build();
    }

    @Test
    @DisplayName("Records events up to capacity without eviction")
    void recordsEventsUpToCapacity() {
        ChaosEventStore store = new ChaosEventStore(5);
        for (int i = 0; i < 5; i++) {
            store.record("method-" + i, decision("s"));
        }
        assertThat(store.size()).isEqualTo(5);
        assertThat(store.getRecentEvents()).hasSize(5);
    }

    @Test
    @DisplayName("Evicts the oldest event when capacity is exceeded")
    void evictsOldestWhenFull() {
        ChaosEventStore store = new ChaosEventStore(3);
        store.record("m1", decision("s"));
        store.record("m2", decision("s"));
        store.record("m3", decision("s"));
        store.record("m4", decision("s"));  // causes eviction of m1

        List<ChaosEvent> events = store.getRecentEvents();
        assertThat(events).hasSize(3);
        List<String> methods = new ArrayList<String>();
        for (ChaosEvent e : events) {
            methods.add(e.getMethod());
        }
        assertThat(methods).containsExactly("m2", "m3", "m4");
        assertThat(methods).doesNotContain("m1");
    }

    @Test
    @DisplayName("Returns events in insertion order (oldest first)")
    void returnsEventsOldestFirst() {
        ChaosEventStore store = new ChaosEventStore(5);
        store.record("first", decision("s"));
        store.record("second", decision("s"));
        store.record("third", decision("s"));

        List<ChaosEvent> events = store.getRecentEvents();
        assertThat(events.get(0).getMethod()).isEqualTo("first");
        assertThat(events.get(1).getMethod()).isEqualTo("second");
        assertThat(events.get(2).getMethod()).isEqualTo("third");
    }

    @Test
    @DisplayName("size() tracks current event count")
    void sizeReturnsCurrentCount() {
        ChaosEventStore store = new ChaosEventStore(10);
        assertThat(store.size()).isEqualTo(0);
        store.record("m1", decision("s"));
        assertThat(store.size()).isEqualTo(1);
        store.record("m2", decision("s"));
        assertThat(store.size()).isEqualTo(2);
    }

    @Test
    @DisplayName("New store returns non-null empty list")
    void emptyStore_returnsEmptyList() {
        ChaosEventStore store = new ChaosEventStore(5);
        List<ChaosEvent> events = store.getRecentEvents();
        assertThat(events).isNotNull().isEmpty();
    }

    @Test
    @DisplayName("Concurrent recording does not corrupt the store")
    void concurrentRecording_doesNotCorruptStore() throws InterruptedException {
        int threads = 10;
        int insertsPerThread = 20;
        int capacity = 50;
        ChaosEventStore store = new ChaosEventStore(capacity);

        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(threads);

        for (int t = 0; t < threads; t++) {
            final int threadId = t;
            executor.submit(new Runnable() {
                @Override
                public void run() {
                    ready.countDown();
                    try { go.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                    for (int i = 0; i < insertsPerThread; i++) {
                        store.record("t" + threadId + "-m" + i, decision("s"));
                    }
                }
            });
        }

        ready.await();
        go.countDown();
        executor.shutdown();
        assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();

        assertThat(store.size()).isLessThanOrEqualTo(capacity);
        assertThat(store.getRecentEvents()).isNotNull();
    }
}
