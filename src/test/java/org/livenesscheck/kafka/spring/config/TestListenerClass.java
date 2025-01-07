package org.livenesscheck.kafka.spring.config;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaHandler;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
@KafkaListener(id = "classTest", groupId = "classGroup", topics = "classTopic")
public class TestListenerClass {

    private static final Logger log = LoggerFactory.getLogger(TestListenerClass.class);

    @KafkaHandler(isDefault = true)
    public void listen(ConsumerRecord<String, String> consumerRecord) {
        log.info("Received Kafka Record from topic '{}' - key '{}', payload '{}'", consumerRecord.topic(), consumerRecord.key(), consumerRecord.value());
    }

}
