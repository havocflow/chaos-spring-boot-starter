package io.havocflow.gateway;

import io.havocflow.autoconfigure.ChaosProperties;
import io.havocflow.core.LatencyParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Spring Cloud Gateway {@link GlobalFilter} that injects HTTP-layer faults into
 * matching requests using non-blocking reactive operators.
 *
 * <p>This filter is the reactive equivalent of
 * {@link io.havocflow.web.ChaosHttpFaultFilter} and intentionally reuses the same
 * {@code chaos.http-fault.*} configuration namespace. Operators running both a
 * servlet container and a Gateway instance can configure fault injection once and
 * have it apply at both layers.
 *
 * <p>Activated when {@code spring-cloud-starter-gateway} is on the classpath.
 * Faults are only injected when {@code chaos.http-fault.enabled=true}.
 *
 * <p>Latency injection uses {@link Mono#delay(Duration)} (non-blocking) instead of
 * {@code Thread.sleep()} to avoid blocking Netty's event-loop threads.
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
public class ChaosGatewayFilter implements GlobalFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(ChaosGatewayFilter.class);

    private final ChaosProperties properties;
    private final AntPathMatcher pathMatcher = new AntPathMatcher();

    /**
     * Constructs a {@code ChaosGatewayFilter}.
     *
     * @param properties the HavocFlow configuration; must not be {@code null}
     */
    public ChaosGatewayFilter(ChaosProperties properties) {
        this.properties = properties;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ChaosProperties.HttpFaultProperties httpFault = properties.getHttpFault();

        if (!httpFault.isEnabled()) {
            return chain.filter(exchange);
        }

        String path = exchange.getRequest().getURI().getPath();
        if (!matchesPathPatterns(path, httpFault.getPathPatterns())) {
            return chain.filter(exchange);
        }

        // Non-blocking latency via Mono.delay() — never blocks Netty threads
        Mono<Void> latencyMono = buildLatencyMono(httpFault, path);

        // Probabilistic fault injection
        if (ThreadLocalRandom.current().nextDouble() < httpFault.getFailureRate()) {
            final int statusCode = httpFault.getHttpStatus();
            log.debug("[HavocFlow][Gateway] Injecting HTTP {} on {}", statusCode, path);
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
        log.debug("[HavocFlow][Gateway] Injecting {}ms latency on {}", latencyMs, path);
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
