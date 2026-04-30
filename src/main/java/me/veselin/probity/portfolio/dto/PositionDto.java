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
        String positionValue,
        String sector,
        double weight,
        double volatility,
        double volatilityContribution,
        double quantity,
        double avgBuyPrice,
        double currentPrice,
        double positionValueRaw,
        double dailyReturn,
        double riskContribution
) {}
