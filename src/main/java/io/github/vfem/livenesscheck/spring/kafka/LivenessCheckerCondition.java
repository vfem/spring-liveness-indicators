package io.github.vfem.livenesscheck.spring.kafka;


import org.springframework.boot.actuate.availability.LivenessStateHealthIndicator;
import org.springframework.boot.autoconfigure.condition.AllNestedConditions;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaOperations;

/**
 * A condition that verifies the required dependencies and properties are available and enabled
 * to perform liveness checks.
 * This class extends {@link AllNestedConditions}, meaning all nested
 * conditions must match for this condition to be satisfied.
 * <p>
 * The condition checks for:
 * 1. The presence of KafkaOperations class in the classpath.
 * 2. The presence of LivenessStateHealthIndicator class in the classpath.
 * 3. The "management.endpoint.health.probes.enabled" property being set to "true".
 * 4. The "management.health.livenessstate.enabled" property being set to "true".
 * <p>
 * This condition is evaluated during the parse configuration phase.
 */
class LivenessCheckerCondition extends AllNestedConditions {

    public LivenessCheckerCondition() {
        super(ConfigurationPhase.PARSE_CONFIGURATION);
    }

    @ConditionalOnClass(KafkaOperations.class)
    static class SpringKafkaInClassPath {
    }

    @ConditionalOnClass(LivenessStateHealthIndicator.class)
    static class SpringActuatorInClassPath {
    }

    @ConditionalOnProperty(value = "management.endpoint.health.probes.enabled", havingValue = "true")
    static class HealthProbesAreEnabled {
    }

    @ConditionalOnProperty(value = "management.health.livenessstate.enabled", havingValue = "true")
    static class LivenessChecksAreEnabled {
    }
}
