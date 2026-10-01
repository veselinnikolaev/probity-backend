package me.veselin.probity.common.domain.event;

import java.time.Instant;
import java.util.UUID;

/**
 * Event published when a simulation is requested, before execution begins.
 */
public record SimulationRequestedEvent(
    UUID simulationId,
    UUID portfolioId,
    UUID userId,
    int numberOfSimulations,
    int timeHorizonDays,
    double confidenceLevel,
    Instant occurredAt
) implements DomainEvent {}
