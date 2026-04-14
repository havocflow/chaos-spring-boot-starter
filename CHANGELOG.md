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

[01.00.01]: https://github.com/havocflow/chaos-spring-boot-starter/compare/v01.00.00...HEAD
[01.00.00]: https://github.com/havocflow/chaos-spring-boot-starter/releases/tag/v01.00.00
