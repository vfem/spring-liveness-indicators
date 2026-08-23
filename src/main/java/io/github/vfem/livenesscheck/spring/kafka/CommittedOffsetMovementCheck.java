package io.github.vfem.livenesscheck.spring.kafka;

import jakarta.annotation.PreDestroy;
import org.apache.commons.lang3.Validate;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.ListConsumerGroupOffsetsResult;
import org.apache.kafka.clients.admin.ListOffsetsResult;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.KafkaFuture;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.availability.AvailabilityChangeEvent;
import org.springframework.boot.availability.LivenessState;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationContext;
import org.springframework.context.event.EventListener;
import org.springframework.kafka.config.KafkaListenerConfigUtils;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer;
import org.springframework.kafka.listener.MessageListenerContainer;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * CommittedOffsetMovementCheck monitors the progress of committed consumer offsets in Kafka.
 * It implements {@link HealthIndicator} to perform on-demand checks when Actuator health
 * endpoints (such as Kubernetes liveness probes) are invoked.
 *
 * If a consumer has pending messages on an assigned partition and fails to progress between checks,
 * it returns {@link Health#down()} and publishes an {@link AvailabilityChangeEvent} indicating {@link LivenessState#BROKEN}.
 */
public final class CommittedOffsetMovementCheck implements HealthIndicator {

    private static final Logger log = LoggerFactory.getLogger(CommittedOffsetMovementCheck.class);

    private final long adminTimeoutMs;
    private final int maxStalledChecks;
    private final ApplicationContext applicationContext;
    private final Map<String, Map<TopicPartition, OffsetAndMetadata>> groupsTopicPartitionOffsets = new HashMap<>();
    private final Map<TopicPartition, Integer> stalledChecksCount = new HashMap<>();
    private final Set<MessageListenerContainer> containers = new CopyOnWriteArraySet<>();
    private final AdminClient adminClient;

    /**
     * Constructor for CommittedOffsetMovementCheck.
     *
     * @param adminTimeoutMs     the timeout in milliseconds for AdminClient calls
     * @param maxStalledChecks   the maximum number of stalled checks before failing
     * @param applicationContext the Spring application context
     * @param kafkaAdminConfig   the Kafka Configuration
     */
    public CommittedOffsetMovementCheck(long adminTimeoutMs,
                                        int maxStalledChecks,
                                        ApplicationContext applicationContext,
                                        Map<String, Object> kafkaAdminConfig) {
        Validate.notNull(applicationContext, "ApplicationContext is null");
        Validate.isTrue(adminTimeoutMs > 0, "adminTimeoutMs must be greater than 0");
        Validate.isTrue(maxStalledChecks > 0, "maxStalledChecks must be greater than 0");

        this.adminTimeoutMs = adminTimeoutMs;
        this.maxStalledChecks = maxStalledChecks;
        this.applicationContext = applicationContext;
        this.adminClient = AdminClient.create(kafkaAdminConfig);
    }

    public CommittedOffsetMovementCheck(ApplicationContext applicationContext,
                                        Map<String, Object> kafkaAdminConfig) {
        this(5000L, 3, applicationContext, kafkaAdminConfig);
    }

    /**
     * Initializes the check by extracting listener containers.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void init() {
        KafkaListenerEndpointRegistry registry = applicationContext.getBean(
                KafkaListenerConfigUtils.KAFKA_LISTENER_ENDPOINT_REGISTRY_BEAN_NAME,
                KafkaListenerEndpointRegistry.class
        );

        Collection<MessageListenerContainer> allContainers = registry.getAllListenerContainers();

        for (MessageListenerContainer container : allContainers) {
            if (container instanceof ConcurrentMessageListenerContainer<?, ?> concurrentContainer) {
                List<? extends MessageListenerContainer> listenerContainers = concurrentContainer.getContainers();
                containers.addAll(listenerContainers);
            } else {
                containers.add(container);
            }
        }
        log.info("CommittedOffsetMovementCheck initialized with {} Kafka containers", containers.size());
    }

    /**
     * Evaluates consumer progress as part of Spring Boot Actuator's health check.
     *
     * @return {@link Health#up()} if all consumers are progressing or caught up; {@link Health#down()} otherwise.
     */
    @Override
    public Health health() {
        boolean healthy = checkConsumerProgress();
        if (healthy) {
            return Health.up()
                    .withDetail("trackedContainers", containers.size())
                    .build();
        }
        return Health.down()
                .withDetail("reason", "One or more Kafka consumers stalled while unconsumed messages remain")
                .withDetail("trackedContainers", containers.size())
                .build();
    }

    /**
     * Checks the progress of the committed offsets for each consumer.
     * If the offsets have not progressed, it publishes a liveness event indicating a broken state.
     *
     * @return true if all consumers are healthy or progressing, false if a consumer is stalled
     */
    public boolean checkConsumerProgress() {
        AtomicBoolean isHealthy = new AtomicBoolean(true);

        containers.forEach(container -> {
            String groupId = container.getContainerProperties().getGroupId();
            if (groupId == null) {
                log.trace("Container group id is null, skipping");
                return;
            }

            Collection<TopicPartition> assignedPartitions = container.getAssignedPartitions();
            if (assignedPartitions == null || assignedPartitions.isEmpty()) {
                log.trace("Consumer is not assigned to any topic partitions, skipping");
                return;
            }

            Set<TopicPartition> assigned = new HashSet<>(assignedPartitions);
            boolean paused = container.isContainerPaused() || container.isPauseRequested();

            if (paused) {
                log.trace("Consumer is paused on topic partitions: {}", assigned);
                return;
            }

            Map<TopicPartition, OffsetSpec> offsetSpecMap = new HashMap<>();
            for (TopicPartition partition : assigned) {
                offsetSpecMap.put(partition, OffsetSpec.latest());
            }

            ListOffsetsResult listOffsetsResult = adminClient.listOffsets(offsetSpecMap);
            Map<TopicPartition, ListOffsetsResult.ListOffsetsResultInfo> offsetResults;
            try {
                offsetResults = listOffsetsResult.all().get(adminTimeoutMs, TimeUnit.MILLISECONDS);
                if (offsetResults == null) {
                    log.error("No latest offsets found for topic partitions for group {}, skipping", groupId);
                    return;
                }
            } catch (InterruptedException e) {
                log.error("Failed to retrieve latest offsets for topic partitions for group {}, error message = {}, skipping",
                        groupId, e.getMessage());
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                log.error("Failed to retrieve latest offsets for topic partitions for group {}, error message = {}, skipping",
                        groupId, e.getMessage());
                return;
            }

            ListConsumerGroupOffsetsResult groupOffsets = adminClient.listConsumerGroupOffsets(groupId);
            KafkaFuture<Map<TopicPartition, OffsetAndMetadata>> partitionsOffsetFuture = groupOffsets.partitionsToOffsetAndMetadata();
            Map<TopicPartition, OffsetAndMetadata> currentlyCommitted;
            try {
                currentlyCommitted = partitionsOffsetFuture.get(adminTimeoutMs, TimeUnit.MILLISECONDS);
                if (currentlyCommitted == null) {
                    log.error("Currently committed offsets are null for group {}, skipping", groupId);
                    return;
                }
            } catch (InterruptedException e) {
                log.error("Failed to retrieve consumer group offsets for group {}, error message = {}, skipping",
                        groupId, e.getMessage());
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                log.error("Failed to retrieve consumer group offsets for group {}, error message = {}, skipping",
                        groupId, e.getMessage());
                return;
            }

            Map<TopicPartition, OffsetAndMetadata> previousOffset = groupsTopicPartitionOffsets
                    .computeIfAbsent(groupId, k -> new HashMap<>());

            for (TopicPartition partition : assigned) {
                OffsetAndMetadata currentOffsetAndMetadata = currentlyCommitted.get(partition);
                OffsetAndMetadata previousOffsetAndMetadata = previousOffset.get(partition);
                ListOffsetsResult.ListOffsetsResultInfo listOffsetsResultInfo = offsetResults.get(partition);

                if (listOffsetsResultInfo == null) {
                    log.error("No latest offset found for topic partition {} for groupId = {}", partition, groupId);
                    continue;
                }

                long latestOffsetForPartition = listOffsetsResultInfo.offset();

                if (latestOffsetForPartition <= 0) {
                    log.trace("No latest offset found for topic partition {}", partition);
                    continue;
                }

                long currentOffset = (currentOffsetAndMetadata != null) ? currentOffsetAndMetadata.offset() : 0L;
                OffsetAndMetadata effectiveCurrent = (currentOffsetAndMetadata != null)
                        ? currentOffsetAndMetadata
                        : new OffsetAndMetadata(0L);

                if (previousOffsetAndMetadata == null) {
                    log.trace("No previous offset found for topic partition {}", partition);
                    previousOffset.put(partition, effectiveCurrent);
                    continue;
                }

                if (currentOffset >= latestOffsetForPartition) {
                    log.trace("Consumer group {} has reached the end of topic partition {}. Current offset: {}, latest offset: {}",
                            groupId, partition, currentOffset, latestOffsetForPartition);
                    stalledChecksCount.remove(partition);
                    continue;
                }

                if (previousOffsetAndMetadata.offset() >= currentOffset) {
                    int stalledCount = stalledChecksCount.getOrDefault(partition, 0) + 1;
                    if (stalledCount >= maxStalledChecks) {
                        log.error("Consumer group {} has not progressed on topic partition {} for {} consecutive checks. Previous offset: {}, current offset: {}",
                                groupId, partition, stalledCount, previousOffsetAndMetadata.offset(), currentOffset);
                        AvailabilityChangeEvent.publish(applicationContext, LivenessState.BROKEN);
                        isHealthy.set(false);
                    } else {
                        log.warn("Consumer group {} has not progressed on topic partition {} since last check. Attempt {} of {}. Previous offset: {}, current offset: {}",
                                groupId, partition, stalledCount, maxStalledChecks, previousOffsetAndMetadata.offset(), currentOffset);
                        stalledChecksCount.put(partition, stalledCount);
                    }
                } else {
                    log.trace("Consumer group {} has progressed on topic partition {}. Previous offset: {}, current offset: {}",
                            groupId, partition, previousOffsetAndMetadata.offset(), currentOffset);
                    previousOffset.put(partition, effectiveCurrent);
                    stalledChecksCount.remove(partition);
                }
            }
        });

        return isHealthy.get();
    }

    /**
     * Checks if the containers collection is empty.
     *
     * @return true if the containers collection is empty; false otherwise
     */
    public boolean isConsumersEmpty() {
        return containers.isEmpty();
    }

    /**
     * Retrieves the size of the containers collection.
     *
     * @return the number of containers in the collection
     */
    public int getConsumersSize() {
        return containers.size();
    }

    /**
     * Handles the shutdown process of the CommittedOffsetMovementCheck component by closing AdminClient.
     */
    @PreDestroy
    public void shutdown() {
        log.info("Shutting down CommittedOffsetMovementCheck");
        if (adminClient != null) {
            log.info("Closing AdminClient");
            adminClient.close();
            log.info("AdminClient closed");
        }
        log.info("CommittedOffsetMovementCheck shutdown complete");
    }

}