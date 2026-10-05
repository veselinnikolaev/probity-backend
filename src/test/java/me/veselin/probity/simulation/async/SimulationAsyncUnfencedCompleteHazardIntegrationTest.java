package me.veselin.probity.simulation.async;

import me.veselin.probity.simulation.domain.Simulation;
import me.veselin.probity.simulation.domain.SimulationPayload;
import me.veselin.probity.simulation.domain.SimulationStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static me.veselin.probity.simulation.async.AsyncKafkaTestSupport.registerDormantListener;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T3b - <strong>the master-compatible probe, and it is expected to PASS today.</strong>
 *
 * <p>Rev. 3's T3b is the fenced test: claim as A, lose the lease, have B reclaim, then call
 * {@code complete(id, ., F_A)} and require 0 rows updated. That test cannot be written yet,
 * because {@code complete} has no fence token on master.
 *
 * <p>So Step 2 ships the <em>other half</em> of the evidence: a characterisation test that
 * reproduces the hazard using master's real, token-less API. It calls the production
 * {@code claimForProcessing(id, lease)} twice to hand the row from A to B, then has A's
 * late {@code complete(id, value, payload)} land anyway, and asserts that A's result
 * <strong>did</strong> overwrite the row B now owns.
 *
 * <p>Green here is the point. Step 3 replaces this file with the fenced test and inverts
 * the assertion. PR-A's acceptance evidence is therefore a pair: the hazard reproduced
 * here, and the fix rejecting it in its replacement.
 *
 * <p>A second test in this class, {@code complete_persistsTheResultPayloadAndPortfolioValue},
 * is RED today and is not part of the fencing probe. It documents an independent defect
 * found while writing T3b — see its Javadoc.
 *
 * <p>No Kafka and no listener: this is the repository's write-back path in isolation.
 */
class SimulationAsyncUnfencedCompleteHazardIntegrationTest extends BaseAsyncSimulationIntegrationTest {

    /** Short enough that a sleep can outlast it, long enough to be a real lease. */
    private static final Duration LEASE = Duration.ofMillis(400);

    /** Distinctive so an assertion can prove which payload was written. */
    private static final double WORKER_A_VALUE = 111.0;

    @DynamicPropertySource
    static void kafkaProperties(DynamicPropertyRegistry registry) {
        registerDormantListener(registry);
    }

    @Test
    @DisplayName("T3b probe: a worker that lost the lease still overwrites the reclaiming worker's row")
    void lostClaim_stillOverwritesTheRowOnMaster() {
        UUID id = seedPendingSimulation();

        // ── Worker A claims ───────────────────────────────────────────────────
        assertTrue(simulationRepository.claimForProcessing(id, LEASE),
                "worker A must be able to claim a PENDING row");

        // ── A's lease lapses ──────────────────────────────────────────────────
        // In production this is a crash, a GC pause, or a wedged execution. Here the
        // only thing needed is for updated_at to fall outside the lease window.
        // Observed, never mutated: the wait must not itself claim the row.
        await().atMost(Duration.ofSeconds(5))
                .pollInterval(Duration.ofMillis(50))
                .until(() -> simulationRepository.findById(id)
                        .map(Simulation::getUpdatedAt)
                        .map(updatedAt -> updatedAt.isBefore(Instant.now().minus(LEASE)))
                        .orElse(false));

        // ── Worker B reclaims ─────────────────────────────────────────────────
        assertTrue(simulationRepository.claimForProcessing(id, LEASE),
                "worker B must be able to claim the row once A's lease has lapsed");
        Simulation ownedByB = simulationRepository.findById(id).orElseThrow();
        assertEquals(SimulationStatus.PROCESSING, ownedByB.getStatus());
        assertNull(ownedByB.getResultPayload(), "B has not produced a result yet");

        // ── A's late write-back ───────────────────────────────────────────────
        // A is no longer the owner. Nothing in the token-less signature lets the
        // database notice.
        Simulation afterA = simulationRepository.complete(id,
                new BigDecimal("2160.00"), payload(WORKER_A_VALUE));

        // ── The hazard, asserted ──────────────────────────────────────────────
        // The returned object carries A's payload because complete() mutates the loaded
        // entity in memory. The re-read is the only honest check, and it is the check
        // that exposes the second defect below.
        assertEquals(SimulationStatus.COMPLETED, afterA.getStatus(),
                "HAZARD REPRODUCED: master has no fence, so the stale worker's write-back "
                        + "is accepted and the row is marked COMPLETED by a worker that "
                        + "lost its claim. " + diagnostics(id, afterA));

        Simulation reread = simulationRepository.findById(id).orElseThrow();
        assertEquals(SimulationStatus.COMPLETED, reread.getStatus(),
                "HAZARD REPRODUCED: the re-read row must show COMPLETED, i.e. A's "
                        + "unfenced write-back was accepted and B now owns a row that "
                        + "claims to be finished. In PR-A this update affects 0 rows "
                        + "because A's fence token is stale. " + diagnostics(id, reread));
    }

