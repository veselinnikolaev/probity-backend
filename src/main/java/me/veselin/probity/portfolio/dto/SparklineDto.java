package me.veselin.probity.portfolio.dto;

import java.util.List;

public record SparklineDto(
        List<Double> totalValue,
        List<Double> dailyReturn,
        List<Double> volatility,
        List<Double> sharpeRatio,
        List<Double> var95
) {}
