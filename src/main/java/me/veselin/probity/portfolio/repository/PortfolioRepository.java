package me.veselin.probity.portfolio.repository;

import me.veselin.probity.portfolio.domain.Portfolio;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface PortfolioRepository extends JpaRepository<Portfolio, UUID> {
    @Query("""
                SELECT p FROM Portfolio p
                LEFT JOIN FETCH p.positions pos
                LEFT JOIN FETCH pos.asset
                WHERE p.id = :id
            """)
    Optional<Portfolio> findByIdWithPositions(@Param("id") UUID id);

    @Query("""
                SELECT p FROM Portfolio p
                LEFT JOIN FETCH p.positions pos
                LEFT JOIN FETCH pos.asset
                WHERE p.userId = :userId
            """)
    List<Portfolio> findByUserIdWithPositions(@Param("userId") UUID userId);

    boolean existsByNameAndUserId(String name, UUID userId);
}

