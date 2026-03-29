package me.veselin.probity.portfolio.dto;

public record PortfolioSummaryDto(
        double totalValue,
        double totalValueDelta,
        double dailyReturn,
        double dailyReturnDelta,
        double volatility,
        double volatilityDelta,
        double sharpeRatio,
        double sharpeRatioDelta,
        double var95,
        double var95Delta,
        SparklineDto sparklines
) {}