    /**
     * A second defect, found while writing T3b and independent of fencing:
     * {@code SimulationJpaEntity.resultPayload} and {@code currentPortfolioValue} are both
     * declared {@code updatable = false}, so the {@code save()} inside
     * {@code SimulationRepository.complete} emits an UPDATE that omits them. A COMPLETED
     * row therefore carries no payload and no portfolio value — the worker's entire result
     * is silently discarded while the row claims success.
     *
     * <p>The synchronous path escapes this because it INSERTs the row with the payload
     * already attached rather than updating a PENDING one, which is why the existing suite
     * is green. Only {@code complete()} — the async path — hits it.
     *
     * <p>RED today. The returned domain object is not evidence: it is built from the same
     * mutated instance {@code complete()} saved, so it shows A's payload even when nothing
     * reached the database. Only a re-read proves the write landed.
     */
    @Test
    @DisplayName("T3b: complete() must persist the result payload and portfolio value")
    void complete_persistsTheResultPayloadAndPortfolioValue() {
        UUID id = seedPendingSimulation();
        assertTrue(simulationRepository.claimForProcessing(id, LEASE),
                "the row must be claimable before it can be completed");

        BigDecimal value = new BigDecimal("2160.00");
        SimulationPayload result = payload(WORKER_A_VALUE);
        Simulation returned = simulationRepository.complete(id, value, result);

        Simulation reread = simulationRepository.findById(id).orElseThrow();

        assertNotNull(reread.getResultPayload(),
                () -> "complete() set status=COMPLETED but the re-read row has a null "
                        + "resultPayload, so the computation was thrown away. The entity "
                        + "declares resultPayload updatable = false, which excludes it from "
                        + "the UPDATE that save() issues. (complete() returned "
                        + (returned.getResultPayload() == null ? "null" : "a payload")
                        + " because it maps the same in-memory instance it just saved - not "
                        + "because anything was written.) " + diagnostics(id, reread));

        assertEquals(WORKER_A_VALUE,
                reread.getResultPayload().statistics().expectedFinalValue(),
                () -> "the persisted payload must be the one complete() was handed. "
                        + diagnostics(id, reread));

        assertEquals(0, value.compareTo(reread.getCurrentPortfolioValue()),
                () -> "currentPortfolioValue is also updatable = false, so the live "
                        + "portfolio value is lost the same way the payload is. "
                        + diagnostics(id, reread));
    }

    private static SimulationPayload payload(double expectedFinalValue) {
        return new SimulationPayload(
                new SimulationPayload.Statistics(expectedFinalValue, expectedFinalValue,
                        1.0, 1.0, expectedFinalValue),
                new SimulationPayload.Outcomes(0.0, 0.0, 0.0, 0.0),
                List.of(),
                List.of(),
                List.of());
    }
}
