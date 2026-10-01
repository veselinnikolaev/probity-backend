package me.veselin.probity.simulation.persistence;

import me.veselin.probity.simulation.domain.Simulation;

/**
 * Mapper between domain Simulation and JPA SimulationJpaEntity.
 * Handles bidirectional conversion (create only - no update path needed).
 */
public class SimulationMapper {

    /**
     * Converts a domain Simulation to a JPA entity for creation.
     * Creates a fresh entity - used only for new aggregates.
     * No update path needed - Simulation is never loaded and re-saved.
     */
    public static SimulationJpaEntity toJpaEntity(Simulation domain) {
        return SimulationJpaEntity.builder()
                .portfolioId(domain.getPortfolioId())
                .userId(domain.getUserId())
                .numberOfSimulations(domain.getNumberOfSimulations())
                .timeHorizonDays(domain.getTimeHorizonDays())
                .confidenceLevel(domain.getConfidenceLevel())
                .assumedReturnPct(domain.getAssumedReturnPct())
                .assumedVolatilityPct(domain.getAssumedVolatilityPct())
                .currentPortfolioValue(domain.getCurrentPortfolioValue())
                .resultPayload(domain.getResultPayload())
                .build();
    }

    /**
     * Converts a JPA entity to a domain Simulation.
     * Copies IDs and timestamps from JPA entity to domain.
     */
    public static Simulation toDomain(SimulationJpaEntity entity) {
        Simulation domain = Simulation.builder()
                .portfolioId(entity.getPortfolioId())
                .userId(entity.getUserId())
                .numberOfSimulations(entity.getNumberOfSimulations())
                .timeHorizonDays(entity.getTimeHorizonDays())
                .confidenceLevel(entity.getConfidenceLevel())
                .assumedReturnPct(entity.getAssumedReturnPct())
                .assumedVolatilityPct(entity.getAssumedVolatilityPct())
                .currentPortfolioValue(entity.getCurrentPortfolioValue())
                .resultPayload(entity.getResultPayload())
                .build();
        domain.setId(entity.getId());
        domain.setCreatedAt(entity.getCreatedAt());
        domain.setUpdatedAt(entity.getUpdatedAt());
        domain.setVersion(entity.getVersion());
        return domain;
    }
}
