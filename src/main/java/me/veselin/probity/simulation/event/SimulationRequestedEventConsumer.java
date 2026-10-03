package me.veselin.probity.simulation.event;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.veselin.probity.bff.security.filter.idempotency.IdempotencyProperties;
import me.veselin.probity.bff.security.filter.idempotency.IdempotencyRecord;
import me.veselin.probity.bff.security.filter.idempotency.IdempotencyStatus;
import me.veselin.probity.common.domain.event.SimulationRequestedEvent;
import me.veselin.probity.simulation.port.SimulationPort;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Kafka consumer for simulation requested events.
 * Handles idempotency, retries, and dead-letter queue forwarding.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SimulationRequestedEventConsumer {

    private final SimulationPort simulationPort;
    private final IdempotencyProperties idempotencyProperties;
    private final RedisIdempotencyService redisIdempotencyService;

    /**
     * Consumes simulation requested events from Kafka.
     * <p>
     * Idempotency is enforced via Redis: the consumer attempts to atomically transition
     * the idempotency key from PENDING to PROCESSING. If the key is already PROCESSING or COMPLETED,
     * the message is acknowledged and skipped (duplicate/redelivery).
     * <p>
     * On success: status → COMPLETED, idempotency key → COMPLETED.
     * On failure: status → FAILED, idempotency key deleted (allows retry via DLT).
     * After max retries: message forwarded to DLT topic.
     */
    @KafkaListener(
            topics = "simulation-requested",
            groupId = "probity-simulation-worker",
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void consume(
            @Payload SimulationRequestedEvent event,
            @Header(KafkaHeaders.RECEIVED_KEY) String idempotencyKey,
            @Header(KafkaHeaders.RECEIVED_PARTITION) int partition,
            @Header(KafkaHeaders.OFFSET) long offset,
            Acknowledgment ack) {

        log.info("Received simulation request event: simulationId={}, portfolioId={}, partition={}, offset={}",
                event.simulationId(), event.portfolioId(), partition, offset);

        // Build Redis storage key using configured prefix
        String storageKey = idempotencyProperties.getKeyPrefix() + event.userId() + ":" + idempotencyKey;

        // Check current status in Redis
        IdempotencyRecord existingRecord = redisIdempotencyService.getRecord(storageKey);

        if (existingRecord != null) {
            if (existingRecord.status() == IdempotencyStatus.PROCESSING) {
                log.warn("Simulation already being processed (PROCESSING), skipping duplicate: simulationId={}", event.simulationId());
                ack.acknowledge();
                return;
            } else if (existingRecord.status() == IdempotencyStatus.COMPLETED) {
                log.info("Simulation already completed, skipping duplicate: simulationId={}", event.simulationId());
                ack.acknowledge();
                return;
            }
            // If PENDING, we proceed to process
        }

        // Atomically set PROCESSING status
        boolean lockAcquired = redisIdempotencyService.setProcessingIfPending(storageKey);

        if (!lockAcquired) {
            log.warn("Failed to acquire PROCESSING lock for simulationId={}, another worker may have picked it up", event.simulationId());
            ack.acknowledge();
            return;
        }

        try {
            // Execute the simulation using the pre-created simulationId and marketDataSnapshot
            simulationPort.executeAsync(
                    event.simulationId(),
                    event.portfolioId(),
                    event.numberOfSimulations(),
                    event.timeHorizonDays(),
                    event.confidenceLevel(),
                    event.assumedReturnPercent(),
                    event.assumedVolatilityPercent(),
                    event.userId(),
                    event.marketDataSnapshot()
            );

            // On success: mark COMPLETED in Redis
            redisIdempotencyService.setCompleted(storageKey, 200, "{}");
            log.info("Simulation completed successfully: simulationId={}", event.simulationId());

            ack.acknowledge();

        } catch (Exception e) {
            log.error("Simulation failed: simulationId={}, error={}", event.simulationId(), e.getMessage(), e);

            // On failure: delete Redis key to allow retry via DLT/redelivery
            redisIdempotencyService.deleteKey(storageKey);

            // Re-throw to trigger Kafka retry/DLT mechanism
            throw e;
        }
    }
}