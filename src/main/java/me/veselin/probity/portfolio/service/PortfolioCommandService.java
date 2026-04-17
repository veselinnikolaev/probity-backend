package me.veselin.probity.portfolio.service;

import lombok.RequiredArgsConstructor;
import me.veselin.probity.bff.dto.portfolio.PortfolioCreatedDto;
import me.veselin.probity.portfolio.domain.Portfolio;
import me.veselin.probity.portfolio.repository.PortfolioRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class PortfolioCommandService {

    private final PortfolioRepository portfolioRepository;

    @Transactional
    public PortfolioCreatedDto create(String name, UUID id) {
        Portfolio saved = portfolioRepository.save(Portfolio.create(name, id));
        return new PortfolioCreatedDto(saved.getId().toString(), saved.getName());
    }
}
