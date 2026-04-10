package io.havocflow.config;

import io.havocflow.aspect.ChaosAspect;
import io.havocflow.engine.ChaosEngine;
import io.havocflow.metrics.ChaosMetricsRecorder;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.EnableAspectJAutoProxy;

import java.util.Optional;

/**
 * Spring Boot auto-configuration for HavocFlow.
 *
 * <p>Activated ONLY when {@code chaos.enabled=true} is present in
 * application properties. The {@code matchIfMissing = false} ensures
 * this configuration is completely absent when the property is omitted —
 * making production safety the default, not an opt-out.
 *
 * <p>Register via:
 * {@code META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports}
 */
@Slf4j
@AutoConfiguration
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

    @Bean
    @ConditionalOnMissingBean
    public ChaosEngine chaosEngine(ChaosProperties properties) {
        log.info("[HavocFlow] ChaosEngine initialized — gremlins are ready.");
        return new ChaosEngine(properties);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnClass(MeterRegistry.class)
    public ChaosMetricsRecorder chaosMetricsRecorder(MeterRegistry meterRegistry) {
        log.info("[HavocFlow] Micrometer detected — chaos metrics will be recorded.");
        return new ChaosMetricsRecorder(meterRegistry);
    }

    @Bean
    @ConditionalOnMissingBean
    public ChaosAspect chaosAspect(ChaosEngine engine,
                                    ChaosProperties properties,
                                    Optional<ChaosMetricsRecorder> metricsRecorder) {
        return new ChaosAspect(engine, properties, metricsRecorder);
    }
}
