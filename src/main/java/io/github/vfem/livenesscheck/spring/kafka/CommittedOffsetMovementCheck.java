package io.github.vfem.livenesscheck.spring.kafka;

import jakarta.annotation.PreDestroy;
import org.apache.commons.lang3.Validate;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.ListConsumerGroupOffsetsResult;
import org.apache.kafka.clients.admin.ListOffsetsResult;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.consumer.KafkaConsumer;
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
import org.springframework.kafka.listener.KafkaMessageListenerContainer;
import org.springframework.kafka.listener.MessageListenerContainer;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.HashMap;
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
    private final ApplicationContext applicationContext;
    private final Map<String, Map<TopicPartition, OffsetAndMetadata>> groupsTopicPartitionOffsets = new HashMap<>();
    private final Set<KafkaConsumer<?, ?>> consumers = new CopyOnWriteArraySet<>();
    private final AdminClient adminClient;

    /**
     * Constructor for CommittedOffsetMovementCheck.
     *
     * @param adminTimeoutMs     the timeout in milliseconds for AdminClient calls
     * @param applicationContext the Spring application context
     * @param kafkaAdminConfig   the Kafka Configuration
     */
    public CommittedOffsetMovementCheck(long adminTimeoutMs,
                                        ApplicationContext applicationContext,
                                        Map<String, Object> kafkaAdminConfig) {
        Validate.notNull(applicationContext, "ApplicationContext is null");
        Validate.isTrue(adminTimeoutMs > 0, "adminTimeoutMs must be greater than 0");

        this.adminTimeoutMs = adminTimeoutMs;
        this.applicationContext = applicationContext;
        this.adminClient = AdminClient.create(kafkaAdminConfig);
    }

    public CommittedOffsetMovementCheck(ApplicationContext applicationContext,
                                        Map<String, Object> kafkaAdminConfig) {
        this(5000L, applicationContext, kafkaAdminConfig);
    }

    /**
     * Initializes the check by extracting Kafka consumers from the listener containers.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void init() {
        KafkaListenerEndpointRegistry registry = applicationContext.getBean(
                KafkaListenerConfigUtils.KAFKA_LISTENER_ENDPOINT_REGISTRY_BEAN_NAME,
                KafkaListenerEndpointRegistry.class
        );

        Collection<MessageListenerContainer> containers = registry.getAllListenerContainers();

        for (MessageListenerContainer container : containers) {
            if (container instanceof ConcurrentMessageListenerContainer<?, ?> concurrentContainer) {
                List<? extends KafkaMessageListenerContainer<?, ?>> listenerContainers = concurrentContainer.getContainers();
                listenerContainers.forEach(
                        kafkaContainer -> consumers.add(extractKafkaConsumer(kafkaContainer))
                );
            }

            if (container instanceof KafkaMessageListenerContainer<?, ?> kafkaContainer) {
                KafkaConsumer<?, ?> kafkaConsumer = extractKafkaConsumer(kafkaContainer);
                consumers.add(kafkaConsumer);
            }
        }
        log.info("CommittedOffsetMovementCheck initialized with {} Kafka consumers", consumers.size());
    }

    /**
     * Extracts the KafkaConsumer instance from a KafkaMessageListenerContainer.
     *
     * @param kafkaContainer the KafkaMessageListenerContainer instance
     * @return the extracted KafkaConsumer instance
     */
    private KafkaConsumer<?, ?> extractKafkaConsumer(KafkaMessageListenerContainer<?, ?> kafkaContainer) {
        try {
            Field consumerField = KafkaMessageListenerContainer.class.getDeclaredField("listenerConsumer");
            consumerField.setAccessible(true);
            Object listenerConsumer = consumerField.get(kafkaContainer);

            Field consumerInnerField = listenerConsumer.getClass().getDeclaredField("consumer");
            consumerInnerField.setAccessible(true);
            return (KafkaConsumer<?, ?>) consumerInnerField.get(listenerConsumer);
        } catch (NoSuchFieldException | IllegalAccessException e) {
            log.error("Failed to extract KafkaConsumer from KafkaMessageListenerContainer", e);
            throw new RuntimeException(e);
        }
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
                    .withDetail("trackedConsumers", consumers.size())
                    .build();
        }
        return Health.down()
                .withDetail("reason", "One or more Kafka consumers stalled while unconsumed messages remain")
                .withDetail("trackedConsumers", consumers.size())
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

        consumers.forEach(consumer -> {
            if (consumer.groupMetadata() == null || consumer.groupMetadata().groupId() == null) {
                log.trace("Consumer group metadata is null, skipping");
                return;
            }

            String groupId = consumer.groupMetadata().groupId();

            Set<TopicPartition> assigned = extractAssigned(consumer);
            Set<TopicPartition> paused = extractPaused(consumer);

            if (assigned.isEmpty()) {
                log.trace("Consumer is not assigned to any topic partitions, skipping");
                return;
            }

            if (!paused.isEmpty()) {
                log.trace("Consumer is paused on topic partitions: {}", paused);
                assigned.removeAll(paused);
            }

            if (assigned.isEmpty()) {
                log.trace("All assigned partitions are paused for group {}, skipping", groupId);
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
                    continue;
                }

                if (previousOffsetAndMetadata.offset() >= currentOffset) {
                    log.error("Consumer group {} has not progressed on topic partition {} since last check. Previous offset: {}, current offset: {}",
                            groupId, partition, previousOffsetAndMetadata.offset(), currentOffset);
                    AvailabilityChangeEvent.publish(applicationContext, LivenessState.BROKEN);
                    isHealthy.set(false);
                } else {
                    log.trace("Consumer group {} has progressed on topic partition {}. Previous offset: {}, current offset: {}",
                            groupId, partition, previousOffsetAndMetadata.offset(), currentOffset);
                    previousOffset.put(partition, effectiveCurrent);
                }
            }
        });

        return isHealthy.get();
    }

    private Set<TopicPartition> extractAssigned(KafkaConsumer<?, ?> consumer) {
        try {
            Field subscriptionsField = KafkaConsumer.class.getDeclaredField("subscriptions");
            subscriptionsField.setAccessible(true);
            Object subscriptions = subscriptionsField.get(consumer);

            Method assignedPartitionsMethod = subscriptions.getClass().getDeclaredMethod("assignedPartitions");
            assignedPartitionsMethod.setAccessible(true);

            return (Set<TopicPartition>) assignedPartitionsMethod.invoke(subscriptions);
        } catch (NoSuchFieldException | NoSuchMethodException | InvocationTargetException | IllegalAccessException e) {
            log.error("Failed to extract assigned partitions from KafkaConsumer", e);
            throw new RuntimeException(e);
        }
    }

    private Set<TopicPartition> extractPaused(KafkaConsumer<?, ?> consumer) {
        try {
            Field subscriptionsField = KafkaConsumer.class.getDeclaredField("subscriptions");
            subscriptionsField.setAccessible(true);
            Object subscriptions = subscriptionsField.get(consumer);

            Method pausedPartitionsMethod = subscriptions.getClass().getDeclaredMethod("pausedPartitions");
            pausedPartitionsMethod.setAccessible(true);

            return (Set<TopicPartition>) pausedPartitionsMethod.invoke(subscriptions);
        } catch (NoSuchFieldException | NoSuchMethodException | InvocationTargetException | IllegalAccessException e) {
            log.error("Failed to extract assigned partitions from KafkaConsumer", e);
            throw new RuntimeException(e);
        }
    }

    /**
     * Checks if the consumers' collection is empty.
     *
     * @return true if the consumers' collection is empty; false otherwise
     */
    public boolean isConsumersEmpty() {
        return consumers.isEmpty();
    }

    /**
     * Retrieves the size of the consumers' collection.
     *
     * @return the number of consumers in the collection
     */
    public int getConsumersSize() {
        return consumers.size();
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