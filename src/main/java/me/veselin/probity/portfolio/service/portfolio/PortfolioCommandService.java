package me.veselin.probity.portfolio.service.portfolio;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.veselin.probity.bff.dto.portfolio.PortfolioCreatedDto;
import me.veselin.probity.bff.dto.portfolio.PositionCreateRequest;
import me.veselin.probity.bff.dto.portfolio.PositionCreatedDto;
import me.veselin.probity.bff.dto.portfolio.PositionUpdateRequest;
import me.veselin.probity.marketdata.port.MarketDataPort;
import me.veselin.probity.portfolio.domain.Asset;
import me.veselin.probity.portfolio.domain.Portfolio;
import me.veselin.probity.portfolio.domain.PortfolioPosition;
import me.veselin.probity.portfolio.exception.DuplicatePortfolioException;
import me.veselin.probity.portfolio.exception.PortfolioNotFoundException;
import me.veselin.probity.portfolio.exception.PositionNotFoundException;
import me.veselin.probity.portfolio.repository.PortfolioRepository;
import me.veselin.probity.portfolio.service.asset.AssetResolver;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional
@Slf4j
public class PortfolioCommandService {

    private final PortfolioRepository portfolioRepository;
    private final AssetResolver assetResolver;
    private final MarketDataPort marketDataPort;

    public PortfolioCreatedDto create(String name, UUID userId) {
        if (portfolioRepository.existsByNameAndUserId(name, userId)) {
            throw new DuplicatePortfolioException("Portfolio '" + name + "' already exists");
        }
        Portfolio saved = portfolioRepository.save(Portfolio.create(name, userId));
        return new PortfolioCreatedDto(saved.getId().toString(), saved.getName());
    }

    public PositionCreatedDto addPosition(UUID portfolioId,
                                          PositionCreateRequest request,
                                          UUID userId) {

        Portfolio portfolio = portfolioRepository.findByIdWithPositions(portfolioId)
                .orElseThrow(() -> new PortfolioNotFoundException(
                        "Portfolio not found: " + portfolioId));

        if (!portfolio.getUserId().equals(userId))
            throw new AccessDeniedException("Access denied to portfolio: " + portfolioId);

        // Resolves from DB or auto-creates from Yahoo Finance
        Asset asset = assetResolver.resolve(request.ticker());

        BigDecimal currentPrice = marketDataPort.getLatestPrice(asset.getTicker());

        PortfolioPosition position = portfolio.addPosition(asset, request.quantity(), currentPrice);
        portfolioRepository.saveAndFlush(portfolio);

        return new PositionCreatedDto(
                position.getId().toString(),
                asset.getTicker(),
                position.getQuantity()
        );
    }

    public void updatePosition(UUID portfolioId, UUID positionId,
                               PositionUpdateRequest request, UUID userId) {
        Portfolio portfolio = portfolioRepository.findByIdWithPositions(portfolioId)
                .orElseThrow(() -> new PortfolioNotFoundException("Portfolio not found: " + portfolioId + " for user: " + userId));

        if (!portfolio.getUserId().equals(userId))
            throw new AccessDeniedException("Access denied to portfolio: " + portfolioId + " for user: " + userId);

        // Find the position to get its ticker, then fetch current price
        PortfolioPosition pos = portfolio.getPositions().stream()
                .filter(p -> p.getId().equals(positionId))
                .findFirst()
                .orElseThrow(() -> new PositionNotFoundException("Position not found: " + positionId + " in portfolio: " + portfolioId));

        BigDecimal currentPrice = marketDataPort.getLatestPrice(pos.getAsset().getTicker());

        portfolio.updatePositionQuantity(positionId, request.quantity(), currentPrice);
        portfolioRepository.save(portfolio);
    }

    public void deletePosition(UUID portfolioId, UUID positionId, UUID userId) {
        Portfolio portfolio = portfolioRepository.findByIdWithPositions(portfolioId)
                .orElseThrow(() -> new PortfolioNotFoundException("Portfolio not found: " + portfolioId));

        if (!portfolio.getUserId().equals(userId)) {
            throw new AccessDeniedException("Access denied to portfolio: " + portfolioId);
        }

        portfolio.removePosition(positionId);
        portfolioRepository.save(portfolio);
    }
}
