package me.veselin.probity.simulation.persistence;

import lombok.RequiredArgsConstructor;
import me.veselin.probity.simulation.domain.Simulation;
import org.springframework.stereotype.Repository;

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
     * Saves a simulation (create only - no update path needed).
     * Simulation is never loaded and re-saved in current code.
     */
    public Simulation save(Simulation domain) {
        SimulationJpaEntity jpaEntity = SimulationMapper.toJpaEntity(domain);
        SimulationJpaEntity saved = jpaRepository.save(jpaEntity);
        return SimulationMapper.toDomain(saved);
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
}
