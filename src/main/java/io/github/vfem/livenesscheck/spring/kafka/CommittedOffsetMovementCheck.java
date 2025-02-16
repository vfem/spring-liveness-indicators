package io.github.vfem.livenesscheck.spring.kafka;

import jakarta.annotation.PreDestroy;
import org.apache.commons.lang3.Validate;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.KafkaAdminClient;
import org.apache.kafka.clients.admin.ListConsumerGroupOffsetsResult;
import org.apache.kafka.clients.admin.ListOffsetsResult;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.KafkaFuture;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * CommittedOffsetMovementCheck is a utility class designed to monitor the progress of
 * committed consumer offsets in a Kafka system.
 * It periodically checks the consumer
 * offsets to ensure they are progressing, indicating that the consumers are working
 * as expected.
 * If the offsets are not progressing, it publishes a liveness event
 * indicating a broken state.
 */
public final class CommittedOffsetMovementCheck {

    private static final Logger log = LoggerFactory.getLogger(CommittedOffsetMovementCheck.class);

    private final boolean scheduled;
    private final long checkPeriodSec;
    private final long checkInitialDelaySec;
    private final ApplicationContext applicationContext;
    private final Map<String, Map<TopicPartition, OffsetAndMetadata>> groupsTopicPartitionOffsets = new HashMap<>();
    private final Set<KafkaConsumer<?, ?>> consumers = new HashSet<>();
    private final AdminClient adminClient;


    private final ScheduledExecutorService scheduledExecutor = Executors.newSingleThreadScheduledExecutor();

    /**
     * Constructor for CommittedOffsetMovementCheck.
     *
     * @param scheduled            the flag which indicates that the check will be scheduled
     * @param checkInitialDelaySec the initial delay before the first check in seconds
     * @param checkPeriodSec       the period between checks in seconds
     * @param applicationContext   the Spring application context
     * @param kafkaAdminConfig     the Kafka Configuration
     */
    public CommittedOffsetMovementCheck(boolean scheduled,
                                        long checkInitialDelaySec,
                                        long checkPeriodSec,
                                        ApplicationContext applicationContext,
                                        Map<String, Object> kafkaAdminConfig) {
        Validate.notNull(applicationContext, "ApplicationContext is null");
        Validate.isTrue(checkInitialDelaySec > 0, "checkInitialDelaySec must be greater than 0 seconds");
        Validate.isTrue(checkPeriodSec > 0, "checkPeriodSec must be greater than 0 seconds");

        this.scheduled = scheduled;
        this.checkInitialDelaySec = checkInitialDelaySec;
        this.checkPeriodSec = checkPeriodSec;
        this.applicationContext = applicationContext;
        this.adminClient = KafkaAdminClient.create(kafkaAdminConfig);
    }

