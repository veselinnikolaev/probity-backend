package me.veselin.probity.portfolio.port.portfolio;

import me.veselin.probity.bff.dto.portfolio.PortfolioCreatedDto;
import me.veselin.probity.bff.dto.portfolio.PositionCreateRequest;
import me.veselin.probity.bff.dto.portfolio.PositionCreatedDto;
import me.veselin.probity.bff.dto.portfolio.PositionUpdateRequest;

import java.util.UUID;

/**
 * Inbound command port for portfolio mutation use cases.
 */
public interface PortfolioCommandPort {

    PortfolioCreatedDto create(String name, UUID userId);

    PositionCreatedDto addPosition(UUID portfolioId, PositionCreateRequest request, UUID userId);

    void updatePosition(UUID portfolioId, UUID positionId, PositionUpdateRequest request, UUID userId);

    void deletePosition(UUID portfolioId, UUID positionId, UUID userId);
}
