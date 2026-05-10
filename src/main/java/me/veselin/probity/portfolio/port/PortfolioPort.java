package me.veselin.probity.portfolio.port;

import me.veselin.probity.portfolio.domain.Portfolio;

import java.util.UUID;

public interface PortfolioPort {
    Portfolio loadPortfolioWithPositions(UUID id, UUID userId);
}
