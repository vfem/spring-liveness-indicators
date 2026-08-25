# Auto-Configuration

This document covers the Spring Boot auto-configuration mechanisms, activation conditions, and bean registration in **Spring Liveness Indicators**.

---

## ⚙️ Registration Mechanism

The starter is registered as an auto-configuration class via Spring Boot 3's auto-configuration imports mechanism:

- **Descriptor**: [`src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`](file:///c:/workdir/spring-liveness-indicators/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports)
- **Entry**:
  ```text
  io.github.vfem.livenesscheck.spring.kafka.LivenessCheckersAutoConfiguration
  ```

---

## 🚦 Activation Conditions

Auto-configuration is gated by [`LivenessCheckerCondition`](file:///c:/workdir/spring-liveness-indicators/src/main/java/io/github/vfem/livenesscheck/spring/kafka/LivenessCheckerCondition.java), which extends `AllNestedConditions` during `ConfigurationPhase.PARSE_CONFIGURATION`.

All 4 conditions must pass simultaneously:

| # | Condition | Evaluated Check | Source |
|---|---|---|---|
| 1 | `SpringKafkaInClassPath` | `@ConditionalOnClass(KafkaOperations.class)` | Spring Kafka is on classpath |
| 2 | `SpringActuatorInClassPath` | `@ConditionalOnClass(LivenessStateHealthIndicator.class)` | Spring Boot Actuator is on classpath |
| 3 | `HealthProbesAreEnabled` | `@ConditionalOnProperty(name = "management.endpoint.health.probes.enabled", havingValue = "true")` | Actuator probes enabled |
| 4 | `LivenessChecksAreEnabled` | `@ConditionalOnProperty(name = "management.health.livenessstate.enabled", havingValue = "true")` | Liveness health indicator enabled |

---

## 📦 Bean Definition: `LivenessCheckersAutoConfiguration`

Source: [`LivenessCheckersAutoConfiguration.java`](file:///c:/workdir/spring-liveness-indicators/src/main/java/io/github/vfem/livenesscheck/spring/kafka/LivenessCheckersAutoConfiguration.java)

```java
@AutoConfiguration
@Conditional(LivenessCheckerCondition.class)
public class LivenessCheckersAutoConfiguration {

    @Bean
    public CommittedOffsetMovementCheck committedOffsetMovementCheck(
            @Value("${liveness.kafka.admin-timeout-ms:5000}") long adminTimeoutMs,
            @Value("${liveness.kafka.max-stalled-checks:3}") int maxStalledChecks,
            ApplicationContext applicationContext,
            KafkaAdmin kafkaAdmin) {
        return new CommittedOffsetMovementCheck(
                adminTimeoutMs,
                maxStalledChecks,
                applicationContext,
                kafkaAdmin.getConfigurationProperties()
        );
    }
}
```

### Injected Dependencies:
- **`KafkaAdmin`**: Provided by Spring Kafka configuration; used to retrieve `kafkaAdmin.getConfigurationProperties()` for creating the Kafka `AdminClient`.
- **`ApplicationContext`**: Used to look up the `KafkaListenerEndpointRegistry` and publish `AvailabilityChangeEvent`.
- **`adminTimeoutMs`**: Injected with default (`5000` ms).
- **`maxStalledChecks`**: Injected with default (`3`).

---

## 🔗 Related Documents

- [Core Components](file:///c:/workdir/spring-liveness-indicators/docs/core-components.md)
- [Configuration Reference](file:///c:/workdir/spring-liveness-indicators/docs/configuration-reference.md)
- [Wiki Index](file:///c:/workdir/spring-liveness-indicators/docs/README.md)
