package me.veselin.probity.portfolio.dto;

import java.math.BigDecimal;

public record AssetSearchResultDto(
        String ticker,
        String name,
        String type,
        String sector,
        BigDecimal currentPrice
) {}
