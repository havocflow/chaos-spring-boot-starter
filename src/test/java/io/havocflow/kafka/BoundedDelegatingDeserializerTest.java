package io.havocflow.kafka;

import org.apache.kafka.common.header.Headers;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.apache.kafka.common.serialization.Deserializer;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class BoundedDelegatingDeserializerTest {

    // Tracks close() calls so eviction and shutdown can be verified.
    static class TrackingDeserializer implements Deserializer<String> {
        final AtomicInteger closedCount = new AtomicInteger();

        @Override
        public String deserialize(String topic, byte[] data) {
            return data == null ? null : new String(data, StandardCharsets.UTF_8);
        }

        @Override
        public void close() {
            closedCount.incrementAndGet();
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private Headers valueHeaders(String selector) {
        return new RecordHeaders(new RecordHeader[]{
            new RecordHeader(BoundedDelegatingDeserializer.SELECTOR_HEADER_KEY,
                selector.getBytes(StandardCharsets.UTF_8))
        });
    }

    private Headers keyHeaders(String selector) {
        return new RecordHeaders(new RecordHeader[]{
            new RecordHeader(BoundedDelegatingDeserializer.KEY_SELECTOR_HEADER_KEY,
                selector.getBytes(StandardCharsets.UTF_8))
        });
    }

    private BoundedDelegatingDeserializer<String> configure(int maxSize,
                                                             Map<String, String> classMap) {
        BoundedDelegatingDeserializer<String> d = new BoundedDelegatingDeserializer<>();
        Map<String, Object> cfg = new HashMap<>();
        cfg.put(BoundedDelegatingDeserializer.MAX_CACHE_SIZE_CONFIG, maxSize);
        cfg.put(BoundedDelegatingDeserializer.SELECTOR_CLASS_MAP_CONFIG, classMap);
        d.configure(cfg, false);
        return d;
    }

    private Map<String, String> stringClassMap(String selector) {
        Map<String, String> m = new HashMap<>();
        m.put(selector, "org.apache.kafka.common.serialization.StringDeserializer");
        return m;
    }

    private Map<String, String> trackingClassMap(String... selectors) {
        Map<String, String> m = new HashMap<>();
        for (String s : selectors) {
            m.put(s, TrackingDeserializer.class.getName());
        }
        return m;
    }

    // ── tests ─────────────────────────────────────────────────────────────────

    @Test
    void happyPath_knownSelector_deserializesCorrectly() {
        BoundedDelegatingDeserializer<String> d = configure(10, stringClassMap("str"));
        byte[] data = "hello".getBytes(StandardCharsets.UTF_8);
        assertEquals("hello", d.deserialize("topic", valueHeaders("str"), data));
        d.close();
    }

    @Test
    void nullData_returnsNull() {
        BoundedDelegatingDeserializer<String> d = configure(10, stringClassMap("str"));
        assertNull(d.deserialize("topic", valueHeaders("str"), null));
        d.close();
    }

    @Test
    void unknownSelector_throwsIllegalArgumentException() {
        BoundedDelegatingDeserializer<String> d = configure(10, stringClassMap("str"));
        byte[] data = "x".getBytes(StandardCharsets.UTF_8);
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
            () -> d.deserialize("topic", valueHeaders("evil-random-xyz"), data));
        assertTrue(ex.getMessage().contains(BoundedDelegatingDeserializer.SELECTOR_CLASS_MAP_CONFIG),
            "Exception message should reference the config key");
        d.close();
    }

    @Test
    void missingHeader_throwsIllegalArgumentException() {
        BoundedDelegatingDeserializer<String> d = configure(10, stringClassMap("str"));
        byte[] data = "x".getBytes(StandardCharsets.UTF_8);
        assertThrows(IllegalArgumentException.class,
            () -> d.deserialize("topic", new RecordHeaders(), data));
        d.close();
    }

    @Test
    void noHeadersOverload_throwsUnsupportedOperationException() {
        BoundedDelegatingDeserializer<String> d = configure(10, stringClassMap("str"));
        assertThrows(UnsupportedOperationException.class,
            () -> d.deserialize("topic", "x".getBytes(StandardCharsets.UTF_8)));
        d.close();
    }

    @Test
    void cacheBounded_evictsLRUWhenFull() {
        Map<String, String> classMap = trackingClassMap("s1", "s2", "s3");
        BoundedDelegatingDeserializer<String> d = configure(2, classMap);
        byte[] data = "x".getBytes(StandardCharsets.UTF_8);

        // Populate cache: s1 then s2 (cache = [s1, s2], s2 is MRU)
        d.deserialize("topic", valueHeaders("s1"), data);
        d.deserialize("topic", valueHeaders("s2"), data);

        // Access s1 to make it MRU (cache = [s2, s1], s1 is MRU)
        d.deserialize("topic", valueHeaders("s1"), data);

        // Insert s3 — s2 is LRU and should be evicted
        d.deserialize("topic", valueHeaders("s3"), data);

        // Retrieve all delegates from the cache to inspect closedCount
        // s2 should have been evicted (closedCount == 1); s1 and s3 still live
        TrackingDeserializer s1Del = (TrackingDeserializer) d.cache.get("s1");
        TrackingDeserializer s2Del = null; // evicted — not in cache
        TrackingDeserializer s3Del = (TrackingDeserializer) d.cache.get("s3");

        // Cache size must not exceed max
        assertEquals(2, d.cache.size(), "Cache should be bounded at max-cache-size=2");
        assertNotNull(s1Del, "s1 should still be in cache (it was MRU)");
        assertNotNull(s3Del, "s3 should be in cache");
        assertNull(d.cache.get("s2"), "s2 should have been evicted (it was LRU)");

        d.close();
    }

    @Test
    void closeSafety_closesAllCachedDelegates() {
        Map<String, String> classMap = trackingClassMap("a", "b");
        BoundedDelegatingDeserializer<String> d = configure(10, classMap);
        byte[] data = "x".getBytes(StandardCharsets.UTF_8);

        d.deserialize("topic", valueHeaders("a"), data);
        d.deserialize("topic", valueHeaders("b"), data);

        TrackingDeserializer aDel = (TrackingDeserializer) d.cache.get("a");
        TrackingDeserializer bDel = (TrackingDeserializer) d.cache.get("b");

        d.close();

        assertEquals(1, aDel.closedCount.get(), "Delegate for 'a' should be closed once");
        assertEquals(1, bDel.closedCount.get(), "Delegate for 'b' should be closed once");
    }

    @Test
    void closeTwice_doesNotThrow() {
        BoundedDelegatingDeserializer<String> d = configure(10, stringClassMap("str"));
        assertDoesNotThrow(d::close);
        assertDoesNotThrow(d::close);
    }

    @Test
    void stringFormatClassMap_parsedCorrectly() {
        BoundedDelegatingDeserializer<String> d = new BoundedDelegatingDeserializer<>();
        Map<String, Object> cfg = new HashMap<>();
        cfg.put(BoundedDelegatingDeserializer.MAX_CACHE_SIZE_CONFIG, 10);
        cfg.put(BoundedDelegatingDeserializer.SELECTOR_CLASS_MAP_CONFIG,
            "str:org.apache.kafka.common.serialization.StringDeserializer");
        d.configure(cfg, false);

        assertEquals("world", d.deserialize("topic", valueHeaders("str"),
            "world".getBytes(StandardCharsets.UTF_8)));
        d.close();
    }

    @Test
    void maxCacheSizeAsString_parsedCorrectly() {
        BoundedDelegatingDeserializer<String> d = new BoundedDelegatingDeserializer<>();
        Map<String, Object> cfg = new HashMap<>();
        cfg.put(BoundedDelegatingDeserializer.MAX_CACHE_SIZE_CONFIG, "5");
        cfg.put(BoundedDelegatingDeserializer.SELECTOR_CLASS_MAP_CONFIG, stringClassMap("str"));
        assertDoesNotThrow(() -> d.configure(cfg, false));
        d.close();
    }

    @Test
    void isKey_usesKeyHeaderName() {
        BoundedDelegatingDeserializer<String> d = new BoundedDelegatingDeserializer<>();
        Map<String, Object> cfg = new HashMap<>();
        cfg.put(BoundedDelegatingDeserializer.MAX_CACHE_SIZE_CONFIG, 10);
        cfg.put(BoundedDelegatingDeserializer.SELECTOR_CLASS_MAP_CONFIG, stringClassMap("str"));
        d.configure(cfg, true); // isKey = true

        byte[] data = "key-value".getBytes(StandardCharsets.UTF_8);

        // Correct header key for isKey=true
        assertEquals("key-value", d.deserialize("topic", keyHeaders("str"), data));

        // Wrong header key (value header, not key header) → missing header → exception
        assertThrows(IllegalArgumentException.class,
            () -> d.deserialize("topic", valueHeaders("str"), data));

        d.close();
    }

    @Test
    void concurrent_noDeadlock() throws InterruptedException {
        BoundedDelegatingDeserializer<String> d = configure(10, stringClassMap("str"));
        byte[] data = "concurrent".getBytes(StandardCharsets.UTF_8);

        int threads = 4;
        int callsPerThread = 50;
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch doneGate = new CountDownLatch(threads);
        AtomicReference<Throwable> failure = new AtomicReference<>();

        for (int i = 0; i < threads; i++) {
            Thread t = new Thread(() -> {
                try {
                    startGate.await();
                    for (int j = 0; j < callsPerThread; j++) {
                        String result = d.deserialize("topic", valueHeaders("str"), data);
                        assertNotNull(result);
                    }
                } catch (Throwable ex) {
                    failure.compareAndSet(null, ex);
                } finally {
                    doneGate.countDown();
                }
            });
            t.setDaemon(true);
            t.start();
        }

        startGate.countDown();
        doneGate.await();
        d.close();

        assertNull(failure.get(), () -> "Concurrent test failed: " + failure.get());
    }
}
