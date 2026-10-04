package me.veselin.probity.simulation.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
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

    @Query("SELECT s FROM SimulationJpaEntity s WHERE s.portfolioId = :portfolioId AND s.userId = :userId AND s.status = 'COMPLETED' AND s.deleted = false ORDER BY s.createdAt DESC")
    List<SimulationJpaEntity> findByPortfolioIdAndUserId(@Param("portfolioId") UUID portfolioId, @Param("userId") UUID userId);

    @Query("SELECT s FROM SimulationJpaEntity s WHERE s.userId = :userId AND s.status = :status AND s.deleted = false")
    List<SimulationJpaEntity> findByUserIdAndStatus(@Param("userId") UUID userId, @Param("status") String status);

    /**
     * Atomically claims a simulation for processing.
     * Updates status to PROCESSING and updated_at to now if:
     * - status is PENDING, OR
     * - status is PROCESSING and updated_at is older than the lease threshold (stale)
     *
     * @param id the simulation ID
     * @param processingStatus the status to set (PROCESSING)
     * @param staleThreshold threshold for considering a PROCESSING row stale
     * @return number of rows updated (1 if claimed, 0 if not)
     */
    @Modifying
    @Transactional
    @Query("UPDATE SimulationJpaEntity s SET s.status = :processingStatus, s.updatedAt = :now " +
           "WHERE s.id = :id AND s.deleted = false " +
           "AND (s.status = 'PENDING' OR (s.status = 'PROCESSING' AND s.updatedAt < :staleThreshold))")
    int claimForProcessing(@Param("id") UUID id, @Param("processingStatus") String processingStatus,
                           @Param("staleThreshold") Instant staleThreshold, @Param("now") Instant now);
}
