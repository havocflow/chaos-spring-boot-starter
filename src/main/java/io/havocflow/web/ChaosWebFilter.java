package io.havocflow.web;

import io.havocflow.autoconfigure.ChaosProperties;
import io.havocflow.core.LatencyParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Spring WebFlux {@link WebFilter} that injects HTTP-layer faults into reactive requests.
 *
 * <p>This is the non-blocking equivalent of {@link ChaosHttpFaultFilter} for Spring WebFlux
 * applications. Both filters reuse the same {@code chaos.http-fault.*} configuration namespace,
 * so operators need only one configuration block regardless of the server model.
 *
 * <p>Activated when {@code org.springframework.web.server.WebFilter} is on the classpath
 * (i.e. Spring WebFlux is present) and the application is a reactive web application.
 * Suppressed when Spring Cloud Gateway is present — {@link io.havocflow.gateway.ChaosGatewayFilter}
 * handles reactive fault injection in that case to avoid double-injection.
 *
 * <p>Faults are only injected when {@code chaos.http-fault.enabled=true}.
 *
 * <p>Latency injection uses {@link Mono#delay(Duration)} — never blocks the event loop.
 *
 * <p>Example configuration:
 * <pre>
 * chaos:
 *   http-fault:
 *     enabled: true
 *     failure-rate: 0.1
 *     http-status: 503
 *     latency: "200ms"
 *     path-patterns:
 *       - "/api/**"
 * </pre>
 */
public class ChaosWebFilter implements WebFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(ChaosWebFilter.class);

    private final ChaosProperties properties;
    private final AntPathMatcher pathMatcher = new AntPathMatcher();

    /**
     * Constructs a {@code ChaosWebFilter}.
     *
     * @param properties the HavocFlow configuration; must not be {@code null}
     */
    public ChaosWebFilter(ChaosProperties properties) {
        this.properties = properties;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        ChaosProperties.HttpFaultProperties httpFault = properties.getHttpFault();

        if (!httpFault.isEnabled()) {
            return chain.filter(exchange);
        }

        String path = exchange.getRequest().getURI().getPath();
        if (!matchesPathPatterns(path, httpFault.getPathPatterns())) {
            return chain.filter(exchange);
        }

        // Non-blocking latency via Mono.delay() — never blocks the event-loop thread
        Mono<Void> latencyMono = buildLatencyMono(httpFault, path);

        // Probabilistic fault injection
        if (ThreadLocalRandom.current().nextDouble() < httpFault.getFailureRate()) {
            final int statusCode = httpFault.getHttpStatus();
            log.debug("[HavocFlow][WebFilter] Injecting HTTP {} on {}", statusCode, path);
            exchange.getResponse().setStatusCode(HttpStatus.valueOf(statusCode));
            return latencyMono.then(exchange.getResponse().setComplete());
        }

        return latencyMono.then(chain.filter(exchange));
    }

    private Mono<Void> buildLatencyMono(ChaosProperties.HttpFaultProperties httpFault, String path) {
        String latencyExpr = httpFault.getLatency();
        if (latencyExpr == null || latencyExpr.trim().isEmpty()) {
            return Mono.empty();
        }
        long rawMs = LatencyParser.parseMillis(latencyExpr);
        long latencyMs = Math.min(rawMs, properties.getMaxLatencyMillis());
        if (latencyMs <= 0) {
            return Mono.empty();
        }
        log.debug("[HavocFlow][WebFilter] Injecting {}ms latency on {}", latencyMs, path);
        return Mono.delay(Duration.ofMillis(latencyMs)).then();
    }

    /**
     * Returns {@code true} if the request path matches at least one of the configured
     * Ant-style path patterns. When no patterns are configured, all paths match.
     */
    private boolean matchesPathPatterns(String path, List<String> patterns) {
        if (patterns == null || patterns.isEmpty()) {
            return true;
        }
        for (String pattern : patterns) {
            if (pathMatcher.match(pattern, path)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Runs near the end of the filter chain (same order as the servlet filter)
     * so routing and authentication filters execute first.
     */
    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE - 10;
    }
}
