package me.veselin.probity.portfolio.port.portfolio;

import jakarta.validation.constraints.NotNull;
import me.veselin.probity.portfolio.domain.Portfolio;
import me.veselin.probity.portfolio.dto.PortfolioDto;

import java.util.List;
import java.util.UUID;

/**
 * Inbound query port for portfolio aggregate read use cases.
 */
public interface PortfolioQueryPort {

    List<PortfolioDto> getPortfolios(UUID userId);

    PortfolioDto getPortfolio(UUID id, UUID userId);

    Portfolio loadPortfolioWithPositions(UUID uuid, UUID userId);
}
