package io.havocflow.kafka;

import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.Headers;
import org.apache.kafka.common.serialization.Deserializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A drop-in replacement for Spring Kafka's {@code DelegatingDeserializer} that prevents
 * the heap-DoS vulnerability where a malicious producer can grow the delegate cache without
 * bound by sending records with unique random {@code spring.kafka.serialization.selector}
 * header values.
 *
 * <p>Two layered defences:
 * <ol>
 *   <li><b>Bounded LRU cache</b> — configurable via {@link #MAX_CACHE_SIZE_CONFIG} (default
 *       {@value #DEFAULT_MAX_CACHE_SIZE}). When the cache is full the least-recently-used
 *       delegate is evicted and closed before the new one is inserted.</li>
 *   <li><b>Allowlist enforcement</b> — only selectors listed in
 *       {@link #SELECTOR_CLASS_MAP_CONFIG} are accepted. Unknown selectors throw
 *       {@link IllegalArgumentException} immediately, preventing arbitrary class loading.</li>
 * </ol>
 *
 * <h3>Configuration</h3>
 * <pre>
 * Map&lt;String, Object&gt; props = new HashMap&lt;&gt;();
 * // allowlist: selector -&gt; fully-qualified deserializer class name
 * Map&lt;String, String&gt; classMap = new HashMap&lt;&gt;();
 * classMap.put("json",  "com.example.JsonDeserializer");
 * classMap.put("avro",  "com.example.AvroDeserializer");
 * props.put(BoundedDelegatingDeserializer.SELECTOR_CLASS_MAP_CONFIG, classMap);
 * props.put(BoundedDelegatingDeserializer.MAX_CACHE_SIZE_CONFIG, 50);
 * </pre>
 *
 * <p>The {@link #SELECTOR_CLASS_MAP_CONFIG} may also be supplied as a {@code String} of
 * comma-separated {@code selector:className} pairs, which is convenient in property files:
 * <pre>
 * spring.kafka.consumer.properties.havocflow.bdd.selector-class-map=\
 *     json:com.example.JsonDeserializer,avro:com.example.AvroDeserializer
 * </pre>
 *
 * <p>Thread-safe: all cache mutations are synchronized on a private lock object.
 * Deserialization itself runs outside the lock.
 */
public class BoundedDelegatingDeserializer<T> implements Deserializer<T> {

    /** Kafka record header key used by Spring's {@code DelegatingDeserializer} for values. */
    public static final String SELECTOR_HEADER_KEY = "spring.kafka.serialization.selector";

    /** Kafka record header key used by Spring's {@code DelegatingDeserializer} for keys. */
    public static final String KEY_SELECTOR_HEADER_KEY = "spring.kafka.key.serialization.selector";

    /** Consumer property: maximum number of cached delegate deserializer instances. */
    public static final String MAX_CACHE_SIZE_CONFIG = "havocflow.bdd.max-cache-size";

    /**
     * Consumer property: mapping from selector string to fully-qualified deserializer class name.
     * Accepts either a {@code Map<String,String>} or a comma-separated {@code String} of
     * {@code selector:className} pairs.
     */
    public static final String SELECTOR_CLASS_MAP_CONFIG = "havocflow.bdd.selector-class-map";

    /** Default value for {@link #MAX_CACHE_SIZE_CONFIG}. */
    public static final int DEFAULT_MAX_CACHE_SIZE = 100;

    private static final Logger log = LoggerFactory.getLogger(BoundedDelegatingDeserializer.class);

    private final Object lock = new Object();

    private int maxCacheSize = DEFAULT_MAX_CACHE_SIZE;
    private boolean isKey;
    private Map<String, String> allowedSelectors = Collections.emptyMap();
    private Map<String, ?> configs;

    // Access to this field MUST be synchronized on lock. Package-private for testing.
    LinkedHashMap<String, Deserializer<T>> cache;

    @Override
    @SuppressWarnings("unchecked")
    public void configure(Map<String, ?> configs, boolean isKey) {
        this.configs = new HashMap<>(configs);
        this.isKey = isKey;

        Object maxSizeObj = configs.get(MAX_CACHE_SIZE_CONFIG);
        if (maxSizeObj instanceof Integer) {
            maxCacheSize = (Integer) maxSizeObj;
        } else if (maxSizeObj instanceof String) {
            maxCacheSize = Integer.parseInt((String) maxSizeObj);
        }
        if (maxCacheSize < 1) {
            throw new IllegalArgumentException(
                MAX_CACHE_SIZE_CONFIG + " must be >= 1, got: " + maxCacheSize);
        }

        Object classMapObj = configs.get(SELECTOR_CLASS_MAP_CONFIG);
        if (classMapObj instanceof Map) {
            allowedSelectors = new HashMap<>((Map<String, String>) classMapObj);
        } else if (classMapObj instanceof String) {
            allowedSelectors = parseClassMapString((String) classMapObj);
        }

        final int finalMax = maxCacheSize;
        synchronized (lock) {
            cache = new LinkedHashMap<String, Deserializer<T>>(finalMax + 1, 1.0f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Deserializer<T>> eldest) {
                    if (size() > finalMax) {
                        log.warn("[HavocFlow] BoundedDelegatingDeserializer evicting LRU selector '{}' (max-cache-size={})",
                            eldest.getKey(), finalMax);
                        try {
                            eldest.getValue().close();
                        } catch (Exception e) {
                            log.warn("[HavocFlow] BoundedDelegatingDeserializer: error closing evicted delegate for selector '{}'",
                                eldest.getKey(), e);
                        }
                        return true;
                    }
                    return false;
                }
            };
        }
    }

    @Override
    public T deserialize(String topic, byte[] data) {
        throw new UnsupportedOperationException(
            "BoundedDelegatingDeserializer requires record headers. " +
            "Use deserialize(String, Headers, byte[]) instead.");
    }

    @Override
    public T deserialize(String topic, Headers headers, byte[] data) {
        if (data == null) {
            return null;
        }
        String headerKey = isKey ? KEY_SELECTOR_HEADER_KEY : SELECTOR_HEADER_KEY;
        Header header = headers.lastHeader(headerKey);
        if (header == null) {
            throw new IllegalArgumentException(
                "[HavocFlow] BoundedDelegatingDeserializer: no '" + headerKey +
                "' header present in record from topic '" + topic + "'");
        }
        String selector = new String(header.value(), StandardCharsets.UTF_8);
        Deserializer<T> delegate;
        synchronized (lock) {
            delegate = getOrCreate(selector, topic);
        }
        return delegate.deserialize(topic, headers, data);
    }

    @Override
    public void close() {
        synchronized (lock) {
            for (Deserializer<T> d : cache.values()) {
                try {
                    d.close();
                } catch (Exception e) {
                    log.warn("[HavocFlow] BoundedDelegatingDeserializer: error closing delegate on shutdown", e);
                }
            }
            cache.clear();
        }
    }

    // Called under lock.
    @SuppressWarnings("unchecked")
    private Deserializer<T> getOrCreate(String selector, String topic) {
        if (!allowedSelectors.containsKey(selector)) {
            throw new IllegalArgumentException(
                "[HavocFlow] BoundedDelegatingDeserializer: selector '" + selector +
                "' is not in the allowlist for topic '" + topic + "'. " +
                "Configure " + SELECTOR_CLASS_MAP_CONFIG + " to declare valid selectors.");
        }
        Deserializer<T> d = cache.get(selector);
        if (d != null) {
            return d;
        }
        String className = allowedSelectors.get(selector);
        try {
            d = (Deserializer<T>) Class.forName(className).getDeclaredConstructor().newInstance();
            d.configure(configs, isKey);
            cache.put(selector, d);
            return d;
        } catch (Exception e) {
            throw new IllegalArgumentException(
                "[HavocFlow] BoundedDelegatingDeserializer: failed to instantiate '" +
                className + "' for selector '" + selector + "'", e);
        }
    }

    private static Map<String, String> parseClassMapString(String raw) {
        Map<String, String> result = new HashMap<>();
        for (String pair : raw.split(",")) {
            int colon = pair.indexOf(':');
            if (colon > 0) {
                result.put(pair.substring(0, colon).trim(), pair.substring(colon + 1).trim());
            }
        }
        return result;
    }
}
