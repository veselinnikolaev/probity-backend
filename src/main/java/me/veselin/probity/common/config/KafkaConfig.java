package me.veselin.probity.common.config;

import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/**
 * Kafka topic configuration for simulation events.
 * Defines the main topic and dead-letter topic.
 */
@Configuration
@Slf4j
public class KafkaConfig {

    @Value("${spring.kafka.consumer.group-id:probity-simulation-worker}")
    private String consumerGroupId;

    /**
     * Main topic for simulation requested events.
     * Single partition for ordering guarantee per idempotency key.
     */
    @Bean
    public NewTopic simulationRequestedTopic() {
        return TopicBuilder.name("simulation-requested")
                .partitions(1)
                .replicas(1)
                .build();
    }

    /**
     * Dead-letter topic for failed simulation requested events.
     * Retains failed messages for manual inspection and replay.
     */
    @Bean
    public NewTopic simulationRequestedDltTopic() {
        return TopicBuilder.name("simulation-requested.DLT")
                .partitions(1)
                .replicas(1)
                .config("cleanup.policy", "compact")
                .config("retention.ms", "604800000") // 7 days
                .build();
    }
}