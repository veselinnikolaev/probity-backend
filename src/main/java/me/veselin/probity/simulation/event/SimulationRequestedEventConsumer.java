package me.veselin.probity.simulation.event;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.veselin.probity.common.domain.event.SimulationRequestedEvent;
import me.veselin.probity.simulation.port.SimulationPort;
import me.veselin.probity.simulation.persistence.SimulationRepository;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * Kafka consumer for simulation requested events.
 * Uses DB-based claim (conditional UPDATE) for idempotency instead of Redis.
 * Handles retries and dead-letter queue forwarding.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SimulationRequestedEventConsumer {

    private final SimulationPort simulationPort;
    private final SimulationRepository simulationRepository;

    /**
     * Consumes simulation requested events from Kafka.
     * <p>
     * Idempotency is enforced via DB conditional UPDATE:
     * - Attempts to atomically claim a PENDING (or stale PROCESSING) row.
     * - If claim fails (already COMPLETED/FAILED or fresh PROCESSING), ack and skip.
     * <p>
     * On success: status → COMPLETED via simulationRepository.complete().
     * On failure: status → FAILED via simulationRepository.updateStatus(), rethrow for retry/DLT.
     * After max retries: message forwarded to DLT topic.
     */
    @KafkaListener(
            topics = "simulation-requested",
            groupId = "probity-simulation-worker",
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void consume(
            @Payload SimulationRequestedEvent event,
            @Header(KafkaHeaders.RECEIVED_KEY) String kafkaKey,
            @Header(KafkaHeaders.RECEIVED_PARTITION) int partition,
            @Header(KafkaHeaders.OFFSET) long offset,
            Acknowledgment ack) {

        log.info("Received simulation request event: simulationId={}, portfolioId={}, partition={}, offset={}",
                event.simulationId(), event.portfolioId(), partition, offset);

        // Try to claim the simulation for processing (PENDING or stale PROCESSING → PROCESSING)
        // 5-minute lease: if a worker died >5min ago, we reclaim the row
        boolean claimed = simulationRepository.claimForProcessing(event.simulationId(), Duration.ofMinutes(5));

        if (!claimed) {
            // Row not claimable: either COMPLETED, FAILED, or fresh PROCESSING (another worker)
            log.info("Simulation {} not claimable (already COMPLETED/FAILED or being processed), skipping", event.simulationId());
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

            log.info("Simulation completed successfully: simulationId={}", event.simulationId());
            ack.acknowledge();

        } catch (Exception e) {
            log.error("Simulation failed: simulationId={}, error={}", event.simulationId(), e.getMessage(), e);

            // Rethrow to trigger Kafka retry/DLT mechanism
            // The runAsync method already marks FAILED in DB
            throw e;
        }
    }
}