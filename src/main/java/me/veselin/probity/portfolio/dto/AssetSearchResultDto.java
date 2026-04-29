package me.veselin.probity.portfolio.dto;

import java.math.BigDecimal;

public record AssetSearchResultDto(String ticker, String name, String name1, String label, BigDecimal currentPrice) {
}
