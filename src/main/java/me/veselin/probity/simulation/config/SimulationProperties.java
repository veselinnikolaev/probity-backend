package me.veselin.probity.simulation.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Configuration for the asynchronous simulation worker.
 *
 * <p>Loaded from {@code application.yaml} under the {@code probity.simulation} namespace.
 */
@Data
@Component
@ConfigurationProperties(prefix = "probity.simulation")
public class SimulationProperties {

    /**
     * How long a claimed row stays claimed before another worker is allowed to take it.
     *
     * <p>This is the whole crash-recovery mechanism: a worker that dies mid-simulation leaves
     * the row {@code PROCESSING}, and this bound is what makes it claimable again. It is
     * also the upper bound on how long a nacked record loops before it can be reclaimed, so
     * a lease that is too long delays recovery and one that is too short lets a slow but
     * healthy worker be preempted — a second worker would start the same simulation and the
     * first would then find its fence gone.
     *
     * <p>Must exceed the slowest legitimate simulation run, and be well under
     * {@code max.poll.interval.ms} so a live worker is never mistaken for a dead one.
     */
    private Duration claimLease = Duration.ofMinutes(5);
}