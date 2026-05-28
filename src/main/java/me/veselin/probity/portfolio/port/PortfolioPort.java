package me.veselin.probity.portfolio.port;

import me.veselin.probity.portfolio.domain.Portfolio;

import java.util.UUID;

/**
 * Inbound query port for loading portfolio aggregates with ownership checks.
 */
public interface PortfolioPort {
    /**
     * Loads a portfolio including positions for a specific owner.
     */
    Portfolio loadPortfolioWithPositions(UUID id, UUID userId);
}
