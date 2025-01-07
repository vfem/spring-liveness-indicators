package org.livenesscheck.kafka.spring;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.livenesscheck.kafka.spring.config.BaseConfig;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
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
import org.springframework.test.context.junit.jupiter.SpringExtension;

import java.util.Collection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

@SpringBootTest(classes = BaseConfig.class)
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_EACH_TEST_METHOD)
@ExtendWith(SpringExtension.class)
@EmbeddedKafka(partitions = 4,
        topics = {"classTopic", "methodTopic1", "methodTopic2", "slowMethodTopic"}, ports = 9092)
class CommittedOffsetMovementCheckIT {

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private ApplicationAvailability applicationAvailability;

    @Autowired
    private CommittedOffsetMovementCheck committedOffsetMovementCheck;

    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;

    @Value("${liveness.kafka.check-period-sec}")
    private long checkPeriodSec;

    @Test
    void initExtractsKafkaConsumers() {
        // Verify that the consumers are extracted
        assertFalse(committedOffsetMovementCheck.isConsumersEmpty());
        // Verify num of consumers
        assertEquals(5, committedOffsetMovementCheck.getConsumersSize());
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
                MessageBuilder.withPayload("test_payload2")
                        .setHeader(KafkaHeaders.TOPIC, "classTopic")
                        .setHeader(KafkaHeaders.MESSAGE_KEY, "test_key2")
                        .build());
        kafkaTemplate.flush();
        Thread.sleep(5 * 1000 + 1000);
        committedOffsetMovementCheck.checkConsumerProgress();
        Assertions.assertEquals(LivenessState.CORRECT, applicationAvailability.getLivenessState());

        Thread.sleep(2 * 1000 + 1000);
        committedOffsetMovementCheck.checkConsumerProgress();
        Assertions.assertEquals(LivenessState.CORRECT, applicationAvailability.getLivenessState());
    }

    @Test
    void doesntFailWhenTopicIsEmpty() throws InterruptedException {
        committedOffsetMovementCheck.checkConsumerProgress();
        LivenessState livenessState = applicationAvailability.getLivenessState();
        Assertions.assertEquals(LivenessState.CORRECT, livenessState);

        Thread.sleep(2 * 1000 + 1000);
        committedOffsetMovementCheck.checkConsumerProgress();
        livenessState = applicationAvailability.getLivenessState();
        Assertions.assertEquals(LivenessState.CORRECT, livenessState);
    }

    @Test
    void failsLivenessIfNoProgress() throws InterruptedException {
        //todo fix this test
        //given
        assertEquals(LivenessState.CORRECT, applicationAvailability.getLivenessState());

        kafkaTemplate.send(
                MessageBuilder.withPayload("test_payload")
                        .setHeader(KafkaHeaders.TOPIC, "slowMethodTopic")
                        .setHeader(KafkaHeaders.MESSAGE_KEY, "test_key")
                        .build());
        kafkaTemplate.flush();

        //when
        //no messages were sent to slow processing topic
        committedOffsetMovementCheck.checkConsumerProgress();
        Thread.sleep(2000);
        committedOffsetMovementCheck.checkConsumerProgress();

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
                MessageBuilder.withPayload("test_payload2")
                        .setHeader(KafkaHeaders.TOPIC, "classTopic")
                        .setHeader(KafkaHeaders.MESSAGE_KEY, "test_key2")
                        .build());
        kafkaTemplate.flush();
        Thread.sleep(2_000);
        committedOffsetMovementCheck.checkConsumerProgress();
        Assertions.assertEquals(LivenessState.CORRECT, applicationAvailability.getLivenessState());

        Thread.sleep(2 * 1000 + 1000);
        committedOffsetMovementCheck.checkConsumerProgress();
        Assertions.assertEquals(LivenessState.CORRECT, applicationAvailability.getLivenessState());
    }

}