package me.veselin.probity.simulation.async;

import me.veselin.probity.simulation.domain.Simulation;
import me.veselin.probity.simulation.domain.SimulationStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Duration;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Positive control for the whole async slice.
 *
 * <p>If this test fails, every red in {@code me.veselin.probity.simulation.async} is
 * meaningless: a broken broker, a listener that never started, or a claim UPDATE that
 * does not match would all look identical. It passes on {@code master} and must keep
 * passing after PR-A.
 */
class SimulationAsyncBrokerControlIntegrationTest extends BaseAsyncSimulationIntegrationTest {

    @DynamicPropertySource
    static void kafkaProperties(DynamicPropertyRegistry registry) {
        AsyncKafkaTestSupport.registerAsyncBroker(registry, true);
    }

    @Test
    @DisplayName("control: broker, listener and claim UPDATE all work end to end")
    void validEventIsDeliveredAndTheRowIsClaimed() throws Exception {
        UUID id = seedPendingSimulation();

        publish(validEventFor(id), id);

        // Only the consumer's conditional UPDATE can move a row off PENDING, so reaching a
        // claimed status proves broker -> listener -> repository is wired up.
        //
        // PROCESSING is the expected terminal state of the *claim* here, and is also where
        // master then wedges: runAsync claims a second time, matches nothing, and returns
        // without executing. T2 is the test that pins the execution half down.
        Simulation claimed = awaitStatusChangeFromPending(id, Duration.ofSeconds(45));

        assertEquals(SimulationStatus.PROCESSING, claimed.getStatus(),
                () -> "the consumer received the record but did not claim the row: " + diagnostics(id, claimed));

        String consumer = describeConsumer();
        assertTrue(consumer.contains("running=true")
                        && consumer.contains(AsyncKafkaTestSupport.SOURCE_TOPIC + "-0"),
                () -> "the simulation listener should be running and holding partition 0 of "
                        + AsyncKafkaTestSupport.SOURCE_TOPIC + ", but was: " + consumer);
    }
}
