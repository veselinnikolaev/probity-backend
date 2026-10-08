package me.veselin.probity.simulation.event;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.veselin.probity.common.domain.event.SimulationRequestedEvent;
import me.veselin.probity.simulation.config.SimulationProperties;
import me.veselin.probity.simulation.domain.SimulationStatus;
import me.veselin.probity.simulation.exception.SimulationClaimLostException;
import me.veselin.probity.simulation.port.SimulationPort;
import me.veselin.probity.simulation.persistence.SimulationRepository;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Kafka consumer for simulation requested events.
 * <p>
 * Idempotency is enforced via DB conditional UPDATE with fence token (truncated to
 * microseconds) and state transitions per §3 of the design. Uses per-class consumer
 * groups via property placeholder {@code ${probity.simulation.consumer-group:probity-simulation-worker}}.
 * On success: status → COMPLETED via fenced complete. On failure: release PROCESSING → PENDING
 * (fenced) before rethrowing so the next delivery can reclaim; exhausted retries flow to DLT.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SimulationRequestedEventConsumer {

    /**
     * Redelivery delay for a record we decline to run yet.
     *
     * <p>Spring floors this at the container's poll timeout (5 s by default), so the value
     * only bites when it is the larger of the two. That is fine: the loop this drives is
     * bounded by the lease, not by this number — it terminates the moment {@code updated_at}
     * falls outside {@code claimLease} and the row becomes reclaimable. A long lease delays
     * recovery; it never prevents it.
     */
    private static final Duration NACK_BACKOFF = Duration.ofSeconds(1);

    private final SimulationPort simulationPort;
    private final SimulationRepository simulationRepository;
    private final SimulationProperties simulationProperties;

    @KafkaListener(
            topics = "${probity.kafka.simulation-requested-topic:simulation-requested}",
            groupId = "${probity.simulation.consumer-group:probity-simulation-worker}",
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

        UUID simulationId = event.simulationId();

        // The claim is taken here, not in the service: this is the only component that holds
        // the Kafka offset and the group identity, so it is the only one that can decide
        // whether this delivery is ours to run. It returns the fence token that every write
        // for this row must then present.
        Optional<Instant> fenceToken = simulationRepository
                .claimForProcessing(simulationId, simulationProperties.getClaimLease());

        if (fenceToken.isEmpty()) {
            handleUnclaimable(simulationId, partition, offset, ack);
            return;
        }

        Instant fence = fenceToken.get();
        try {
            // Execute the simulation using the pre-created simulationId and marketDataSnapshot
            simulationPort.executeAsync(
                    simulationId,
                    event.portfolioId(),
                    event.numberOfSimulations(),
                    event.timeHorizonDays(),
                    event.confidenceLevel(),
                    event.assumedReturnPercent(),
                    event.assumedVolatilityPercent(),
                    event.userId(),
                    event.marketDataSnapshot(),
                    fence
            );

            log.info("Simulation completed successfully: simulationId={}", simulationId);
            ack.acknowledge();

        } catch (SimulationClaimLostException lost) {
            // Our lease lapsed mid-run and a peer took the row. Nothing to release, nothing
            // to retry, nothing to publish — the peer is already doing the work. Acknowledge
            // so this copy stops bouncing.
            log.error("Simulation {} was reclaimed by a peer mid-run; stopping without side "
                    + "effects: {}", simulationId, lost.getMessage());
            ack.acknowledge();

        } catch (Exception e) {
            log.error("Simulation failed: simulationId={}, error={}", simulationId, e.getMessage(), e);

            // Release before rethrowing, and release *fenced*. An un-released PROCESSING row
            // cannot be claimed by the redelivery, so the retry budget would be spent on a row
            // nobody can take; and an unfenced release would hand a peer's live job back to
            // the queue for a second, concurrent execution.
            if (simulationRepository.releaseToPending(simulationId, fence)) {
                log.info("Released simulation {} back to PENDING for redelivery (fence={})",
                        simulationId, fence);
            } else {
                log.warn("Could not release simulation {}: the claim taken at {} is no longer "
                        + "current, so a peer owns the row. Leaving it alone — it will go stale "
                        + "and be reclaimed.", simulationId, fence);
            }

            // Rethrow to trigger Kafka retry/DLT. The row is PENDING again, not FAILED:
            // marking it terminal on every throw is what made the retry path inert.
            throw e;
        }
    }

    /**
     * Decides what to do with a delivery whose row it could not claim.
     *
     * <p>Not being able to claim is three different situations, and they must not be
     * collapsed into one response. Acknowledging all of them destroys messages: the record
     * is committed, never redelivered, and any work it represented is unrecoverable.
     *
     * <table border="1">
     *   <caption>Row state → response</caption>
     *   <tr><th>State</th><th>Response</th><th>Why</th></tr>
     *   <tr><td>{@code PROCESSING}, fresh</td><td>nack</td>
     *       <td>A peer holds it, or a dead consumer did and this is its first redelivery.
     *           Acknowledging would destroy the message with no recovery. Stale after one
     *           lease, so the loop terminates.</td></tr>
     *   <tr><td>{@code PENDING}</td><td>nack</td>
     *       <td>Lost the claim race to a concurrent claimant that has since released. The
     *           row is claimable and genuinely ours, so redeliver it.</td></tr>
     *   <tr><td>{@code COMPLETED}</td><td>ack + skip</td>
     *       <td>Terminal success. A duplicate must never re-run a finished simulation.</td></tr>
     *   <tr><td>{@code FAILED}</td><td>ack + skip</td>
     *       <td>Terminal failure, set by the recoverer. Replay is an explicit operator action
     *           that resets the row to PENDING.</td></tr>
     *   <tr><td>deleted or missing</td><td>ack + skip</td>
     *       <td>Nothing to run. Acknowledging prevents an infinite redelivery loop.</td></tr>
     * </table>
     */
    private void handleUnclaimable(UUID simulationId, int partition, long offset, Acknowledgment ack) {
        Optional<SimulationStatus> live = simulationRepository.findLiveStatus(simulationId);

        if (live.isEmpty()) {
            log.info("Simulation {} has no live row (deleted or never created); acknowledging "
                    + "so the record does not loop. partition={}, offset={}", simulationId, partition, offset);
            ack.acknowledge();
            return;
        }

        switch (live.get()) {
            case COMPLETED, FAILED -> {
                log.info("Simulation {} is {}; this delivery is a duplicate and there is nothing "
                        + "to run. Acknowledging.", simulationId, live.get());
                ack.acknowledge();
            }
            case PROCESSING, PENDING -> {
                log.info("Simulation {} is {} and not claimable; nacking so it comes back once "
                        + "the lease has lapsed. partition={}, offset={}", simulationId, live.get(), partition, offset);
                ack.nack(NACK_BACKOFF);
            }
        }
    }
}