package io.github.vfem.livenesscheck.spring.kafka;


import org.springframework.boot.actuate.availability.LivenessStateHealthIndicator;
import org.springframework.boot.autoconfigure.condition.AllNestedConditions;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaOperations;

class LivenessCheckerCondition extends AllNestedConditions {

    public LivenessCheckerCondition() {
        super(ConfigurationPhase.PARSE_CONFIGURATION);
    }

    @ConditionalOnClass(KafkaOperations.class)
    static class SpringKafkaInClassPath {}

    @ConditionalOnClass(LivenessStateHealthIndicator.class)
    static class SpringActuatorInClassPath {}

    @ConditionalOnProperty(value = "management.endpoint.health.probes.enabled", havingValue = "true")
    static class HealthProbesAreEnabled {}

    @ConditionalOnProperty(value = "management.health.livenessstate.enabled", havingValue = "true")
    static class LivenessChecksAreEnabled {}
}
