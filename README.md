# HavocFlow — Chaos Spring Boot Starter

[![CI](https://github.com/havocflow/chaos-spring-boot-starter/actions/workflows/ci.yml/badge.svg)](https://github.com/havocflow/chaos-spring-boot-starter/actions/workflows/ci.yml)
[![Maven Central](https://img.shields.io/maven-central/v/io.havocflow/chaos-spring-boot-starter.svg)](https://central.sonatype.com/artifact/io.havocflow/chaos-spring-boot-starter)
[![License](https://img.shields.io/badge/license-Apache%202.0-blue.svg)](LICENSE)
[![Java 8+](https://img.shields.io/badge/java-8%2B-orange.svg)](https://adoptium.net/)

> Inject controlled chaos into your Spring Boot services with a single annotation.
> No Istio. No Chaos Monkey infrastructure. Just `@InjectChaos`.

## What is it?

HavocFlow is a Spring Boot starter that lets you annotate any service or repository
with `@InjectChaos`. During development or staging, it injects **latency**,
**exceptions**, or both — forcing you to write resilient code from day one.

```java
@Service
@InjectChaos(scenario = "db-timeout")
public class OrderRepository {
    public List<Order> findByUser(Long userId) {
        // HavocFlow randomly delays or throws here in dev/staging
        return jpaRepository.findByUserId(userId);
    }
}
```

**It is OFF by default.** Chaos never activates unless you set `chaos.enabled=true`.

---

## Quick start

### 1. Add the dependency

```xml
<dependency>
    <groupId>io.havocflow</groupId>
    <artifactId>chaos-spring-boot-starter</artifactId>
    <version>1.0.0</version>
    <scope>test</scope>
</dependency>
```

### 2. Enable chaos in dev/staging

```yaml
# application-dev.yml
chaos:
  enabled: true
  dry-run: false
  default-failure-rate: 0.05
  log-gremlins: true

  scenarios:
    db-timeout:
      latency: "3s"
      failure-rate: 0.2
      exception: org.springframework.dao.QueryTimeoutException
    network-partition:
      latency: "100ms-500ms"
      failure-rate: 0.3
      exception: java.net.SocketTimeoutException
    flaky-service:
      failure-rate: 0.15
      exception: org.springframework.web.client.ResourceAccessException
```

### 3. Annotate your services

```java
// Named scenario
@InjectChaos(scenario = "db-timeout")
public Product findById(Long id) { ... }

// Inline — 2 s latency + 10 % failure rate
@InjectChaos(latency = "2s", failureRate = 0.1)
public void sendPayment(PaymentRequest req) { ... }

// Jitter range
@InjectChaos(latency = "100ms-500ms")
public List<Event> pollEvents() { ... }

// Class-level — applies to ALL methods
@Service
@InjectChaos(scenario = "flaky-service")
public class NotificationService { ... }
```

---

## Annotation reference

| Attribute     | Type    | Default            | Description                                       |
|---------------|---------|--------------------|---------------------------------------------------|
| `latency`     | String  | `""` (none)        | Artificial delay. Supports fixed and jitter range |
| `failureRate` | double  | `0.0`              | Probability of throwing an exception (0.0–1.0)    |
| `exception`   | Class   | `RuntimeException` | Exception type to throw on failure                |
| `scenario`    | String  | `""` (none)        | Named preset from `chaos.scenarios.*`             |
| `overridable` | boolean | `true`             | Allow runtime property overrides                  |

### Latency format

| Expression     | Result                           |
|----------------|----------------------------------|
| `"500ms"`      | Fixed 500 milliseconds           |
| `"2s"`         | Fixed 2 seconds                  |
| `"1m"`         | Fixed 1 minute                   |
| `"100ms-2s"`   | Random between 100 ms and 2 000 ms |
| `"1s-5s"`      | Random between 1 s and 5 s       |

---

## Resolution order

```
Named scenario  →  Inline annotation attributes  →  Global default-failure-rate
  (highest)                                               (lowest)
```

---

## Dry-run mode

Set `chaos.dry-run=true` to see what chaos would fire without applying it.
Useful for CI environments or initial rollouts:

```
[HavocFlow][DRY-RUN] Would apply ChaosDecision{mode=LATENCY_AND_EXCEPTION,
  latencyMillis=3000, exceptionType=QueryTimeoutException, scenarioName='db-timeout'}
  to OrderRepository.findById
```

---

## Metrics (Micrometer)

If `micrometer-core` is on the classpath, HavocFlow auto-publishes:

| Metric                      | Type    | Tags                         |
|-----------------------------|---------|------------------------------|
| `chaos.gremlins.fired`      | Counter | `method`, `mode`, `scenario` |
| `chaos.latency.injected.ms` | Counter | `method`                     |

---

## HTTP fault injection

Inject faults at the servlet filter layer — no method annotations required:

```yaml
chaos:
  enabled: true
  http-fault:
    enabled: true
    failure-rate: 0.3
    http-status: 503
    latency: "200ms"
    path-patterns:
      - "/api/payments/*"
      - "/api/orders/*"
```

This injects HTTP 503 responses (with a 200 ms delay) on 30% of matching requests.

---

## Gremlin strategies

Enable resource-exhaustion scenarios for deeper resilience testing:

```yaml
chaos:
  enabled: true
  gremlins:
    cpu-threads: 2
    cpu-duration-millis: 1000
    memory-allocation-mb: 64
    connection-count: 5
    connection-hold-millis: 2000
```

All gremlin strategies are safely bounded — CPU capped at 5 s, memory capped at 25% of heap, connections always released in a `finally` block.

---

## Custom strategy (SPI)

Implement `ChaosStrategy` to add your own fault injection logic:

```java
import io.havocflow.spi.ChaosStrategy;
import io.havocflow.annotation.InjectChaos;
import org.aspectj.lang.ProceedingJoinPoint;

@Component
public class NetworkPartitionStrategy implements ChaosStrategy {

    @Override
    public boolean canHandle(InjectChaos annotation) {
        return "network-partition".equals(annotation.scenario());
    }

    @Override
    public Object apply(ProceedingJoinPoint pjp, InjectChaos annotation) throws Throwable {
        // simulate a network partition
        throw new java.net.ConnectException("Simulated network partition");
    }
}
```

Register it as a Spring bean and HavocFlow will automatically discover it via `ChaosAutoConfiguration`.

---

## Package structure

```
io.havocflow
├── annotation/       @InjectChaos
├── aop/              ChaosAspect (AOP interceptor)
├── autoconfigure/    ChaosAutoConfiguration, ChaosProperties, ChaosProductionGuard
├── actuator/         ChaosEndpoint (/actuator/chaos)
├── core/             ChaosEngine, ChaosDecision, FailureMode, LatencyParser
├── event/            ChaosEventStore, ChaosEvent (injection history ring buffer)
├── exception/        ChaosGremlinException
├── gremlins/         CpuStressStrategy, MemoryPressureStrategy, ConnectionPoolExhaustionStrategy
├── metrics/          ChaosMetricsRecorder
├── spi/              ChaosStrategy (extension point)
└── web/              ChaosHttpFaultFilter
```

---

## Pairing with Resilience4j

```java
// Callee: injects chaos
@Service
@InjectChaos(scenario = "db-timeout")
public class PaymentGatewayClient { ... }

// Caller: declares resilience
@Retry(name = "payment", fallbackMethod = "fallbackPayment")
@CircuitBreaker(name = "payment")
public void checkout(Order order) {
    paymentGatewayClient.charge(order.toPaymentRequest());
}
```

---

## Safety guarantees

1. **Off by default** — does nothing unless `chaos.enabled=true`
2. **Profile guard** — tie `chaos.enabled=true` to `application-dev.yml`
3. **Dry-run mode** — logs without acting (`chaos.dry-run=true`)
4. **Compile-time visibility** — every chaotic method is annotated, no hidden gremlins
5. **Test scope** — recommended `<scope>test</scope>` excludes from production artifacts

---

## Build from source

```bash
git clone https://github.com/havocflow/chaos-spring-boot-starter
cd chaos-spring-boot-starter
mvn clean install
```

Requires: **Java 8+**, Maven 3.8+, Spring Boot 2.7+ or 3.x

## Compatibility

| HavocFlow | Java  | Spring Boot |
|-----------|-------|-------------|
| 1.0.x     | 8–21  | 2.7.x, 3.x  |

## Performance & overhead

- **When `chaos.enabled=false` (default):** The entire auto-configuration is never loaded. Zero overhead — no beans, no AOP proxy, nothing.
- **When `chaos.enabled=true`:** Each intercepted method pays the cost of one `Random.nextDouble()` call and one AOP `around` advice dispatch (microsecond range). This is negligible for any I/O-bound method where chaos is useful.
- Metrics recording (when Micrometer is present) adds one counter increment per injection.

---

## Known limitations

- **HTTP filter pattern matching** uses simple `String.matches()` regex, not Spring's `AntPathMatcher`. Use regex syntax (`/api/.*`) rather than Ant syntax (`/api/**`).
- **`overridable` annotation attribute** — when `true` (the default), inline annotation values (`latency`, `failureRate`) override the named scenario values. Set to `false` to make the named scenario authoritative.
- **No distributed chaos coordination** — each JVM instance makes independent decisions. Two instances of the same service may inject at different rates.
- **No reactive (WebFlux) support** — `ChaosHttpFaultFilter` is a `javax.servlet.Filter` and does not work in reactive pipelines. AOP-based injection (`@InjectChaos`) works in reactive code but blocks the calling thread during latency injection.
- **Java 8 bytecode** — the JAR compiles to Java 8 bytecode and runs on any JVM 8+, including Spring Boot 3.x (which requires JVM 17+ at runtime).

---

## Troubleshooting

**Chaos is not firing — nothing happens:**
- Check that `chaos.enabled=true` is set in the active profile's YAML (not `application.yml` — use `application-dev.yml` and activate with `--spring.profiles.active=dev`).
- Check that `spring-boot-starter-aop` is on the classpath. Without AOP, the aspect never runs.
- Set `chaos.log-gremlins=true` and watch the logs for `[HavocFlow]` lines.

**Application fails to start with `IllegalStateException`:**
- `ChaosProductionGuard` detected `chaos.enabled=true` on a forbidden profile (`prod`, `production`, `live`). Either remove `chaos.enabled=true` from that profile, or adjust `chaos.forbidden-profiles`.

**Latency is not as long as configured:**
- `chaos.max-latency-millis` (default: 30 000 ms) hard-caps all latency values. Increase it if you need longer delays.

**Exception type is not thrown — `RuntimeException` fires instead:**
- The configured class may not be on the classpath, or it may be in the blocklist (class names containing `System`, `Runtime`, `ProcessBuilder`, `Shutdown` are rejected). Check the `WARN`-level logs.

**IDE shows no autocomplete for `chaos.*` properties:**
- Ensure `spring-boot-configuration-processor` is in your project's dependencies (it is provided transitively by HavocFlow, but your IDE may need a Maven refresh).

---

## FAQ

**Does HavocFlow work with Kotlin?**
Yes. `@InjectChaos` is a standard Java annotation and works on Kotlin `open` functions. Make sure your Spring beans are open (either use `kotlin-spring` compiler plugin or annotate the class `open`).

**Does it work with reactive / WebFlux?**
The `@InjectChaos` AOP aspect works on any Spring bean method, including those in reactive services. However, the latency injection uses `Thread.sleep()`, which blocks the calling thread — this can stall reactive pipelines. HTTP-layer fault injection (`ChaosHttpFaultFilter`) requires a servlet container and does not support WebFlux.

**Can I use this in production to do canary fault injection?**
The library is designed for dev/staging use. The production guard actively prevents enabling it on `prod`/`production`/`live` profiles. That said, if you remove or override the forbidden-profiles list and use a low `failureRate`, it can work in production — but you do so at your own risk.

**Can I toggle chaos on/off without restarting?**
The `/actuator/chaos` endpoint lets you toggle `dry-run` mode at runtime (which effectively suppresses all chaos without a restart). Full enable/disable at runtime is not yet supported — it requires a restart with the appropriate YAML profile.

---

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md) for how to report bugs, request features, and submit pull requests.

---

## License

Apache License 2.0 — see [LICENSE](LICENSE) for the full text.
