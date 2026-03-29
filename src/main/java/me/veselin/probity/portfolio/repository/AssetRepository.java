package me.veselin.probity.portfolio.repository;

import me.veselin.probity.portfolio.domain.Asset;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface AssetRepository extends JpaRepository<Asset, Long> {
}
