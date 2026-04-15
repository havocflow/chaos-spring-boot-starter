package io.havocflow;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import io.havocflow.autoconfigure.ChaosProperties;
import io.havocflow.core.ChaosDecision;
import io.havocflow.core.FailureMode;
import io.havocflow.notify.ChaosWebhookNotifier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.Charset;
import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link ChaosWebhookNotifier}.
 */
class ChaosWebhookNotifierTest {

    private HttpServer server;
    private int serverPort;
    private final AtomicReference<String> receivedBody = new AtomicReference<>();
    private final AtomicReference<String> receivedContentType = new AtomicReference<>();
    private CountDownLatch requestLatch;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        serverPort = server.getAddress().getPort();
        server.createContext("/webhook", new HttpHandler() {
            @Override
            public void handle(HttpExchange exchange) throws IOException {
                receivedContentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
                InputStream is = exchange.getRequestBody();
                byte[] bytes = is.readAllBytes();
                receivedBody.set(new String(bytes, Charset.forName("UTF-8")));
                exchange.sendResponseHeaders(200, -1);
                exchange.close();
                requestLatch.countDown();
            }
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private ChaosProperties propertiesWithUrl() {
        ChaosProperties props = new ChaosProperties();
        props.setEnabled(true);
        props.getNotifications().setEnabled(true);
        props.getNotifications().setWebhookUrl("http://localhost:" + serverPort + "/webhook");
        return props;
    }

    @Test
    @DisplayName("Posts JSON payload to webhook when gremlin fires")
    void postsJsonPayload() throws InterruptedException {
        requestLatch = new CountDownLatch(1);
        ChaosProperties props = propertiesWithUrl();
        ChaosWebhookNotifier notifier = new ChaosWebhookNotifier(props);

        ChaosDecision decision = ChaosDecision.builder()
            .mode(FailureMode.EXCEPTION)
            .latencyMillis(0L)
            .exceptionType(RuntimeException.class)
            .scenarioName("db-timeout")
            .build();

        notifier.notifyAsync("com.example.OrderService.placeOrder", decision);

        boolean received = requestLatch.await(5, TimeUnit.SECONDS);
        notifier.destroy();

        assertThat(received).isTrue();
        assertThat(receivedContentType.get()).startsWith("application/json");
        String body = receivedBody.get();
        assertThat(body).contains("\"source\":\"havocflow\"");
        assertThat(body).contains("\"method\":\"com.example.OrderService.placeOrder\"");
        assertThat(body).contains("\"mode\":\"EXCEPTION\"");
        assertThat(body).contains("\"scenario\":\"db-timeout\"");
        assertThat(body).contains("\"latencyMs\":0");
    }

    @Test
    @DisplayName("Mode filter: skips notification when mode not in filter list")
    void modeFilter_skipsWhenNotInList() throws InterruptedException {
        requestLatch = new CountDownLatch(1);
        ChaosProperties props = propertiesWithUrl();
        props.getNotifications().setModes(Arrays.asList("EXCEPTION"));
        ChaosWebhookNotifier notifier = new ChaosWebhookNotifier(props);

        ChaosDecision decision = ChaosDecision.builder()
            .mode(FailureMode.LATENCY)  // not in modes filter
            .latencyMillis(100L)
            .scenarioName("inline")
            .build();

        notifier.notifyAsync("com.example.Service.method", decision);

        // Wait briefly — request must NOT arrive
        boolean received = requestLatch.await(300, TimeUnit.MILLISECONDS);
        notifier.destroy();

        assertThat(received).isFalse();
    }

    @Test
    @DisplayName("Mode filter: sends notification when mode is in filter list")
    void modeFilter_sendsWhenInList() throws InterruptedException {
        requestLatch = new CountDownLatch(1);
        ChaosProperties props = propertiesWithUrl();
        props.getNotifications().setModes(Arrays.asList("EXCEPTION", "LATENCY_AND_EXCEPTION"));
        ChaosWebhookNotifier notifier = new ChaosWebhookNotifier(props);

        ChaosDecision decision = ChaosDecision.builder()
            .mode(FailureMode.EXCEPTION)
            .latencyMillis(0L)
            .scenarioName("inline")
            .build();

        notifier.notifyAsync("com.example.Service.method", decision);

        boolean received = requestLatch.await(5, TimeUnit.SECONDS);
        notifier.destroy();

        assertThat(received).isTrue();
    }

    @Test
    @DisplayName("Empty modes list sends notification for all modes")
    void emptyModesList_notifiesAllModes() throws InterruptedException {
        requestLatch = new CountDownLatch(1);
        ChaosProperties props = propertiesWithUrl();
        props.getNotifications().setModes(Collections.<String>emptyList());
        ChaosWebhookNotifier notifier = new ChaosWebhookNotifier(props);

        ChaosDecision decision = ChaosDecision.builder()
            .mode(FailureMode.LATENCY)
            .latencyMillis(200L)
            .scenarioName("inline")
            .build();

        notifier.notifyAsync("com.example.Service.method", decision);

        boolean received = requestLatch.await(5, TimeUnit.SECONDS);
        notifier.destroy();

        assertThat(received).isTrue();
    }

    @Test
    @DisplayName("Constructor throws when webhook-url is blank and enabled=true")
    void blankUrl_throwsOnConstruction() {
        ChaosProperties props = new ChaosProperties();
        props.getNotifications().setEnabled(true);
        props.getNotifications().setWebhookUrl("  ");

        assertThatThrownBy(() -> new ChaosWebhookNotifier(props))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("webhook-url");
    }

    @Test
    @DisplayName("notifyAsync returns immediately (non-blocking)")
    void notifyAsync_returnsImmediately() {
        requestLatch = new CountDownLatch(1);
        ChaosProperties props = propertiesWithUrl();
        ChaosWebhookNotifier notifier = new ChaosWebhookNotifier(props);

        ChaosDecision decision = ChaosDecision.builder()
            .mode(FailureMode.EXCEPTION)
            .latencyMillis(0L)
            .scenarioName("inline")
            .build();

        long start = System.currentTimeMillis();
        notifier.notifyAsync("com.example.Service.method", decision);
        long elapsed = System.currentTimeMillis() - start;

        notifier.destroy();

        // Should return well under 1 second (actual POST happens async)
        assertThat(elapsed).isLessThan(1000L);
    }
}
