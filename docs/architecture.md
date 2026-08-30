# Architecture & Flow

This document details the architectural design of **Spring Liveness Indicators** and how it monitors Kafka consumer progress.

---

## 🏗️ High-Level System Architecture

```mermaid
flowchart TD
    subgraph SpringContext["Spring Application Context"]
        Registry["KafkaListenerEndpointRegistry"]
        LivenessCheck["CommittedOffsetMovementCheck\n(implements HealthIndicator)"]
        EventPub["AvailabilityChangeEvent Publisher"]
        Actuator["Spring Boot Actuator Health Endpoint\n(/actuator/health & /actuator/health/liveness)"]
    end

    subgraph KafkaBroker["Kafka Cluster"]
        Admin["Kafka AdminClient"]
        Topics["Topic Partitions"]
    end

    LivenessCheck -->|Discover Containers on ApplicationReadyEvent| Registry
    Actuator -->|Invoke health() on-demand| LivenessCheck
    LivenessCheck -->|Query: listOffsets & listConsumerGroupOffsets| Admin
    Admin --> Topics
    LivenessCheck -->|If Stalled: AvailabilityChangeEvent.publish(BROKEN)| EventPub
    EventPub --> Actuator
    LivenessCheck -->|Returns Health.up() or Health.down()| Actuator
```

---

## 🔄 Lifecycle and Evaluation Flow

1. **Bootstrap & Auto-Configuration**:
   - [`LivenessCheckersAutoConfiguration`](file:///c:/workdir/spring-liveness-indicators/src/main/java/io/github/vfem/livenesscheck/spring/kafka/LivenessCheckersAutoConfiguration.java) activates when [`LivenessCheckerCondition`](file:///c:/workdir/spring-liveness-indicators/src/main/java/io/github/vfem/livenesscheck/spring/kafka/LivenessCheckerCondition.java) evaluates to true.
   - Instantiates [`CommittedOffsetMovementCheck`](file:///c:/workdir/spring-liveness-indicators/src/main/java/io/github/vfem/livenesscheck/spring/kafka/CommittedOffsetMovementCheck.java) as a Spring Actuator `HealthIndicator`.

2. **Initialization (`init()`)**:
   - Triggered on [`ApplicationReadyEvent`](file:///c:/workdir/spring-liveness-indicators/src/main/java/io/github/vfem/livenesscheck/spring/kafka/CommittedOffsetMovementCheck.java#L150-L155).
   - Resolves and caches all `ConcurrentMessageListenerContainer` and `KafkaMessageListenerContainer` instances across all `KafkaListenerEndpointRegistry` and `MessageListenerContainer` beans.

3. **On-Demand Offset Evaluation (`health() -> checkConsumerProgress()`)**:
   - Dynamically resolves current listener containers via [`resolveContainers()`](file:///c:/workdir/spring-liveness-indicators/src/main/java/io/github/vfem/livenesscheck/spring/kafka/CommittedOffsetMovementCheck.java#L104-L129).
   - For each tracked `MessageListenerContainer`:
     1. Extracts active `assignedPartitions` and skips paused containers (`isContainerPaused()` or `isPauseRequested()`).
     2. Queries Kafka `AdminClient.listOffsets(OffsetSpec.latest())` for latest partition end offsets.
     3. Queries Kafka `AdminClient.listConsumerGroupOffsets(groupId)` for committed consumer offsets.
     4. Compares the offsets against the stored history in `groupsTopicPartitionOffsets`.

4. **Liveness State Update**:
   - **Condition**: `currentOffset < latestOffset` AND `previousOffset >= currentOffset` (i.e. messages are pending, but the consumer has made zero progress since the previous check).
   - **Action**: Publishes `AvailabilityChangeEvent.publish(applicationContext, LivenessState.BROKEN)` and returns `Health.down()`.
   - **Normal condition**: If consumer has reached the end or moved forward, state remains intact, the cached offset is updated, and returns `Health.up()`.

5. **Teardown (`shutdown()`)**:
   - On `@PreDestroy` ([`CommittedOffsetMovementCheck.java#L358-L367`](file:///c:/workdir/spring-liveness-indicators/src/main/java/io/github/vfem/livenesscheck/spring/kafka/CommittedOffsetMovementCheck.java#L358-L367)), gracefully closes the `AdminClient` instance.

---

## 🔗 Related Documents

- [Auto-Configuration Details](file:///c:/workdir/spring-liveness-indicators/docs/auto-configuration.md)
- [Core Components Breakdown](file:///c:/workdir/spring-liveness-indicators/docs/core-components.md)
- [Configuration Reference](file:///c:/workdir/spring-liveness-indicators/docs/configuration-reference.md)
