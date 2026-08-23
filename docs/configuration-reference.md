# Configuration Reference

This document provides a comprehensive reference of all configuration properties supported and required by **Spring Liveness Indicators**.

---

## 📋 Properties Reference

### 1. Library-Specific Properties (`liveness.kafka.*`)

Configured in [`LivenessCheckersAutoConfiguration.java`](file:///c:/workdir/spring-liveness-indicators/src/main/java/io/github/vfem/livenesscheck/spring/kafka/LivenessCheckersAutoConfiguration.java) and sampled in [`application-sample.yml`](file:///c:/workdir/spring-liveness-indicators/src/main/resources/sample/application-sample.yml):

| Property | Type | Default | Description |
| :--- | :--- | :--- | :--- |
| `liveness.kafka.scheduled` | `Boolean` | `true` | Enables or disables background periodic offset movement checks via the internal executor. |
| `liveness.kafka.check-initial-delay-sec` | `long` | `600` | Initial delay in seconds before the first scheduled offset evaluation begins. Must be `> 0`. |
| `liveness.kafka.check-period-sec` | `long` | `600` | Interval in seconds between subsequent offset evaluations. Must be `> 0`. |

---

### 2. Spring Boot Actuator Properties (Required for Activation)

Evaluated by [`LivenessCheckerCondition.java`](file:///c:/workdir/spring-liveness-indicators/src/main/java/io/github/vfem/livenesscheck/spring/kafka/LivenessCheckerCondition.java):

| Property | Expected Value | Purpose |
| :--- | :--- | :--- |
| `management.endpoint.health.probes.enabled` | `true` | Enables health probe endpoints in Spring Boot Actuator. |
| `management.health.livenessstate.enabled` | `true` | Enables the `/actuator/health/liveness` indicator. |

---

## 📄 Example Configurations

### Production Example (`application.yml`)

```yaml
management:
  health:
    livenessstate:
      enabled: true
  endpoint:
    health:
      probes:
        enabled: true

liveness:
  kafka:
    scheduled: true
    check-initial-delay-sec: 120   # 2 minutes initial warmup
    check-period-sec: 300          # Check every 5 minutes
```

### Integration Test Example (`src/test/resources/application.yml`)

View: [`src/test/resources/application.yml`](file:///c:/workdir/spring-liveness-indicators/src/test/resources/application.yml)

```yaml
management:
  health:
    livenessstate:
      enabled: true
  endpoint:
    health:
      probes:
        enabled: true

liveness:
  kafka:
    scheduled: false             # Disable background executor to allow manual invocation in tests
    check-period-sec: 5
    check-initial-delay-sec: 2
```

---

## 🔗 Related Documents

- [Auto-Configuration Details](file:///c:/workdir/spring-liveness-indicators/docs/auto-configuration.md)
- [Architecture & Flow](file:///c:/workdir/spring-liveness-indicators/docs/architecture.md)
- [Wiki Index](file:///c:/workdir/spring-liveness-indicators/docs/README.md)
