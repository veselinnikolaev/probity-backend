package me.veselin.probity.simulation.repository;

import me.veselin.probity.simulation.domain.Simulation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SimulationRepository extends JpaRepository<Simulation, UUID> {

    /**
     * Ownership-safe lookup — avoids a separate access-check query.
     */
    Optional<Simulation> findByIdAndUserId(UUID id, UUID userId);

    /**
     * Recent simulations for a portfolio, most recent first.
     * Excludes the heavy allPaths/percentileSeries payload via a projection
     * if you add a SimulationSummary interface — for now returns full entity.
     */
    @Query("""
            SELECT s FROM Simulation s
            WHERE s.portfolioId = :portfolioId
              AND s.userId      = :userId
            ORDER BY s.createdAt DESC
            """)
    List<Simulation> findByPortfolioIdAndUserId(
            @Param("portfolioId") UUID portfolioId,
            @Param("userId")      UUID userId
    );
}