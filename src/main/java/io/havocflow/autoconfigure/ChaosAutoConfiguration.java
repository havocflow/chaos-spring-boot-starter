package io.havocflow.autoconfigure;

import io.havocflow.aop.ChaosAspect;
import io.havocflow.core.ChaosEngine;
import io.havocflow.metrics.ChaosMetricsRecorder;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.EnableAspectJAutoProxy;

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
        log.info("[HavocFlow] ChaosEngine initialized — gremlins are ready.");
        return new ChaosEngine(properties);
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
     * Registers the AOP aspect that intercepts {@code @InjectChaos}-annotated methods.
     *
     * @param engine          the decision engine
     * @param properties      the HavocFlow configuration
     * @param metricsRecorder optional Micrometer recorder
     * @return the aspect instance
     */
    @Bean
    @ConditionalOnMissingBean
    public ChaosAspect chaosAspect(ChaosEngine engine,
                                   ChaosProperties properties,
                                   Optional<ChaosMetricsRecorder> metricsRecorder) {
        return new ChaosAspect(engine, properties, metricsRecorder);
    }
}
