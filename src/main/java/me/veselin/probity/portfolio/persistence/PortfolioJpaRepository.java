package me.veselin.probity.portfolio.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data JPA repository for PortfolioJpaEntity.
 * This is the low-level JPA interface that the PortfolioRepository adapter wraps.
 */
public interface PortfolioJpaRepository extends JpaRepository<PortfolioJpaEntity, UUID> {

    @Query("SELECT p FROM PortfolioJpaEntity p LEFT JOIN FETCH p.positions WHERE p.id = :id AND p.deleted = false")
    Optional<PortfolioJpaEntity> findByIdWithPositions(@Param("id") UUID id);

    @Query("SELECT p FROM PortfolioJpaEntity p LEFT JOIN FETCH p.positions WHERE p.userId = :userId AND p.deleted = false")
    List<PortfolioJpaEntity> findByUserIdWithPositions(@Param("userId") UUID userId);

    boolean existsByNameAndUserId(String name, UUID userId);
}
