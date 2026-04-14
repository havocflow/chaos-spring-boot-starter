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

### [01.01.00] — 2026-04-14 — Test coverage + Spring Boot 3.x compatibility
Full unit and integration test suite for all previously untested components. Spring Boot 3.x partial
support: `ChaosHttpFaultFilter` is now guarded with `@ConditionalOnClass("javax.servlet.FilterChain")`
so it no longer crashes on Spring Boot 3.x (filter simply won't register; all other features work).
Configurable HTTP fault response body, library+version Micrometer tags, and `@InjectChaos` support
on Spring `@Component` interfaces.

### [01.02.00] — 2026-04-14 — Developer ergonomics + security hardening
`@SuppressChaos` annotation to exempt individual methods from class-level chaos. Scenario inheritance
(`extends` field in scenario config). Time-windowed chaos scheduling (`chaos.schedule.cron` +
`chaos.schedule.duration`). Structured append-only JSON-lines audit log. Security: `ThreadLocalRandom`
in `LatencyParser`, pinned GitHub Action SHA, explicit workflow permissions.

---

## In Progress

### [01.00.01] — Hotfix — Security hardening (folded into 01.02.00)
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
