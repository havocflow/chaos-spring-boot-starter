package io.havocflow.autoconfigure;

import io.havocflow.actuator.ChaosEndpoint;
import io.havocflow.aop.ChaosAspect;
import io.havocflow.audit.ChaosAuditLogger;
import io.havocflow.core.ChaosEngine;
import io.havocflow.event.ChaosEventStore;
import io.havocflow.gateway.ChaosGatewayFilter;
import io.havocflow.gremlins.ConnectionPoolExhaustionStrategy;
import io.havocflow.gremlins.CpuStressStrategy;
import io.havocflow.gremlins.MemoryPressureStrategy;
import io.havocflow.kafka.ChaosKafkaAspect;
import io.havocflow.metrics.ChaosMetricsRecorder;
import io.havocflow.notify.ChaosWebhookNotifier;
import io.havocflow.otel.ChaosOtelSpanRecorder;
import io.havocflow.schedule.ChaosScheduler;
import io.havocflow.spi.ChaosStrategy;
import io.havocflow.test.ChaosContainerSupport;
import io.havocflow.web.ChaosHttpFaultFilter;
import io.havocflow.web.ChaosWebFilter;
import org.springframework.scheduling.TaskScheduler;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.core.Ordered;

import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * Spring Boot auto-configuration for HavocFlow.
 *
 * <p>Activated <strong>only</strong> when {@code chaos.enabled=true} is present in
 * application properties. The {@code matchIfMissing = false} ensures this configuration
 * is completely absent when the property is omitted — making production safety the
 * default, not an opt-out.
 *
 * <p>Compatible with Spring Boot 2.7+ and Spring Boot 3.x.
 * Registered via:
 * <ul>
 *   <li>{@code META-INF/spring.factories} (Spring Boot 2.x)</li>
 *   <li>{@code META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports}
 *       (Spring Boot 3.x)</li>
 * </ul>
 */
