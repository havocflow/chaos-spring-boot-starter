# Security Policy

## Supported Versions

| Version | Supported |
|---------|-----------|
| 1.x     | Yes       |

## Reporting a Vulnerability

**Please do not open a public GitHub issue for security vulnerabilities.**

Report security issues privately via GitHub's
[Security Advisories](https://github.com/havocflow/chaos-spring-boot-starter/security/advisories/new)
feature (Repository → Security → Advisories → New draft advisory).

You can expect an acknowledgement within 72 hours and a fix or mitigation plan within 14 days for
confirmed vulnerabilities.

## Scope

HavocFlow is a **development and staging tool only**. It is designed to be enabled via
`chaos.enabled=true` in non-production Spring profiles. The production safety guard
([`ChaosProductionGuard`](src/main/java/io/havocflow/autoconfigure/ChaosProductionGuard.java))
blocks activation in `prod`, `production`, and `live` profiles by default.

**Never deploy HavocFlow with `chaos.enabled=true` in production.** Doing so can cause
intentional service degradation, latency injection, and exception storms in live traffic.

## Security Configuration Recommendations

### Restrict injectable exception classes

By default `chaos.allowed-exception-classes` is empty, meaning any `Throwable` subclass on the
classpath may be referenced in scenario config. In shared or multi-tenant environments, configure
an explicit allowlist:

```yaml
chaos:
  allowed-exception-classes:
    - java.lang.RuntimeException
    - java.io.IOException
    - java.sql.SQLException
    - org.springframework.dao.QueryTimeoutException
```

### Secure the Actuator endpoint

If `spring-boot-starter-actuator` is on the classpath, the `/actuator/chaos` endpoint is
registered. Secure it like any other sensitive actuator endpoint:

```yaml
management:
  endpoints:
    web:
      exposure:
        include: health, info   # do not expose 'chaos' in production
```

### Production profile guard

The default forbidden profiles are `prod`, `production`, and `live`. If your organisation uses
different profile names (e.g. `prd`, `release`), add them:

```yaml
chaos:
  forbidden-profiles: "prod,production,live,prd,release"
```
