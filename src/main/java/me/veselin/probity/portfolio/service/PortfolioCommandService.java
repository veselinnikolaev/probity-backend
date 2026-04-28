package me.veselin.probity.portfolio.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.veselin.probity.bff.dto.portfolio.PortfolioCreatedDto;
import me.veselin.probity.bff.dto.portfolio.PositionCreateRequest;
import me.veselin.probity.bff.dto.portfolio.PositionCreatedDto;
import me.veselin.probity.bff.dto.portfolio.PositionUpdateRequest;
import me.veselin.probity.portfolio.domain.Asset;
import me.veselin.probity.portfolio.domain.Portfolio;
import me.veselin.probity.portfolio.domain.PortfolioPosition;
import me.veselin.probity.portfolio.exception.AssetNotFoundException;
import me.veselin.probity.portfolio.exception.PortfolioNotFoundException;
import me.veselin.probity.portfolio.repository.AssetRepository;
import me.veselin.probity.portfolio.repository.PortfolioRepository;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional
@Slf4j
public class PortfolioCommandService {

    private final PortfolioRepository portfolioRepository;
    private final AssetRepository assetRepository;

    public PortfolioCreatedDto create(String name, UUID userId) {
        Portfolio saved = portfolioRepository.save(Portfolio.create(name, userId));
        return new PortfolioCreatedDto(saved.getId().toString(), saved.getName());
    }

    public PositionCreatedDto addPosition(UUID portfolioId, PositionCreateRequest request, UUID userId) {
        Portfolio portfolio = portfolioRepository.findByIdWithPositions(portfolioId)
                .orElseThrow(() -> new PortfolioNotFoundException("Portfolio not found: " + portfolioId));

        if (!portfolio.getUserId().equals(userId)) {
            throw new AccessDeniedException("Access denied to portfolio: " + portfolioId);
        }

        Asset asset = assetRepository.findByTicker(request.ticker())
                .orElseThrow(() -> new AssetNotFoundException("Asset not found: " + request.ticker()));

        // Portfolio.addPosition handles both merge and create internally
        portfolio.addPosition(asset, request.quantity());
        portfolioRepository.save(portfolio);

        PortfolioPosition saved = portfolio.getPositions().stream()
                .filter(p -> p.getAsset().getTicker().equals(request.ticker()))
                .findFirst()
                .orElseThrow();

        return new PositionCreatedDto(saved.getId().toString(), asset.getTicker(), saved.getQuantity());
    }

    public void updatePosition(UUID portfolioId, UUID positionId, PositionUpdateRequest request, UUID userId) {
        Portfolio portfolio = portfolioRepository.findByIdWithPositions(portfolioId)
                .orElseThrow(() -> new PortfolioNotFoundException("Portfolio not found: " + portfolioId));

        if (!portfolio.getUserId().equals(userId)) {
            throw new AccessDeniedException("Access denied to portfolio: " + portfolioId);
        }

        portfolio.updatePositionQuantity(positionId, request.quantity());
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
