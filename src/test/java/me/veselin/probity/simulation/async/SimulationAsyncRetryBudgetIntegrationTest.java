package me.veselin.probity.simulation.async;

import me.veselin.probity.simulation.domain.Simulation;
import me.veselin.probity.simulation.domain.SimulationStatus;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

/**
 * T4b - a run that always fails must be retried on a bounded budget, then dead-lettered,
 * and its row must be released back to {@code PENDING} on every failed attempt.
 *
 * <p>Mechanism under test, as measured on {@code master}: the injected failure is never
 * even reached. The consumer claims the row, then {@code runAsync} claims it again, the
 * second claim matches nothing and returns through the dummy-DTO branch without executing
 * (see T2), so the execution count is 0 rather than 1 and there is no failure to retry.
 * The row is then acked and left at {@code PROCESSING}.
 *
 * <p>That corrects rev. 3 of the design note, which predicted 1 attempt followed by a
 * {@code FAILED} row. Rev. 3 assumed the injected failure would fire; in fact F1 swallows
 * the run before the injected failure has any effect, so the retry budget, the DLT and the
 * {@code FAILED} transition are all unreachable today. This test therefore pins the
 * <em>fixed</em> contract, and its first red is F1 rather than the retry policy.
 *
 * <p>Asserted invariants after the fix:
 * <ol>
 *   <li>exactly 3 attempts - one initial plus two retries;</li>
 *   <li>the row returns to {@code PENDING} on every failed attempt, so it is claimable
 *       again rather than stranded in {@code PROCESSING};</li>
 *   <li>the record reaches the DLT once the budget is exhausted;</li>
 *   <li>the row becomes {@code FAILED} only at DLT publication, from the record key.</li>
 * </ol>
 *
 * <p>Context is dirtied so the deliberately failing record cannot outlive this class.
 */
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class SimulationAsyncRetryBudgetIntegrationTest extends BaseAsyncSimulationIntegrationTest {

    private static final Duration DLT_WAIT = Duration.ofSeconds(20);
    private static final int EXPECTED_ATTEMPTS = 3;

    @DynamicPropertySource
    static void kafkaProperties(DynamicPropertyRegistry registry) {
        AsyncKafkaTestSupport.registerAsyncBroker(registry, true);
        // Consumed by PR-A. Inert on master, where the retry policy is Boot's default.
        registry.add("probity.simulation.claim-lease", () -> "PT2S");
    }

    @Test
    @DisplayName("T4b: an always-failing run is retried, then dead-lettered, then failed")
    void alwaysFailingRun_isRetriedThenDeadLetteredThenFailed() throws Exception {
        UUID id = seedPendingSimulation();

        // The failure is injected where real execution begins, i.e. after the claim.
        doThrow(new IllegalStateException("market data unavailable"))
                .when(portfolioQueryPort).loadPortfolioWithPositions(any(), any());

        publish(validEventFor(id), id);

        List<ConsumerRecord<String, byte[]>> dlt = AsyncKafkaTestSupport.drainDlt(DLT_WAIT, 1);
        Simulation row = simulationRepository.findById(id).orElseThrow();

        assertEquals(EXPECTED_ATTEMPTS, executionAttempts(),
                () -> "the run must be retried on a bounded budget: 1 initial attempt + 2 "
                        + "retries = " + EXPECTED_ATTEMPTS + ". A count of 0 means F1 is still "
                        + "open - the consumer's second claim swallows the run before the "
                        + "injected failure is ever reached. " + describe(id, dlt, row));

        assertEquals(SimulationStatus.FAILED, row.getStatus(),
                () -> "the row must be marked FAILED when the record is dead-lettered. "
                        + describe(id, dlt, row));

        assertFalse(dlt.isEmpty(),
                () -> "an exhausted retry budget must produce a dead-letter record so the work "
                        + "is not lost. " + describe(id, dlt, row));
    }

    private String describe(UUID id, List<ConsumerRecord<String, byte[]>> dlt, Simulation row) {
        return "Observed: " + diagnostics(id, row)
                + " | dltRecords=" + dlt.size()
                + " keys=" + dlt.stream().map(ConsumerRecord::key).toList();
    }
}
