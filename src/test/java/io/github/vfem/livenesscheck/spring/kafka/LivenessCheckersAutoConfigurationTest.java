package io.github.vfem.livenesscheck.spring.kafka;

import org.apache.kafka.clients.admin.AdminClientConfig;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaAdmin;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class LivenessCheckersAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(LivenessCheckersAutoConfiguration.class))
            .withUserConfiguration(TestKafkaAdminConfig.class);

    @Configuration
    static class TestKafkaAdminConfig {
        @Bean
        public KafkaAdmin kafkaAdmin() {
            Map<String, Object> configs = new HashMap<>();
            configs.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:9092");
            return new KafkaAdmin(configs);
        }
    }

    @Test
    void activatesWhenBothPropertiesEnabled() {
        contextRunner
                .withPropertyValues(
                        "management.endpoint.health.probes.enabled=true",
                        "management.health.livenessstate.enabled=true"
                )
                .run(context -> {
                    assertThat(context).hasSingleBean(CommittedOffsetMovementCheck.class);
                });
    }

    @Test
    void doesNotActivateWhenProbesDisabled() {
        contextRunner
                .withPropertyValues(
                        "management.endpoint.health.probes.enabled=false",
                        "management.health.livenessstate.enabled=true"
                )
                .run(context -> {
                    assertThat(context).doesNotHaveBean(CommittedOffsetMovementCheck.class);
                });
    }

    @Test
    void doesNotActivateWhenLivenessStateDisabled() {
        contextRunner
                .withPropertyValues(
                        "management.endpoint.health.probes.enabled=true",
                        "management.health.livenessstate.enabled=false"
                )
                .run(context -> {
                    assertThat(context).doesNotHaveBean(CommittedOffsetMovementCheck.class);
                });
    }

    @Test
    void doesNotActivateWhenPropertiesMissing() {
        contextRunner.run(context -> {
            assertThat(context).doesNotHaveBean(CommittedOffsetMovementCheck.class);
        });
    }

    @Test
    void customAdminTimeoutConfigured() {
        contextRunner
                .withPropertyValues(
                        "management.endpoint.health.probes.enabled=true",
                        "management.health.livenessstate.enabled=true",
                        "liveness.kafka.admin-timeout-ms=8500"
                )
                .run(context -> {
                    assertThat(context).hasSingleBean(CommittedOffsetMovementCheck.class);
                    CommittedOffsetMovementCheck check = context.getBean(CommittedOffsetMovementCheck.class);
                    Field timeoutField = CommittedOffsetMovementCheck.class.getDeclaredField("adminTimeoutMs");
                    timeoutField.setAccessible(true);
                    assertThat(timeoutField.get(check)).isEqualTo(8500L);
                });
    }
}
