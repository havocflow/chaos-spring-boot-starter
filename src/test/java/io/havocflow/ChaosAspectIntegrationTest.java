package io.havocflow;

import io.havocflow.annotation.InjectChaos;
import io.havocflow.autoconfigure.ChaosAutoConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.stereotype.Service;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration test that boots a minimal Spring context to verify
 * the AOP aspect fires correctly end-to-end.
 */
@SpringBootTest(classes = {ChaosAspectIntegrationTest.TestConfig.class})
@Import(ChaosAutoConfiguration.class)
@TestPropertySource(properties = {"chaos.enabled=true"})
class ChaosAspectIntegrationTest {

    @Autowired
    private TestOrderService orderService;

    @Test
    @DisplayName("Latency-only annotation delays the method call")
    void latencyAnnotation_delaysCall() {
        long start = System.currentTimeMillis();
        orderService.slowFind();
        long elapsed = System.currentTimeMillis() - start;

        assertThat(elapsed).isGreaterThanOrEqualTo(200L);
    }

    @Test
    @DisplayName("failureRate=1.0 always throws the configured exception")
    void alwaysFail_throwsException() {
        assertThatThrownBy(() -> orderService.alwaysFail())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("HavocFlow");
    }

    @Test
    @DisplayName("failureRate=0.0 never throws")
    void neverFail_proceeds() {
        assertThatCode(() -> orderService.neverFail()).doesNotThrowAnyException();
    }

    // ---- Test doubles -------------------------------------------------------

    @Service
    static class TestOrderService {

        @InjectChaos(latency = "300ms")
        public String slowFind() { return "order-1"; }

        @InjectChaos(failureRate = 1.0, exception = IllegalStateException.class)
        public String alwaysFail() { return "should-not-reach"; }

        @InjectChaos(failureRate = 0.0)
        public String neverFail() { return "ok"; }
    }

    @SpringBootConfiguration
    static class TestConfig {
        @Bean
        public TestOrderService testOrderService() { return new TestOrderService(); }
    }
}
