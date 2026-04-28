package me.veselin.probity.portfolio.dto;

import java.util.List;

public record PortfolioDto(
        String id,
        String name,
        double totalValue,
        String createdAt,
        int positionsCount,
        double volatility,
        double sharpeRatio,
        List<Double> sparklineData
) {}