    /**
     * Initializes the check by extracting Kafka consumers from the listener containers
     * and scheduling the periodic offset check.
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

        if (scheduled) {
            scheduledExecutor.scheduleWithFixedDelay(this::checkConsumerProgress, checkInitialDelaySec, checkPeriodSec, TimeUnit.SECONDS);
            log.info("Committed offset movement check scheduled with initial delay {} seconds and period {} seconds",
                    checkInitialDelaySec, checkPeriodSec);
        }
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
     * Checks the progress of the committed offsets for each consumer.
     * If the offsets have not progressed, it publishes a liveness event indicating a broken state.
     */
    void checkConsumerProgress() {

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

            Map<TopicPartition, OffsetSpec> offsetSpecMap = new HashMap<>();

            for (TopicPartition partition : assigned) {
                offsetSpecMap.put(partition, OffsetSpec.latest());
            }

            ListOffsetsResult listOffsetsResult = adminClient.listOffsets(offsetSpecMap);
            Map<TopicPartition, ListOffsetsResult.ListOffsetsResultInfo> offsetResults;
            try {
                offsetResults = listOffsetsResult.all().get();
                if (offsetResults == null) {
                    log.error("No latest offsets found for topic partitions for group {}, skipping", groupId);
                    return;
                }
            } catch (Exception e) {
                log.error("Failed to retrieve latest offsets for topic partitions for group {}, error message = {}, skipping",
                        groupId, e.getMessage());
                return;
            }

            ListConsumerGroupOffsetsResult groupOffsets = adminClient.listConsumerGroupOffsets(groupId);

            KafkaFuture<Map<TopicPartition, OffsetAndMetadata>> partitionsOffsetFuture = groupOffsets.partitionsToOffsetAndMetadata();
            Map<TopicPartition, OffsetAndMetadata> currentlyCommitted;
            try {
                currentlyCommitted = partitionsOffsetFuture.get(10, TimeUnit.SECONDS);
                if (currentlyCommitted == null) {
                    log.error("Currently committed offsets are null for group {}, skipping", groupId);
                    return;
                }
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
                    return;
                }

                long latestOffsetForPartition = listOffsetsResultInfo.offset();

                if (latestOffsetForPartition <= 0) {
                    log.trace("No latest offset found for topic partition {}", partition);
                    continue;
                }

                if (previousOffsetAndMetadata == null) {
                    log.trace("No previous offset found for topic partition {}", partition);
                    previousOffset.put(
                            partition,
                            currentOffsetAndMetadata == null ? new OffsetAndMetadata(0) : currentOffsetAndMetadata
                    );
                    continue;
                }

                if (currentOffsetAndMetadata == null) {
                    log.info("No current offset found for topic partition {}", partition);
                    return;
                }

                if (currentOffsetAndMetadata.offset() >= latestOffsetForPartition) {
                    log.trace("Consumer group {} has reached the end of topic partition {}. Current offset: {}, latest offset: {}",
                            consumer.groupMetadata().groupId(), partition, currentOffsetAndMetadata.offset(), latestOffsetForPartition);
                    continue;
                }

                if (previousOffsetAndMetadata.offset() >= currentOffsetAndMetadata.offset()) {
                    log.error("Consumer group {} has not progressed on topic partition {} since last check. Previous offset: {}, current offset: {}",
                            consumer.groupMetadata().groupId(), partition, previousOffsetAndMetadata.offset(), currentOffsetAndMetadata.offset());
                    AvailabilityChangeEvent.publish(applicationContext, LivenessState.BROKEN);
                } else {
                    log.trace("Consumer group {} has progressed on topic partition {}. Previous offset: {}, current offset: {}",
                            consumer.groupMetadata().groupId(), partition, previousOffsetAndMetadata.offset(), currentOffsetAndMetadata.offset());
                    previousOffset.put(partition, currentOffsetAndMetadata);
                }
            }
        });
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
     * Checks if the consumers collection is empty.
     *
     * @return true if the consumers collection is empty; false otherwise
     */
    public boolean isConsumersEmpty() {
        return consumers.isEmpty();
    }

    /**
     * Retrieves the size of the consumers collection.
     *
     * @return the number of consumers in the collection
     */
    public int getConsumersSize() {
        return consumers.size();
    }

    /**
     * Handles the shutdown process of the CommittedOffsetMovementCheck component.
     * <p>
     * This method is executed during the destruction of the bean. It ensures a clean shutdown
     * of the internal executor service used for scheduled tasks as well as the Kafka AdminClient.
     * <p>
     * Specifically, the method performs the following steps:
     * - Logs the initiation of the shutdown process.
     * - Shuts down the scheduled executor service if it is still running.
     * - Waits up to 5 seconds for the executor to terminate gracefully.
     * - If termination does not occur within the wait period, a forced shutdown is initiated.
     * - Handles interruptions during the waiting period, forcibly shutting down the executor
     * and reasserting the thread's interrupt status.
     * - Closes the Kafka AdminClient, if it is not null, to release any associated resources.
     * - Logs the completion of the shutdown process.
     */
    @PreDestroy
    public void shutdown() {
        log.info("Shutting down CommittedOffsetMovementCheck");
        if (!scheduledExecutor.isShutdown()) {
            scheduledExecutor.shutdown();
            try {
                log.info("Waiting for CommittedOffsetMovementCheck to terminate");
                if (!scheduledExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                    log.info("Forcing shutdown of CommittedOffsetMovementCheck");
                    scheduledExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                log.error("Interrupted while waiting for CommittedOffsetMovementCheck to terminate," +
                        " forcing shutdown of CommittedOffsetMovementCheck", e);
                scheduledExecutor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
        if (adminClient != null) {
            log.info("Closing AdminClient");
            adminClient.close();
            log.info("AdminClient closed");
        }
        log.info("CommittedOffsetMovementCheck shutdown complete");
    }

}