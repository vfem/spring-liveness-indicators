# Configuration Reference

This document provides a comprehensive reference of all configuration properties supported and required by **Spring Liveness Indicators**.

---

## 📋 Properties Reference

### 1. Library-Specific Properties (`liveness.kafka.*`)

Configured in [`LivenessCheckersAutoConfiguration.java`](file:///c:/workdir/spring-liveness-indicators/src/main/java/io/github/vfem/livenesscheck/spring/kafka/LivenessCheckersAutoConfiguration.java) and sampled in [`application-sample.yml`](file:///c:/workdir/spring-liveness-indicators/src/main/resources/sample/application-sample.yml):

| Property | Type | Default | Description |
| :--- | :--- | :--- | :--- |
| `liveness.kafka.admin-timeout-ms` | `long` | `5000` | Timeout in milliseconds for Kafka `AdminClient` queries (`listOffsets`, `listConsumerGroupOffsets`). |
| `liveness.kafka.max-stalled-checks` | `int` | `3` | The maximum number of consecutive stalled checks before marking the application as `BROKEN`. |

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
    admin-timeout-ms: 5000         # 5 seconds timeout for Kafka AdminClient RPCs
```

### Integration Test Example (`src/test/resources/application.yml`)

View: [`src/test/resources/application.yml`](file:///c:/workdir/spring-liveness-indicators/src/test/resources/application.yml)

```yaml
spring:
  kafka:
    bootstrap-servers: ${spring.embedded.kafka.brokers}

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
    admin-timeout-ms: 3000
    max-stalled-checks: 3
```

---

## 🔗 Related Documents

- [Auto-Configuration Details](file:///c:/workdir/spring-liveness-indicators/docs/auto-configuration.md)
- [Architecture & Flow](file:///c:/workdir/spring-liveness-indicators/docs/architecture.md)
- [Wiki Index](file:///c:/workdir/spring-liveness-indicators/docs/README.md)
