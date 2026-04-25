package io.havocflow;

import io.havocflow.autoconfigure.ChaosProperties;
import io.havocflow.web.ChaosWebFilter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link ChaosWebFilter}.
 * Mirrors {@code ChaosGatewayFilterTest} — same assertions, different filter type.
 */
class ChaosWebFilterTest {

    private ChaosProperties properties;
    private ChaosWebFilter filter;

    @BeforeEach
    void setUp() {
        properties = new ChaosProperties();
        filter = new ChaosWebFilter(properties);
    }

    private WebFilterChain passThroughChain() {
        return exchange -> Mono.empty();
    }

    @Test
    @DisplayName("When http-fault.enabled=false, passes through without fault")
    void disabled_passesThrough() {
        properties.getHttpFault().setEnabled(false);

        MockServerWebExchange exchange = MockServerWebExchange.from(
            MockServerHttpRequest.get("/api/orders").build());

        StepVerifier.create(filter.filter(exchange, passThroughChain()))
            .verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isNull();
    }

    @Test
    @DisplayName("failureRate=1.0 always returns configured HTTP status")
    void failureRateOne_returnsErrorStatus() {
        properties.getHttpFault().setEnabled(true);
        properties.getHttpFault().setFailureRate(1.0);
        properties.getHttpFault().setHttpStatus(503);

        MockServerWebExchange exchange = MockServerWebExchange.from(
            MockServerHttpRequest.get("/api/orders").build());

        StepVerifier.create(filter.filter(exchange, passThroughChain()))
            .verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }

    @Test
    @DisplayName("failureRate=0.0 never injects a fault")
    void failureRateZero_neverFaults() {
        properties.getHttpFault().setEnabled(true);
        properties.getHttpFault().setFailureRate(0.0);

        MockServerWebExchange exchange = MockServerWebExchange.from(
            MockServerHttpRequest.get("/api/orders").build());

        StepVerifier.create(filter.filter(exchange, passThroughChain()))
            .verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isNull();
    }

    @Test
    @DisplayName("Path pattern miss: passes through without fault")
    void pathPatternMiss_passesThrough() {
        properties.getHttpFault().setEnabled(true);
        properties.getHttpFault().setFailureRate(1.0);
        properties.getHttpFault().setPathPatterns(Arrays.asList("/api/**"));

        // /health is not matched by /api/**
        MockServerWebExchange exchange = MockServerWebExchange.from(
            MockServerHttpRequest.get("/health").build());

        StepVerifier.create(filter.filter(exchange, passThroughChain()))
            .verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isNull();
    }

    @Test
    @DisplayName("Path pattern match: injects fault")
    void pathPatternMatch_injectsFault() {
        properties.getHttpFault().setEnabled(true);
        properties.getHttpFault().setFailureRate(1.0);
        properties.getHttpFault().setHttpStatus(500);
        properties.getHttpFault().setPathPatterns(Arrays.asList("/api/**"));

        MockServerWebExchange exchange = MockServerWebExchange.from(
            MockServerHttpRequest.get("/api/orders/123").build());

        StepVerifier.create(filter.filter(exchange, passThroughChain()))
            .verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    }

    @Test
    @DisplayName("Empty path patterns match all paths")
    void emptyPatterns_matchAll() {
        properties.getHttpFault().setEnabled(true);
        properties.getHttpFault().setFailureRate(1.0);
        properties.getHttpFault().setHttpStatus(503);
        // no path patterns configured

        MockServerWebExchange exchange = MockServerWebExchange.from(
            MockServerHttpRequest.get("/health").build());

        StepVerifier.create(filter.filter(exchange, passThroughChain()))
            .verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }

    @Test
    @DisplayName("Latency injection delays the Mono using virtual time")
    void latencyInjection_delaysMono() {
        properties.getHttpFault().setEnabled(true);
        properties.getHttpFault().setFailureRate(0.0); // no fault, only latency
        properties.getHttpFault().setLatency("200ms");
        properties.setMaxLatencyMillis(1000L);

        MockServerWebExchange exchange = MockServerWebExchange.from(
            MockServerHttpRequest.get("/api/orders").build());

        StepVerifier.withVirtualTime(() -> filter.filter(exchange, passThroughChain()))
            .expectSubscription()
            .expectNoEvent(Duration.ofMillis(199))
            .thenAwait(Duration.ofMillis(1))
            .verifyComplete();
    }

    @Test
    @DisplayName("Latency is capped by max-latency-millis")
    void latency_cappedByMaxLatency() {
        properties.getHttpFault().setEnabled(true);
        properties.getHttpFault().setFailureRate(0.0);
        properties.getHttpFault().setLatency("5000ms");
        properties.setMaxLatencyMillis(100L); // cap at 100ms

        MockServerWebExchange exchange = MockServerWebExchange.from(
            MockServerHttpRequest.get("/api/orders").build());

        StepVerifier.withVirtualTime(() -> filter.filter(exchange, passThroughChain()))
            .expectSubscription()
            .thenAwait(Duration.ofMillis(100))
            .verifyComplete();
    }

    @Test
    @DisplayName("getOrder returns LOWEST_PRECEDENCE - 10")
    void getOrder_returnsExpectedValue() {
        assertThat(filter.getOrder()).isEqualTo(org.springframework.core.Ordered.LOWEST_PRECEDENCE - 10);
    }
}
