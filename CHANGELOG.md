# Changelog

All notable changes to HavocFlow will be documented in this file.

The format follows [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

---

## [Unreleased]

_No unreleased changes yet._

---

## [1.0.0] — 2026-04-11

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

[Unreleased]: https://github.com/havocflow/chaos-spring-boot-starter/compare/v1.0.0...HEAD
[1.0.0]: https://github.com/havocflow/chaos-spring-boot-starter/releases/tag/v1.0.0