@Configuration
@ConditionalOnProperty(
        prefix = "chaos",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = false   // CRITICAL: never activates by accident
)
@ConditionalOnClass(name = "org.aspectj.lang.annotation.Aspect")
@EnableConfigurationProperties(ChaosProperties.class)
@EnableAspectJAutoProxy
public class ChaosAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(ChaosAutoConfiguration.class);

    /**
     * Registers the core {@link ChaosEngine} bean.
     *
     * @param properties bound HavocFlow configuration
     * @return the engine instance
     */
    @Bean
    @ConditionalOnMissingBean
    public ChaosEngine chaosEngine(ChaosProperties properties) {
        logStartupBanner(properties);
        return new ChaosEngine(properties);
    }

    private void logStartupBanner(ChaosProperties props) {
        log.info("[HavocFlow] ============= HavocFlow Chaos Engineering Starter =============");
        log.info("[HavocFlow] Status        : ACTIVE (chaos.enabled=true)");
        log.info("[HavocFlow] Dry-Run       : {}", props.isDryRun());
        log.info("[HavocFlow] Default Rate  : {}", props.getDefaultFailureRate());
        log.info("[HavocFlow] Max Latency   : {}ms", props.getMaxLatencyMillis());
        log.info("[HavocFlow] Log Gremlins  : {}", props.isLogGremlins());
        log.info("[HavocFlow] Forbidden Profiles: {}", props.getForbiddenProfiles());
        if (props.getScenarios().isEmpty()) {
            log.info("[HavocFlow] Scenarios     : (none configured)");
        } else {
            props.getScenarios().forEach((name, s) ->
                log.info("[HavocFlow] Scenario      : name={} latency={} rate={} exception={}",
                    name, s.getLatency(), s.getFailureRate(), s.getException()));
        }
        log.warn("[HavocFlow] WARNING: Chaos injection is ACTIVE. Do NOT deploy in production.");
        log.info("[HavocFlow] =================================================================");
    }

    /**
     * Registers a {@link ChaosMetricsRecorder} when Micrometer is on the classpath.
     *
     * @param meterRegistry the Micrometer registry provided by the host application
     * @return the recorder instance
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnClass(MeterRegistry.class)
    @ConditionalOnBean(MeterRegistry.class)
    public ChaosMetricsRecorder chaosMetricsRecorder(MeterRegistry meterRegistry) {
        log.info("[HavocFlow] Micrometer detected — chaos metrics will be recorded.");
        return new ChaosMetricsRecorder(meterRegistry);
    }

    /**
     * Registers the in-memory event store for chaos injection history.
     */
    @Bean
    @ConditionalOnMissingBean
    public ChaosEventStore chaosEventStore(ChaosProperties properties) {
        log.info("[HavocFlow] ChaosEventStore initialized with capacity={}",
            properties.getEventStoreCapacity());
        return new ChaosEventStore(properties.getEventStoreCapacity());
    }

    /**
     * Registers the {@code /actuator/chaos} endpoint when Spring Boot Actuator is present.
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnClass(name = "org.springframework.boot.actuate.endpoint.annotation.Endpoint")
    public ChaosEndpoint chaosEndpoint(ChaosProperties properties, ChaosEventStore eventStore) {
        log.info("[HavocFlow] Actuator endpoint registered at /actuator/chaos (spring-boot-actuator detected)");
        return new ChaosEndpoint(properties, eventStore);
    }

    /**
     * Registers the HTTP fault injection filter for Spring Boot 2.x (javax.servlet).
     * Only active when {@code javax.servlet.FilterChain} is on the classpath.
     */
    @Bean
    @ConditionalOnMissingBean(name = "chaosHttpFaultFilter")
    @ConditionalOnWebApplication
    @ConditionalOnClass(name = "javax.servlet.FilterChain")
    public FilterRegistrationBean<ChaosHttpFaultFilter> chaosHttpFaultFilter(ChaosProperties properties) {
        ChaosHttpFaultFilter filter = new ChaosHttpFaultFilter(properties);
        FilterRegistrationBean<ChaosHttpFaultFilter> registration =
            new FilterRegistrationBean<ChaosHttpFaultFilter>(filter);
        registration.setOrder(Ordered.LOWEST_PRECEDENCE - 10);
        registration.addUrlPatterns("/*");
        log.info("[HavocFlow] HTTP fault filter registered (active when chaos.http-fault.enabled=true)");
        return registration;
    }

    /**
     * Registers the AOP aspect that intercepts {@code @InjectChaos}-annotated methods.
     *
     * @param engine          the decision engine
     * @param properties      the HavocFlow configuration
     * @param metricsRecorder optional Micrometer recorder
     * @param eventStore      event store for injection history
     * @param strategies      optional list of {@link ChaosStrategy} plugins
     * @return the aspect instance
     */
    @Bean
    @ConditionalOnMissingBean
    public ChaosAspect chaosAspect(ChaosEngine engine,
                                   ChaosProperties properties,
                                   Optional<ChaosMetricsRecorder> metricsRecorder,
                                   ChaosEventStore eventStore,
                                   Optional<ChaosAuditLogger> auditLogger,
                                   List<ChaosStrategy> strategies,
                                   Optional<ChaosOtelSpanRecorder> otelRecorder,
                                   Optional<ChaosWebhookNotifier> webhookNotifier) {
        return new ChaosAspect(engine, properties, metricsRecorder, eventStore, auditLogger,
            strategies != null ? strategies : Collections.<ChaosStrategy>emptyList(),
            otelRecorder, webhookNotifier);
    }

    // -----------------------------------------------------------------------
    // Ecosystem integration beans (01.03.00)
    // -----------------------------------------------------------------------

    /**
     * Registers {@link ChaosOtelSpanRecorder} when OpenTelemetry API is on the classpath.
     * Zero-config: just add {@code opentelemetry-api} as a dependency.
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnClass(name = "io.opentelemetry.api.trace.Span")
    public ChaosOtelSpanRecorder chaosOtelSpanRecorder() {
        log.info("[HavocFlow] OpenTelemetry API detected — chaos span events will be recorded.");
        return new ChaosOtelSpanRecorder();
    }

    /**
     * Registers {@link ChaosWebhookNotifier} when {@code chaos.notifications.enabled=true}.
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "chaos.notifications", name = "enabled", havingValue = "true")
    public ChaosWebhookNotifier chaosWebhookNotifier(ChaosProperties properties) {
        log.info("[HavocFlow] Webhook notifier registered — url={}",
            properties.getNotifications().getWebhookUrl());
        return new ChaosWebhookNotifier(properties);
    }

    /**
     * Registers {@link ChaosKafkaAspect} when Spring Kafka is on the classpath.
     * Applies chaos to all {@code @KafkaListener} methods when {@code chaos.kafka.enabled=true}.
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnClass(name = "org.springframework.kafka.annotation.KafkaListener")
    public ChaosKafkaAspect chaosKafkaAspect(ChaosProperties properties) {
        log.info("[HavocFlow] Spring Kafka detected — ChaosKafkaAspect registered.");
        return new ChaosKafkaAspect(properties);
    }

    /**
     * Registers {@link ChaosGatewayFilter} when Spring Cloud Gateway is on the classpath.
     * Reuses {@code chaos.http-fault.*} configuration for consistency with
     * {@link ChaosHttpFaultFilter}.
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnClass(name = "org.springframework.cloud.gateway.filter.GlobalFilter")
    public ChaosGatewayFilter chaosGatewayFilter(ChaosProperties properties) {
        log.info("[HavocFlow] Spring Cloud Gateway detected — ChaosGatewayFilter registered.");
        return new ChaosGatewayFilter(properties);
    }

    /**
     * Registers the {@link ChaosScheduler} when {@code chaos.schedule.enabled=true} and
     * a {@link TaskScheduler} bean is available in the context.
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "chaos.schedule", name = "enabled", havingValue = "true")
    @ConditionalOnBean(TaskScheduler.class)
    public ChaosScheduler chaosScheduler(ChaosProperties properties, TaskScheduler taskScheduler) {
        log.info("[HavocFlow] Chaos scheduler enabled — cron='{}' duration='{}'",
                properties.getSchedule().getCron(), properties.getSchedule().getDuration());
        return new ChaosScheduler(properties, taskScheduler);
    }

    /**
     * Registers the {@link ChaosAuditLogger} when {@code chaos.audit-log.enabled=true}.
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "chaos.audit-log", name = "enabled", havingValue = "true")
    public ChaosAuditLogger chaosAuditLogger(ChaosProperties properties) {
        log.info("[HavocFlow] Audit log enabled — path='{}'", properties.getAuditLog().getPath());
        return new ChaosAuditLogger(properties.getAuditLog().getPath());
    }

    // -----------------------------------------------------------------------
    // New features — 01.04.00
    // -----------------------------------------------------------------------

    /**
     * Registers {@link ChaosWebFilter} for Spring WebFlux (reactive) applications.
     *
     * <p>Only activated when:
     * <ul>
     *   <li>{@code org.springframework.web.server.WebFilter} is on the classpath (WebFlux present), AND</li>
     *   <li>Spring Cloud Gateway is <em>absent</em> — when SCG is present,
     *       {@link ChaosGatewayFilter} handles reactive fault injection to prevent double-injection, AND</li>
     *   <li>The application is a reactive web application.</li>
     * </ul>
     * Faults are only injected when {@code chaos.http-fault.enabled=true}.
     */
    @Bean
    @ConditionalOnMissingBean(ChaosWebFilter.class)
    @ConditionalOnClass(name = "org.springframework.web.server.WebFilter")
    @ConditionalOnMissingClass("org.springframework.cloud.gateway.filter.GlobalFilter")
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
    public ChaosWebFilter chaosWebFilter(ChaosProperties properties) {
        log.info("[HavocFlow] WebFlux detected — ChaosWebFilter registered (active when chaos.http-fault.enabled=true)");
        return new ChaosWebFilter(properties);
    }

    /**
     * Registers the {@link ExperimentTemplateLoader} that merges five built-in scenario
     * presets into {@link ChaosProperties#getScenarios()} at startup.
     * User-defined scenarios always win — templates are only added when the key is absent.
     */
    @Bean
    @ConditionalOnMissingBean(ExperimentTemplateLoader.class)
    public ExperimentTemplateLoader experimentTemplateLoader(ChaosProperties properties) {
        log.info("[HavocFlow] ExperimentTemplateLoader registered — built-in experiment templates available");
        return new ExperimentTemplateLoader(properties);
    }

    /**
     * Registers a {@link ChaosContainerSupport} singleton when Testcontainers is on the classpath.
     * Provides lifecycle-aware chaos scenario activation tied to container start/stop.
     */
    @Bean
    @ConditionalOnMissingBean(ChaosContainerSupport.class)
    @ConditionalOnClass(name = "org.testcontainers.containers.GenericContainer")
    public ChaosContainerSupport chaosContainerSupport() {
        log.info("[HavocFlow] Testcontainers detected — ChaosContainerSupport available.");
        return new ChaosContainerSupport();
    }

    // -----------------------------------------------------------------------
    // Built-in gremlin ChaosStrategy beans (Phase 3)
    // -----------------------------------------------------------------------

    /**
     * Registers the CPU stress gremlin strategy.
     */
    @Bean
    @ConditionalOnMissingBean(CpuStressStrategy.class)
    public CpuStressStrategy cpuStressStrategy(ChaosProperties properties) {
        ChaosProperties.GremlinProperties g = properties.getGremlins();
        return new CpuStressStrategy(g.getCpuThreads(), g.getCpuDurationMillis());
    }

    /**
     * Registers the memory pressure gremlin strategy.
     */
    @Bean
    @ConditionalOnMissingBean(MemoryPressureStrategy.class)
    public MemoryPressureStrategy memoryPressureStrategy(ChaosProperties properties) {
        return new MemoryPressureStrategy(properties.getGremlins().getMemoryAllocationMb());
    }

    /**
     * Registers the connection pool exhaustion gremlin strategy.
     * Only active when a {@link javax.sql.DataSource} bean is present.
     */
    @Bean
    @ConditionalOnMissingBean(ConnectionPoolExhaustionStrategy.class)
    @ConditionalOnBean(name = "dataSource")
    @ConditionalOnClass(name = "javax.sql.DataSource")
    public ConnectionPoolExhaustionStrategy connectionPoolExhaustionStrategy(
            ChaosProperties properties,
            javax.sql.DataSource dataSource) {
        ChaosProperties.GremlinProperties g = properties.getGremlins();
        return new ConnectionPoolExhaustionStrategy(
            dataSource,
            g.getConnectionCount(),
            g.getConnectionHoldMillis(),
            properties.getMaxLatencyMillis()
        );
    }
}
