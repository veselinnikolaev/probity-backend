package me.veselin.probity.portfolio.dto;

public record PositionDto(
        String id,
        String ticker,
        String name,
        String assetType,
        double price,
        double change,
        double changePercent,
        int riskScore,
        String riskLevel,
        String volume,
        String marketCap,
        String sector,
        double weight,
        double volatility,
        double volatilityContribution
) {}
