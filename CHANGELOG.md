# Changelog

All notable changes to HavocFlow will be documented in this file.

The format follows [Keep a Changelog](https://keepachangelog.com/en/1.0.0/).

### Version format: `MAJOR.MINOR.HOTFIX` (zero-padded, e.g. `01.02.03`)

| Segment | When to bump | Example trigger |
|---------|-------------|-----------------|
| **MAJOR** | Breaking API or config changes | Rename annotation attribute, remove a property, change behaviour contract |
| **MINOR** | New backwards-compatible features (target: ~2-week sprint) | New gremlin strategy, new config option, new annotation |
| **HOTFIX** | Urgent bug fixes and security patches | Fix a ReDoS, fix a data race, fix a crash |

---

## [Unreleased]

_No unreleased changes yet._

---

## [01.04.03] — 2026-07-26

### Security

**CVE — Spring Kafka `DelegatingDeserializer` heap-DoS (unbounded selector cache)**
- `spring-kafka` versions 4.0.0–4.0.5, 3.3.0–3.3.15, 3.2.0–3.2.13, 2.9.0–2.9.13, and
  2.8.0–2.8.11 contain a heap-DoS vulnerability: `DelegatingDeserializer` caches delegate
  deserializer instances in an **unbounded** `HashMap` keyed by the
  `spring.kafka.serialization.selector` Kafka record header. A malicious producer can send
  records with unique random header values, growing the cache without bound →
  GC thrash → `OutOfMemoryError`.
- HavocFlow's `ChaosKafkaAspect` does not use `DelegatingDeserializer` and is not directly
  exploitable via this vector. However, HavocFlow users who configure `DelegatingDeserializer`
  in their own consumer factories are affected when running a vulnerable spring-kafka version.
- **Fix**: new `io.havocflow.kafka.BoundedDelegatingDeserializer<T>` — a drop-in replacement
  with two layered defences:
  - **Bounded LRU cache** (`havocflow.bdd.max-cache-size`, default 100): backed by a
    `LinkedHashMap` with `removeEldestEntry`; evicts and closes the LRU delegate when full,
    preventing unbounded heap growth regardless of selector cardinality.
  - **Allowlist enforcement** (`havocflow.bdd.selector-class-map`): unknown selectors are
    rejected with `IllegalArgumentException` immediately — deny-by-default posture prevents
    arbitrary class loading from untrusted header values.
  - Thread-safe; no Spring beans required; configured via standard `ConsumerFactory` properties.
  - Handles both value (`spring.kafka.serialization.selector`) and key
    (`spring.kafka.key.serialization.selector`) routing headers.
  - To migrate: replace `org.springframework.kafka.support.serializer.DelegatingDeserializer`
    with `io.havocflow.kafka.BoundedDelegatingDeserializer` in your consumer factory and
    populate `havocflow.bdd.selector-class-map` with the allowed selector → class mappings.

---

## [01.04.02] — 2026-07-26

### Security

**Advisory — Spring Kafka header-mapper trusted-package deserialization**
- `spring-kafka:2.9.13` (optional dependency) is within the affected range of a
  Spring for Apache Kafka vulnerability where `JsonKafkaHeaderMapper` and the deprecated
  `DefaultKafkaHeaderMapper` matched type headers using a simple prefix check, allowing any
  sub-package of a trusted package to pass the guard. Combined with Jackson's default bean
  deserialization, a crafted producer could supply header values that caused the consumer to
  deserialize arbitrary JDK types.
- HavocFlow's `ChaosKafkaAspect` does not configure or invoke header mappers — it intercepts
  `@KafkaListener` methods via AOP only. HavocFlow itself is not exploitable via this vector.
  However, security scanners may flag `spring-kafka:2.9.13` as a vulnerable direct dependency.
- **No patch available for the 2.9.x line.** The fix was released in spring-kafka 3.x. HavocFlow
  targets Java 8 / Spring Boot 2.7.x and cannot move to 3.x. `2.9.13` is the final 2.9.x release.
  Users who configure `JsonKafkaHeaderMapper` or `DefaultKafkaHeaderMapper` in their own consumer
  factories should migrate to spring-kafka 3.x or restrict the trusted-packages list explicitly.

**Dependabot — OpenTelemetry unbounded baggage parsing (W3C / Jaeger / OtTrace propagators)**
- `opentelemetry-api:1.38.0` (optional dependency) is within the affected range: `W3CBaggagePropagator`,
  `JaegerPropagator`, and `OtTracePropagator` enforced no limit on baggage header size or entry count,
  enabling unbounded memory allocation and CPU consumption that can propagate to downstream services.
- HavocFlow does not use baggage propagation — the OTel integration only records `chaos.gremlin.fired`
  span events. HavocFlow itself is not exploitable via this vector.
  However, security scanners flag `opentelemetry-api:1.38.0` as a vulnerable direct dependency.
- **Fix**: bumped optional `opentelemetry-api` and test `opentelemetry-sdk-testing` from `1.38.0` to `1.62.0`.

---

## [01.04.01] — 2026-04-25

### Security / Fixed

- **`ChaosProperties`**: `scenarios` map changed from `HashMap` to `ConcurrentHashMap` — eliminates
  a potential `ConcurrentModificationException` when the `activateScenario()` daemon thread races
  with request threads iterating the map inside `ChaosEngine.resolveFromScenario()`.
- **`ChaosProperties.RampUpProperties`**: added range validation (0.0–1.0) on
  `setStartFailureRate()` and `setEndFailureRate()`, matching the guard already present on every
  other rate setter in the class. Out-of-range values now throw `IllegalArgumentException` at
  configuration-bind time rather than being silently clamped at runtime.
- **`ExperimentTemplateLoader.builtInTemplateNames()`**: now returns
  `Collections.unmodifiableList(...)` instead of a mutable `ArrayList`, preventing callers from
  accidentally mutating the list.

---

## [01.04.00] — 2026-04-25

### Added

**Spring WebFlux reactive HTTP fault injection (`io.havocflow.web.ChaosWebFilter`)**
- New `ChaosWebFilter` implementing `WebFilter` — the non-blocking equivalent of
  `ChaosHttpFaultFilter` for Spring WebFlux applications.
- Uses `Mono.delay(Duration)` for latency (never blocks the event loop) and
  `exchange.getResponse().setComplete()` for short-circuit fault responses.
- Reuses the existing `chaos.http-fault.*` configuration namespace — no new properties required.
- Registered via `@ConditionalOnClass("org.springframework.web.server.WebFilter")` and
  `@ConditionalOnWebApplication(type=REACTIVE)`. Suppressed when Spring Cloud Gateway is
  present (`ChaosGatewayFilter` handles reactive fault injection in that case).
- New optional compile dependency: `spring-webflux` (no Netty pulled in).
- Test: `ChaosWebFilterTest` using `MockServerWebExchange` + `StepVerifier.withVirtualTime()`.

**Fault Ramp-Up — gradual probability increase (`io.havocflow.core.RampUpCalculator`)**
- New `RampUpProperties` nested class inside `ScenarioProperties` adds four config fields:
  `enabled`, `duration`, `start-failure-rate`, `end-failure-rate`.
- New `RampUpCalculator` utility computes the linearly interpolated failure rate given elapsed time.
  The ramp clock starts lazily on the first invocation of the scenario.
- `ChaosEngine` uses the interpolated rate instead of the static `failureRate` when
  `ramp-up.enabled=true` on a scenario, unless an inline `@InjectChaos` override is active.
- Config path: `chaos.scenarios.<name>.ramp-up.enabled`, `...duration`, `...start-failure-rate`,
  `...end-failure-rate`.
- Test: `RampUpCalculatorTest` with exact time boundary assertions.

**Experiment Templates — pre-built scenario presets (`io.havocflow.autoconfigure.ExperimentTemplateLoader`)**
- New `ExperimentTemplateLoader` implementing `SmartInitializingSingleton` merges five built-in
  scenario presets into `ChaosProperties.scenarios` at startup. User-defined scenarios always win.
- Built-in templates: `golden-signal-degradation`, `database-timeout`, `network-partition`,
  `cascading-failure`, `cache-miss-storm`. Each is a pre-configured `ScenarioProperties`.
- `GET /actuator/chaos` response now includes a `builtInTemplates` field listing available template names.
- Test: `ExperimentTemplateLoaderTest`.

**Testcontainers Integration (`io.havocflow.test.ChaosContainerSupport`)**
- New `ChaosContainerSupport` utility with static factory method
  `withScenario(GenericContainer, ChaosProperties, String)` — wraps a container to
  activate a chaos scenario when the container starts and deactivate it when it stops.
- New `ScenarioProperties.active` volatile boolean flag controls per-scenario on/off state.
  `ChaosEngine.resolveFromScenario()` short-circuits to `NONE` when `active=false`.
- New `ChaosProperties.activateScenario(String, int)` utility: temporarily activates a
  scenario for a fixed duration on a background daemon thread.
- New optional dependency: `org.testcontainers:testcontainers:1.19.7`.
- Registered via `@ConditionalOnClass("org.testcontainers.containers.GenericContainer")`.
- Test: `ChaosContainerSupportTest` (uses Mockito mock — no Docker required).

---

## [01.03.01] — 2026-04-17

### Security

**CVE-2026-22733 — remove `spring-boot-actuator-autoconfigure` from dependency tree**
- `spring-boot-starter-actuator` (optional build dependency) transitively pulled in
  `spring-boot-actuator-autoconfigure`, which contains CVE-2026-22733 (CVSS 8.2 HIGH —
  Authentication Bypass via CloudFoundry Actuator endpoints).
- HavocFlow itself was never exploitable: `ChaosEndpoint` registers at `/actuator/chaos`,
  not under CloudFoundry paths, and HavocFlow carries no Spring Security dependency.
  However, security scanners correctly flag the vulnerable artifact as a direct dependency.
- **Fix**: replaced `spring-boot-starter-actuator` (optional) with `spring-boot-actuator`
  (optional) in `pom.xml`. `ChaosEndpoint` only requires `@Endpoint` / `@ReadOperation` /
  `@WriteOperation` from `spring-boot-actuator`; the autoconfigure module was never needed.
- `spring-boot-actuator-autoconfigure` is no longer present anywhere in HavocFlow's
  dependency tree. Scanners will no longer flag this CVE against HavocFlow artifacts.

---

## [01.03.00] — 2026-04-15

### Added

**Spring Kafka chaos (`io.havocflow.kafka`)**
- New `ChaosKafkaAspect` applies chaos globally to all `@KafkaListener` methods without requiring
  `@InjectChaos` on each listener. Simulates consumer lag (latency) and processing failures (exceptions).
- Activated when `spring-kafka` is on the classpath and `chaos.kafka.enabled=true`.
- Config: `chaos.kafka.latency`, `chaos.kafka.failure-rate`, `chaos.kafka.exception`.
- Respects the global `chaos.schedule.*` time window and `chaos.max-latency-millis` cap.

**OpenTelemetry span events (`io.havocflow.otel`)**
- New `ChaosOtelSpanRecorder` adds a `chaos.gremlin.fired` event to the current OTel span
  every time a gremlin fires, making chaos injections visible in distributed traces.
- Zero-config: just add `opentelemetry-api` to the classpath. No `chaos.otel.*` properties needed.
- Span event attributes: `chaos.method`, `chaos.mode`, `chaos.scenario`, `chaos.latency_ms`.
- Registered via `@ConditionalOnClass("io.opentelemetry.api.trace.Span")`. No-op when no span is active.

**Webhook/Slack notifications (`io.havocflow.notify`)**
- New `ChaosWebhookNotifier` POSTs a JSON payload to a configurable webhook URL when a gremlin fires.
  Works with any HTTP webhook endpoint including Slack Incoming Webhooks.
- Notifications are dispatched on a background daemon thread — never blocks the calling thread.
- Config: `chaos.notifications.enabled`, `chaos.notifications.webhook-url`,
  `chaos.notifications.modes` (filter by `FailureMode` name).
- No new dependencies — uses `java.net.HttpURLConnection`. Implements `DisposableBean` for clean shutdown.

**Spring Cloud Gateway filter (`io.havocflow.gateway`)**
- New `ChaosGatewayFilter` implements `GlobalFilter` for reactive, non-blocking fault injection at
  the API gateway level. Uses `Mono.delay()` instead of `Thread.sleep()`.
- Reuses the existing `chaos.http-fault.*` configuration — no new properties required.
- Registered via `@ConditionalOnClass("org.springframework.cloud.gateway.filter.GlobalFilter")`.

---

## [01.02.00] — 2026-04-14

### Added

**`@SuppressChaos` annotation**
- New `@SuppressChaos` method annotation that exempts a specific method from class-level or
  interface-level `@InjectChaos` chaos injection without removing the class annotation.
- Enforced at both the AspectJ pointcut level and with a belt-and-suspenders runtime guard in
  `ChaosAspect`, so new advice paths added in future also respect the suppression.

**Scenario inheritance**
- Named scenarios can now extend a base scenario via `chaos.scenarios.<name>.extends: <base-name>`.
- Merge rules: child field wins when explicitly set (non-blank latency, rate > 0, non-default
  exception); otherwise the base value is used.
- Circular-reference detection: throws `IllegalStateException` if the extends chain exceeds 10 levels.

**Chaos scheduling**
- New `chaos.schedule.cron` + `chaos.schedule.duration` properties enable time-windowed chaos:
  chaos is active only during the configured cron window and auto-disables when the duration elapses.
- Implemented by `ChaosScheduler` (new bean, registered conditionally on `chaos.schedule.enabled=true`
  and a Spring `TaskScheduler` bean being present).
- Window state (`scheduleWindowActive`) is a `volatile boolean` on `ChaosProperties`; `ChaosAspect`
  passes all calls through when the window is closed.

**Structured audit log**
- New `chaos.audit-log.enabled` + `chaos.audit-log.path` properties activate an append-only
  JSON-lines audit file that records every gremlin fired with timestamp, method, mode, scenario,
  latencyMs, and thread name.
- Implemented by `ChaosAuditLogger` (new bean). Zero new dependencies — JSON is hand-crafted.
- File is flushed after every entry and closed cleanly on shutdown.

### Security

- `LatencyParser`: replaced `java.util.Random` with `ThreadLocalRandom` (thread-safe; no shared
  mutable state across threads). Tracking issue originally filed under 01.00.01.
- GitHub Actions `release.yml`: `softprops/action-gh-release` pinned to commit SHA
  `3bb12739c298aeb8a4eeaf626c5b8d85266b0e65` (was floating `v2` tag — supply chain risk).
- GitHub Actions `ci.yml` / `release.yml`: added explicit `permissions` blocks (`contents: read`
  and `contents: write` respectively) following least-privilege principle.

---

## [01.00.01] — Unreleased (security fixes folded into 01.02.00)

### Fixed
- `ChaosHttpFaultFilter`: replaced hand-rolled Ant regex with Spring `AntPathMatcher` (eliminates ReDoS risk)
- `ChaosEngine` / `ChaosHttpFaultFilter`: replaced shared `Random` with `ThreadLocalRandom` (thread-safe probability)
- `ChaosProperties`: `dryRun` field marked `volatile` (prevents JVM visibility race on actuator toggle)
- `LatencyParser`: rejects numeric values > 999,999,999 before unit multiplication (prevents `NumberFormatException` on absurd input)
- `ChaosEngine`: emits one-time `INFO` advisory at startup when `allowed-exception-classes` is not configured
- `ChaosHttpFaultFilter`: per-request HTTP fault log demoted from `WARN` to `DEBUG`

### Added
- `SECURITY.md`: private vulnerability reporting path, production safety warnings, allowlist guidance

---

## [01.00.00] — 2026-04-11

### Added

**Core chaos engine**
- `@InjectChaos` annotation for method- and class-level chaos injection
- `ChaosEngine` with three-level decision resolution: named scenario → inline annotation → global default
- `FailureMode` enum: `NONE`, `LATENCY`, `EXCEPTION`, `LATENCY_AND_EXCEPTION`
- `LatencyParser` supporting human-friendly formats: `200ms`, `2s`, `1m`, `100ms-500ms` (jitter range)
- `ChaosDecision` immutable value object carrying resolved fault parameters

**AOP integration**
- `ChaosAspect` — AspectJ around-advice that intercepts all `@InjectChaos`-annotated methods
- Class-level annotation fallback when no method-level annotation is present

**Auto-configuration**
- `ChaosAutoConfiguration` with `@ConditionalOnProperty(matchIfMissing=false)` — zero overhead when not explicitly enabled
- `ChaosProperties` with full validation (failure rate bounds, latency cap, exception allowlist)
- `ChaosProductionGuard` — early-startup `ApplicationListener` that blocks startup if chaos is enabled on a forbidden profile (`prod`, `production`, `live` by default)

**Safety guardrails**
- Default off (`chaos.enabled` must be explicitly `true`)
- Profile-based startup guard with configurable forbidden profiles
- Hard latency cap (`max-latency-millis`, default 30 000 ms)
- Exception class blocklist (rejects `System`, `Runtime`, `ProcessBuilder`, `Shutdown`)
- Optional exception allowlist (`allowed-exception-classes`)
- Dry-run mode (`chaos.dry-run=true`) — logs decisions without executing chaos

**HTTP fault injection**
- `ChaosHttpFaultFilter` — servlet filter for HTTP-layer faults (configurable status code, latency, path patterns, failure rate)

**Gremlin strategies**
- `CpuStressStrategy` — spins background threads for a configurable duration (capped at 5 s)
- `MemoryPressureStrategy` — allocates `SoftReference`-backed byte arrays up to 25% of max heap
- `ConnectionPoolExhaustionStrategy` — holds JDBC connections for a configurable duration with guaranteed release

**Observability**
- `ChaosMetricsRecorder` — Micrometer counter `chaos.injections` tagged by method, mode, and scenario
- `ChaosEventStore` — thread-safe ring buffer storing the last N injection events (default 100)
- Actuator endpoint `/actuator/chaos` — exposes status, event history, and live dry-run toggle

**SPI**
- `ChaosStrategy` interface — extension point for custom fault strategies (`canHandle` / `apply`)

**Spring Boot compatibility**
- Spring Boot 2.7.x and 3.x
- Java 8 through Java 21
- `spring.factories` + `AutoConfiguration.imports` dual registration

[01.04.03]: https://github.com/havocflow/chaos-spring-boot-starter/compare/v01.04.02...v01.04.03
[01.04.02]: https://github.com/havocflow/chaos-spring-boot-starter/compare/v01.04.01...v01.04.02
[01.00.01]: https://github.com/havocflow/chaos-spring-boot-starter/compare/v01.00.00...HEAD
[01.00.00]: https://github.com/havocflow/chaos-spring-boot-starter/releases/tag/v01.00.00
