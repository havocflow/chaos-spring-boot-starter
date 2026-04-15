package io.havocflow.notify;

import io.havocflow.autoconfigure.ChaosProperties;
import io.havocflow.core.ChaosDecision;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;

import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.Charset;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

/**
 * Sends an asynchronous JSON webhook notification every time a HavocFlow gremlin fires.
 *
 * <p>Works with any webhook endpoint that accepts an HTTP POST with a JSON body,
 * including Slack Incoming Webhooks, PagerDuty, and custom alerting systems.
 *
 * <p>Notifications are dispatched on a background daemon thread so the caller is
 * never blocked by network latency or failures. A failed POST is logged at WARN
 * level and silently swallowed — it never propagates to the chaos injection path.
 *
 * <p>Example configuration:
 * <pre>
 * chaos:
 *   notifications:
 *     enabled: true
 *     webhook-url: https://hooks.slack.com/services/T.../B.../...
 *     modes:
 *       - EXCEPTION
 *       - LATENCY_AND_EXCEPTION
 * </pre>
 *
 * <p>JSON payload posted to the webhook:
 * <pre>
 * {"source":"havocflow","method":"com.example.OrderService.placeOrder","mode":"EXCEPTION","scenario":"db-timeout","latencyMs":0,"ts":"2026-04-15T10:00:00Z"}
 * </pre>
 */
public class ChaosWebhookNotifier implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(ChaosWebhookNotifier.class);

    private static final int CONNECT_TIMEOUT_MS = 5_000;
    private static final int READ_TIMEOUT_MS    = 5_000;

    private final ChaosProperties properties;
    private final ExecutorService executor;

    /**
     * Constructs a {@code ChaosWebhookNotifier}.
     *
     * @param properties the HavocFlow configuration; must not be {@code null}
     * @throws IllegalArgumentException if {@code chaos.notifications.webhook-url} is blank
     */
    public ChaosWebhookNotifier(ChaosProperties properties) {
        this.properties = properties;
        String url = properties.getNotifications().getWebhookUrl();
        if (url == null || url.trim().isEmpty()) {
            throw new IllegalArgumentException(
                "[HavocFlow] chaos.notifications.enabled=true but chaos.notifications.webhook-url is not set.");
        }
        this.executor = Executors.newSingleThreadExecutor(new ThreadFactory() {
            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "havocflow-webhook");
                t.setDaemon(true);
                return t;
            }
        });
    }

    /**
     * Enqueues an asynchronous webhook notification for the given gremlin event.
     * Returns immediately — the POST happens on a background daemon thread.
     *
     * <p>If a {@code modes} filter is configured and the decision's mode is not in
     * the list, the notification is silently skipped.
     *
     * @param methodName fully-qualified method name
     * @param decision   the resolved chaos decision
     */
    public void notifyAsync(final String methodName, final ChaosDecision decision) {
        List<String> modes = properties.getNotifications().getModes();
        if (modes != null && !modes.isEmpty() && !modes.contains(decision.getMode().name())) {
            return;
        }
        executor.submit(new Runnable() {
            @Override
            public void run() {
                doPost(methodName, decision);
            }
        });
    }

    private void doPost(String methodName, ChaosDecision decision) {
        String webhookUrl = properties.getNotifications().getWebhookUrl();
        String json = buildJson(methodName, decision);
        HttpURLConnection conn = null;
        try {
            URL url = new URL(webhookUrl);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setDoOutput(true);

            byte[] body = json.getBytes(Charset.forName("UTF-8"));
            OutputStream os = conn.getOutputStream();
            os.write(body);
            os.flush();
            os.close();

            int status = conn.getResponseCode();
            if (status < 200 || status >= 300) {
                log.warn("[HavocFlow][webhook] Received HTTP {} from webhook at {}", status, webhookUrl);
            } else {
                log.debug("[HavocFlow][webhook] Notification sent (HTTP {}) for method={}", status, methodName);
            }
        } catch (IOException e) {
            log.warn("[HavocFlow][webhook] Failed to POST to {}: {}", webhookUrl, e.getMessage());
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private String buildJson(String methodName, ChaosDecision decision) {
        return "{\"source\":\"havocflow\""
            + ",\"method\":\"" + escape(methodName) + "\""
            + ",\"mode\":\"" + decision.getMode().name() + "\""
            + ",\"scenario\":\"" + escape(decision.getScenarioName() != null ? decision.getScenarioName() : "") + "\""
            + ",\"latencyMs\":" + decision.getLatencyMillis()
            + ",\"ts\":\"" + Instant.now() + "\""
            + "}";
    }

    /** Minimal JSON string escaping for method names and scenario names. */
    private static String escape(String value) {
        if (value == null) return "";
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    @Override
    public void destroy() {
        executor.shutdownNow();
        log.debug("[HavocFlow][webhook] Webhook notifier executor shut down.");
    }
}
