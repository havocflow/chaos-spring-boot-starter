# HavocFlow — Chaos Spring Boot Starter

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

// Inline — 2s latency + 10% failure rate
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
| `failureRate` | double  | `0.0`              | Probability of throwing an exception (0.0–1.0)   |
| `exception`   | Class   | `RuntimeException` | Exception type to throw on failure                |
| `scenario`    | String  | `""` (none)        | Named preset from `chaos.scenarios.*`             |
| `overridable` | boolean | `true`             | Allow runtime property overrides                  |

### Latency format

| Expression   | Result                         |
|--------------|--------------------------------|
| `"500ms"`    | Fixed 500 milliseconds         |
| `"2s"`       | Fixed 2 seconds                |
| `"100ms-2s"` | Random between 100ms and 2000ms|
| `"1s-5s"`    | Random between 1s and 5s      |

---

## Resolution order

```
Annotation attribute  ->  Named scenario  ->  Global default
     (highest)                                   (lowest)
```

---

## Dry-run mode

Set `chaos.dry-run=true` to see what chaos would fire without applying it:

```
[HavocFlow][DRY-RUN] Would apply ChaosDecision{mode=LATENCY_AND_EXCEPTION,
  latencyMs=3000, exception=QueryTimeoutException, scenario='db-timeout'}
  to OrderRepository#findById
```

---

## Metrics (Micrometer)

If `micrometer-core` is on the classpath, HavocFlow auto-publishes:

| Metric                   | Type    | Tags                        |
|--------------------------|---------|-----------------------------|
| `chaos.gremlins.fired`   | Counter | `method`, `mode`, `scenario`|
| `chaos.latency.injected` | Timer   | `method`, `mode`, `scenario`|

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
3. **Dry-run mode** — logs without acting
4. **Compile-time visibility** — every chaotic method is annotated, no hidden gremlins
5. **Test scope** — recommended `<scope>test</scope>` excludes from production artifacts

---

## Build from source

```bash
git clone https://github.com/havocflow/chaos-spring-boot-starter
cd chaos-spring-boot-starter
mvn clean install
```

Requires: Java 17+, Maven 3.8+

## License

Apache License 2.0
