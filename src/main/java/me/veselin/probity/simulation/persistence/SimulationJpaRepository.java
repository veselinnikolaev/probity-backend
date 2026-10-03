package me.veselin.probity.simulation.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data JPA repository for SimulationJpaEntity.
 * This is the low-level JPA interface that the SimulationRepository adapter wraps.
 */
public interface SimulationJpaRepository extends JpaRepository<SimulationJpaEntity, UUID> {

    @Query("SELECT s FROM SimulationJpaEntity s WHERE s.id = :id AND s.userId = :userId AND s.deleted = false")
    Optional<SimulationJpaEntity> findByIdAndUserId(@Param("id") UUID id, @Param("userId") UUID userId);

    @Query("SELECT s FROM SimulationJpaEntity s WHERE s.portfolioId = :portfolioId AND s.userId = :userId AND s.deleted = false ORDER BY s.createdAt DESC")
    List<SimulationJpaEntity> findByPortfolioIdAndUserId(@Param("portfolioId") UUID portfolioId, @Param("userId") UUID userId);

    @Query("SELECT s FROM SimulationJpaEntity s WHERE s.userId = :userId AND s.status = :status AND s.deleted = false")
    List<SimulationJpaEntity> findByUserIdAndStatus(@Param("userId") UUID userId, @Param("status") String status);
}
