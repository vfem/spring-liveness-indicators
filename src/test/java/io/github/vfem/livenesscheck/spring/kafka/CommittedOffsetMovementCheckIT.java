package io.github.vfem.livenesscheck.spring.kafka;

import io.github.vfem.livenesscheck.spring.kafka.config.BaseConfig;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;
import org.springframework.boot.availability.ApplicationAvailability;
import org.springframework.boot.availability.LivenessState;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.kafka.config.KafkaListenerConfigUtils;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

import java.util.Collection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@SpringBootTest(classes = BaseConfig.class)
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_EACH_TEST_METHOD)
@EmbeddedKafka(partitions = 4,
        topics = {"classTopic", "methodTopic1", "methodTopic2", "slowMethodTopic"})
class CommittedOffsetMovementCheckIT {

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private ApplicationAvailability applicationAvailability;

    @Autowired
    private CommittedOffsetMovementCheck committedOffsetMovementCheck;

    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;

    @Test
    void initExtractsKafkaConsumers() {
        // Verify that the consumers are extracted
        assertFalse(committedOffsetMovementCheck.isConsumersEmpty());
        // Verify num of consumers
        assertEquals(5, committedOffsetMovementCheck.getConsumersSize());
    }

    @Test
    void healthIndicatorContainsDetails() {
        Health health = committedOffsetMovementCheck.health();
        assertNotNull(health);
        assertEquals(Status.UP, health.getStatus());
        assertEquals(5, health.getDetails().get("trackedContainers"));
    }

    @Test
    void doesntFailWhenTopicCompletelyConsumed() throws InterruptedException {
        kafkaTemplate.send(
                MessageBuilder.withPayload("test_payload")
                        .setHeader(KafkaHeaders.TOPIC, "classTopic")
                        .setHeader(KafkaHeaders.MESSAGE_KEY, "test_key")
                        .build());
        kafkaTemplate.send(
                MessageBuilder.withPayload("test_payload2")
                        .setHeader(KafkaHeaders.TOPIC, "classTopic")
                        .setHeader(KafkaHeaders.MESSAGE_KEY, "test_key2")
                        .build());
        kafkaTemplate.send(
                MessageBuilder.withPayload("test_payload3")
                        .setHeader(KafkaHeaders.TOPIC, "classTopic")
                        .setHeader(KafkaHeaders.MESSAGE_KEY, "test_key3")
                        .build());
        kafkaTemplate.flush();
        Thread.sleep(5 * 1000 + 1000);

        Health health = committedOffsetMovementCheck.health();
        assertEquals(Status.UP, health.getStatus());
        Assertions.assertEquals(LivenessState.CORRECT, applicationAvailability.getLivenessState());

        Thread.sleep(2 * 1000 + 1000);
        Health health2 = committedOffsetMovementCheck.health();
        assertEquals(Status.UP, health2.getStatus());
        Assertions.assertEquals(LivenessState.CORRECT, applicationAvailability.getLivenessState());
    }

    @Test
    void doesntFailWhenTopicIsEmpty() throws InterruptedException {
        Health health = committedOffsetMovementCheck.health();
        assertEquals(Status.UP, health.getStatus());
        LivenessState livenessState = applicationAvailability.getLivenessState();
        Assertions.assertEquals(LivenessState.CORRECT, livenessState);

        Thread.sleep(2 * 1000 + 1000);
        Health health2 = committedOffsetMovementCheck.health();
        assertEquals(Status.UP, health2.getStatus());
        livenessState = applicationAvailability.getLivenessState();
        Assertions.assertEquals(LivenessState.CORRECT, livenessState);
    }

    @Test
    void failsLivenessIfNoProgress() throws InterruptedException {
        //given
        assertEquals(LivenessState.CORRECT, applicationAvailability.getLivenessState());

        kafkaTemplate.send(
                MessageBuilder.withPayload("test_payload")
                        .setHeader(KafkaHeaders.TOPIC, "slowMethodTopic")
                        .setHeader(KafkaHeaders.MESSAGE_KEY, "test_key")
                        .build());
        kafkaTemplate.flush();

        //when
        //first check records baseline
        Health initialHealth = committedOffsetMovementCheck.health();
        assertEquals(Status.UP, initialHealth.getStatus());

        Thread.sleep(1000);

        //second check detects stalled consumer, attempt 1 (still UP)
        Health secondHealth = committedOffsetMovementCheck.health();
        assertEquals(Status.UP, secondHealth.getStatus());

        //third check detects stalled consumer, attempt 2 (still UP)
        Health thirdHealth = committedOffsetMovementCheck.health();
        assertEquals(Status.UP, thirdHealth.getStatus());

        //fourth check detects stalled consumer, attempt 3 (reaches max-stalled-checks, marked DOWN)
        Health fourthHealth = committedOffsetMovementCheck.health();
        assertEquals(Status.DOWN, fourthHealth.getStatus());

        //then
        assertEquals(LivenessState.BROKEN, applicationAvailability.getLivenessState());
    }

    @Test
    void doesntFailForPausedConsumer() throws InterruptedException {
        KafkaListenerEndpointRegistry registry = applicationContext.getBean(
                KafkaListenerConfigUtils.KAFKA_LISTENER_ENDPOINT_REGISTRY_BEAN_NAME,
                KafkaListenerEndpointRegistry.class
        );

        Collection<MessageListenerContainer> containers = registry.getAllListenerContainers();

        for (MessageListenerContainer container : containers) {
            container.pause();
        }
        kafkaTemplate.send(
                MessageBuilder.withPayload("test_payload")
                        .setHeader(KafkaHeaders.TOPIC, "classTopic")
                        .setHeader(KafkaHeaders.MESSAGE_KEY, "test_key")
                        .build());
        kafkaTemplate.send(
                MessageBuilder.withPayload("test_payload2")
                        .setHeader(KafkaHeaders.TOPIC, "classTopic")
                        .setHeader(KafkaHeaders.MESSAGE_KEY, "test_key2")
                        .build());
        kafkaTemplate.send(
                MessageBuilder.withPayload("test_payload3")
                        .setHeader(KafkaHeaders.TOPIC, "classTopic")
                        .setHeader(KafkaHeaders.MESSAGE_KEY, "test_key3")
                        .build());
        kafkaTemplate.flush();
        Thread.sleep(2000);

        Health health = committedOffsetMovementCheck.health();
        assertEquals(Status.UP, health.getStatus());
        Assertions.assertEquals(LivenessState.CORRECT, applicationAvailability.getLivenessState());

        Thread.sleep(2 * 1000 + 1000);
        Health health2 = committedOffsetMovementCheck.health();
        assertEquals(Status.UP, health2.getStatus());
        Assertions.assertEquals(LivenessState.CORRECT, applicationAvailability.getLivenessState());
    }

    @Test
    void multipleTopicsConsumedSuccessfully() throws InterruptedException {
        kafkaTemplate.send(
                MessageBuilder.withPayload("payload1")
                        .setHeader(KafkaHeaders.TOPIC, "methodTopic1")
                        .setHeader(KafkaHeaders.MESSAGE_KEY, "k1")
                        .build());
        kafkaTemplate.send(
                MessageBuilder.withPayload("payload2")
                        .setHeader(KafkaHeaders.TOPIC, "methodTopic2")
                        .setHeader(KafkaHeaders.MESSAGE_KEY, "k2")
                        .build());
        kafkaTemplate.flush();

        Thread.sleep(4000);

        Health health = committedOffsetMovementCheck.health();
        assertEquals(Status.UP, health.getStatus());
        assertEquals(LivenessState.CORRECT, applicationAvailability.getLivenessState());
    }

}