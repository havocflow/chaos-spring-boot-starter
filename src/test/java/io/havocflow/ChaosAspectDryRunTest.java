package io.havocflow;

import io.havocflow.annotation.InjectChaos;
import io.havocflow.config.ChaosAutoConfiguration;
import io.havocflow.config.ChaosProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Service;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest(classes = {
    ChaosAspectDryRunTest.TestConfig.class,
    ChaosAutoConfiguration.class
})
@TestPropertySource(properties = {
    "chaos.enabled=true",
    "chaos.dry-run=true"
})
@DisplayName("ChaosAspect dry-run mode")
class ChaosAspectDryRunTest {

    @Autowired
    private SomeService someService;

    @Test
    @DisplayName("in dry-run mode, failureRate=1.0 does NOT throw")
    void dryRunDoesNotThrow() {
        // Should complete without exception despite 100% failure rate
        assertThatCode(() -> someService.doWork())
            .doesNotThrowAnyException();
    }

    @Service
    static class SomeService {
        @InjectChaos(failureRate = 1.0, latency = "500ms")
        public void doWork() { /* real logic */ }
    }

    @Configuration
    static class TestConfig {
        @Bean SomeService someService()         { return new SomeService(); }
        @Bean ChaosProperties chaosProperties() { return new ChaosProperties(); }
    }
}
