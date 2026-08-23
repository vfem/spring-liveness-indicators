# Architecture & Flow

This document details the architectural design of **Spring Liveness Indicators** and how it monitors Kafka consumer progress.

---

## 🏗️ High-Level System Architecture

```mermaid
flowchart TD
    subgraph SpringContext["Spring Application Context"]
        Registry["KafkaListenerEndpointRegistry"]
        LivenessCheck["CommittedOffsetMovementCheck"]
        EventPub["AvailabilityChangeEvent Publisher"]
        Actuator["Spring Boot Actuator Health Probe\n(/actuator/health/liveness)"]
    end

    subgraph KafkaBroker["Kafka Cluster"]
        Admin["Kafka AdminClient"]
        Topics["Topic Partitions"]
    end

    LivenessCheck -->|Extract Consumers on ApplicationReadyEvent| Registry
    LivenessCheck -->|Periodic Query: listOffsets & listConsumerGroupOffsets| Admin
    Admin --> Topics
    LivenessCheck -->|If Stalled: AvailabilityChangeEvent.publish(BROKEN)| EventPub
    EventPub --> Actuator
```

---

## 🔄 Lifecycle and Evaluation Flow

1. **Bootstrap & Auto-Configuration**:
   - [`LivenessCheckersAutoConfiguration`](file:///c:/workdir/spring-liveness-indicators/src/main/java/io/github/vfem/livenesscheck/spring/kafka/LivenessCheckersAutoConfiguration.java) activates when [`LivenessCheckerCondition`](file:///c:/workdir/spring-liveness-indicators/src/main/java/io/github/vfem/livenesscheck/spring/kafka/LivenessCheckerCondition.java) evaluates to true.
   - Instantiates [`CommittedOffsetMovementCheck`](file:///c:/workdir/spring-liveness-indicators/src/main/java/io/github/vfem/livenesscheck/spring/kafka/CommittedOffsetMovementCheck.java).

2. **Initialization (`init()`)**:
   - Triggered on [`ApplicationReadyEvent`](file:///c:/workdir/spring-liveness-indicators/src/main/java/io/github/vfem/livenesscheck/spring/kafka/CommittedOffsetMovementCheck.java#L92-L120).
   - Scans `KafkaListenerEndpointRegistry` for all `ConcurrentMessageListenerContainer` and `KafkaMessageListenerContainer` instances.
   - Extracts internal `KafkaConsumer` instances via reflection.
   - Schedules periodic polling via `ScheduledExecutorService`.

3. **Periodic Offset Evaluation (`checkConsumerProgress()`)**:
   - For each extracted `KafkaConsumer`:
     1. Extracts active `assignedPartitions` and excludes `pausedPartitions`.
     2. Queries Kafka `AdminClient.listOffsets(OffsetSpec.latest())` for latest partition end offsets.
     3. Queries Kafka `AdminClient.listConsumerGroupOffsets(groupId)` for committed consumer offsets.
     4. Compares the offsets against the stored history in `groupsTopicPartitionOffsets`.

4. **Liveness State Update**:
   - **Condition**: `currentOffset < latestOffset` AND `previousOffset >= currentOffset` (i.e. messages are pending, but the consumer has made zero progress since the previous check).
   - **Action**: Publishes `AvailabilityChangeEvent.publish(applicationContext, LivenessState.BROKEN)`.
   - **Normal condition**: If consumer has reached the end or moved forward, state remains intact and the cached offset is updated.

5. **Teardown (`shutdown()`)**:
   - On `@PreDestroy` ([`CommittedOffsetMovementCheck.java#L338`](file:///c:/workdir/spring-liveness-indicators/src/main/java/io/github/vfem/livenesscheck/spring/kafka/CommittedOffsetMovementCheck.java#L338)), gracefully terminates `ScheduledExecutorService` (with a 5s timeout fallback) and closes `AdminClient`.

---

## 🔗 Related Documents

- [Auto-Configuration Details](file:///c:/workdir/spring-liveness-indicators/docs/auto-configuration.md)
- [Core Components Breakdown](file:///c:/workdir/spring-liveness-indicators/docs/core-components.md)
- [Configuration Reference](file:///c:/workdir/spring-liveness-indicators/docs/configuration-reference.md)
