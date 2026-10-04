package me.veselin.probity.simulation.persistence;

import lombok.RequiredArgsConstructor;
import me.veselin.probity.simulation.domain.Simulation;
import me.veselin.probity.simulation.domain.SimulationPayload;
import me.veselin.probity.simulation.domain.SimulationStatus;
import me.veselin.probity.simulation.exception.SimulationNotFoundException;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Adapter wrapping SimulationJpaRepository and SimulationMapper.
 * Provides domain-oriented interface for services to use.
 */
@Repository
@RequiredArgsConstructor
public class SimulationRepository {

    private final SimulationJpaRepository jpaRepository;

    /**
     * Saves a new simulation.
     */
    public Simulation save(Simulation domain) {
        SimulationJpaEntity jpaEntity = SimulationMapper.toJpaEntity(domain);
        SimulationJpaEntity saved = jpaRepository.save(jpaEntity);
        return SimulationMapper.toDomain(saved);
    }

    /**
     * Completes an existing simulation by loading it, setting the result payload,
     * current portfolio value, and status to COMPLETED, then saving.
     *
     * @param id the simulation ID
     * @param currentValue the current portfolio value at time of completion
     * @param payload the full simulation result payload
     * @return the completed simulation domain object
     * @throws SimulationNotFoundException if no simulation with the given ID exists
     */
    public Simulation complete(UUID id, BigDecimal currentValue, SimulationPayload payload) {
        SimulationJpaEntity entity = jpaRepository.findById(id)
                .orElseThrow(() -> new SimulationNotFoundException("Simulation not found: " + id));
        entity.setCurrentPortfolioValue(currentValue);
        entity.setResultPayload(payload);
        entity.setStatus(SimulationStatus.COMPLETED.name());
        SimulationJpaEntity saved = jpaRepository.save(entity);
        return SimulationMapper.toDomain(saved);
    }

    /**
     * Atomically claims a PENDING (or stale PROCESSING) simulation for processing.
     * Updates status to PROCESSING and updated_at to now.
     *
     * @param id the simulation ID
     * @param lease maximum age of a PROCESSING row to consider stale (e.g., 5 minutes)
     * @return true if row was claimed (status was PENDING or stale PROCESSING), false otherwise
     */
    public boolean claimForProcessing(UUID id, java.time.Duration lease) {
        Instant now = Instant.now();
        Instant staleThreshold = now.minus(lease);
        int updated = jpaRepository.claimForProcessing(id, SimulationStatus.PROCESSING.name(), staleThreshold, now);
        return updated > 0;
    }

    public Optional<Simulation> findById(UUID id) {
        return jpaRepository.findById(id)
                .map(SimulationMapper::toDomain);
    }

    public Optional<Simulation> findByIdAndUserId(UUID id, UUID userId) {
        return jpaRepository.findByIdAndUserId(id, userId)
                .map(SimulationMapper::toDomain);
    }

    public List<Simulation> findByPortfolioIdAndUserId(UUID portfolioId, UUID userId) {
        return jpaRepository.findByPortfolioIdAndUserId(portfolioId, userId).stream()
                .map(SimulationMapper::toDomain)
                .toList();
    }

    /**
     * Deletes all simulations (for test cleanup only).
     */
    public void deleteAll() {
        jpaRepository.deleteAll();
    }

    /**
     * Finds a simulation by ID and user ID, including status.
     */
    public Optional<Simulation> findByIdAndUserIdWithStatus(UUID id, UUID userId) {
        return jpaRepository.findByIdAndUserId(id, userId)
                .map(SimulationMapper::toDomain);
    }

    /**
     * Updates the status of a simulation.
     */
    public void updateStatus(UUID id, SimulationStatus status) {
        jpaRepository.findById(id).ifPresent(entity -> {
            entity.setStatus(status.name());
            jpaRepository.save(entity);
        });
    }

    /**
     * Finds simulations stuck in PROCESSING state for recovery.
     */
    public List<Simulation> findStaleProcessingSimulations(UUID userId) {
        return jpaRepository.findByUserIdAndStatus(userId, SimulationStatus.PROCESSING.name()).stream()
                .map(SimulationMapper::toDomain)
                .toList();
    }
}
