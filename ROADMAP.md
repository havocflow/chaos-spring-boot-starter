# HavocFlow Roadmap

> Version format: `MAJOR.MINOR.HOTFIX` (e.g. `01.02.03`)
>
> | Segment | Rule |
> |---------|------|
> | **HOTFIX** | Urgent bug fix or security patch — ship as soon as it's ready |
> | **MINOR** | New backwards-compatible feature — target a ~2-week sprint |
> | **MAJOR** | Breaking API or config change — planned milestone, requires migration guide |

---

## Released

### [01.00.00] — 2026-04-11 — Initial release
First open-source release. Annotation-driven chaos injection, AOP interception, HTTP fault filter,
three gremlin strategies (CPU, memory, connection pool), Micrometer metrics, Actuator endpoint,
production safety guard, dry-run mode.

---

## In Progress

### [01.00.01] — Hotfix — Security hardening
Fixes identified during open-source readiness audit. No API or config changes.

- [x] Replace `antMatch()` regex with Spring `AntPathMatcher` (ReDoS fix)
- [x] `ThreadLocalRandom` instead of shared `Random` (thread-safety)
- [x] `volatile dryRun` field (JVM visibility fix)
- [x] `LatencyParser` overflow guard (parse-time bounds check)
- [x] Startup advisory when `allowed-exception-classes` is unconfigured
- [x] HTTP fault logs demoted WARN → DEBUG
- [x] `SECURITY.md` added

---

## Planned

### [01.01.00] — Minor — Test coverage + Spring Boot 3.x compatibility
**Target: ~2 weeks**

These gaps exist today and directly affect confidence in the library for new adopters.

#### Test coverage (none of these have tests today)
- [ ] `ChaosHttpFaultFilter` — unit tests for path matching, latency injection, error status injection
- [ ] `ChaosEndpoint` — actuator endpoint tests: GET status, POST dry-run toggle, event history
- [ ] `ChaosMetricsRecorder` — verify Micrometer counters are emitted with correct tags
- [ ] `ChaosEventStore` — ring-buffer eviction: fill past capacity, assert oldest events dropped
- [ ] `CpuStressStrategy` — verifies background threads start and terminate within declared duration
- [ ] `MemoryPressureStrategy` — verifies allocation does not exceed 25% heap cap
- [ ] `ConnectionPoolExhaustionStrategy` — verifies connections are always released (even on exception)

#### Spring Boot 3.x servlet namespace
- [ ] `ChaosHttpFaultFilter` uses `javax.servlet.*` — Spring Boot 3.x uses `jakarta.servlet.*`
- [ ] Auto-detect which namespace is on the classpath and register the correct filter class
- [ ] Add a Spring Boot 3.x integration test in CI matrix

#### Small improvements
- [ ] Configurable HTTP fault response body (today hardcoded to `"[HavocFlow] HTTP fault injected"`)
- [ ] `@InjectChaos` on Spring `@Component` interfaces (today only concrete bean proxies work)
- [ ] Add `library` and `version` tags to all Micrometer metrics for multi-library dashboards

---

### [01.02.00] — Minor — Developer ergonomics
**Target: ~4 weeks after 01.01.00**

Features most commonly requested by chaos engineering users.

#### `@SuppressChaos` annotation
Exclude a specific method from class-level `@InjectChaos` without removing the class annotation.
```java
@InjectChaos(latency = "200ms", failureRate = 0.1)
public class OrderService {

    public Order findById(String id) { ... }   // chaos applied

    @SuppressChaos                             // excluded
    public Order save(Order order) { ... }
}
```

#### Scenario inheritance
A named scenario can extend a base scenario and override only specific fields:
```yaml
chaos:
  scenarios:
    base-latency:
      latency: "100ms"
      failure-rate: 0.05
    db-timeout:
      extends: base-latency      # inherits latency and failure-rate
      latency: "3s"              # overrides only latency
      exception: org.springframework.dao.QueryTimeoutException
```

#### Chaos scheduling
Enable chaos on a cron window and auto-disable when the window closes — no manual toggle needed:
```yaml
chaos:
  schedule:
    enabled: true
    cron: "0 0 10 * * MON-FRI"   # every weekday at 10:00
    duration: "30m"               # active for 30 minutes, then auto-off
```

#### Structured chaos audit log
Append-only JSON log of every gremlin fired (timestamp, method, mode, scenario, latency, thread).
Useful for incident investigations and compliance reviews:
```yaml
chaos:
  audit-log:
    enabled: true
    path: /var/log/havocflow-audit.jsonl
```

---

### [01.03.00] — Minor — Ecosystem integrations
**Target: ~6 weeks after 01.02.00**

#### Spring Kafka chaos
Apply `@InjectChaos` directly on `@KafkaListener` methods to simulate consumer lag and processing failures without touching application code:
```java
@KafkaListener(topics = "orders")
@InjectChaos(latency = "500ms", failureRate = 0.05)
public void onOrder(Order order) { ... }
```

#### OpenTelemetry span events
When `opentelemetry-api` is on the classpath, add a span event for every gremlin fired.
Chaos injections become visible in distributed traces with zero extra configuration.

#### Webhook / Slack notifications
POST a JSON payload to a configurable URL whenever a gremlin fires:
```yaml
chaos:
  notifications:
    webhook-url: https://hooks.slack.com/services/...
    on-modes: [EXCEPTION, LATENCY_AND_EXCEPTION]   # filter by severity
```

#### Spring Cloud Gateway filter
Register a `GatewayFilter` for fault injection at the API gateway level — inject faults before
requests reach downstream services, enabling gateway-level resilience testing.

---

### [02.00.00] — Major — Reactive / WebFlux support
**Breaking: requires Java 11+, new module**

The current `ChaosAspect` and `ChaosHttpFaultFilter` are blocking (synchronous). Reactive
applications using Spring WebFlux and `WebClient` need a non-blocking equivalent.

#### What changes (breaking)
- Minimum Java version raised from 8 to 11 (Project Reactor requires Java 11)
- New module `chaos-spring-boot-starter-reactive` (separate artifact)
- `ChaosWebFilter` replaces `ChaosHttpFaultFilter` for WebFlux applications
- `ChaosReactiveAspect` applies chaos using `Mono.delay()` / `Mono.error()` instead of `Thread.sleep()` / `throw`

#### Migration guide needed
Existing `ChaosHttpFaultFilter` users on Spring MVC are unaffected. WebFlux users must switch to
the new reactive module. A migration guide will be published with the release.

---

## Future ideas (not yet scheduled)

| Idea | Description |
|------|-------------|
| Fault ramp-up | `failure-rate` increases linearly from 0 to target over a time window — simulates a degrading dependency |
| Chaos experiment templates | Built-in named presets: `db-latency`, `network-partition`, `cascading-failure` — apply with one property |
| Testcontainers integration | Auto-enable chaos scenarios when Testcontainers is detected on the classpath |
| Multi-scenario per method | Apply more than one named scenario to a single method |
| Chaos maturity score | Actuator endpoint that reports how many methods are annotated vs. total Spring beans |
| Admin CLI | `havocflow-cli` tool for toggling chaos and viewing event history without touching actuator directly |
