package me.veselin.probity.portfolio.dto;

public record AssetMetadataDto(
        String ticker,
        String name,
        String quoteType,
        String sector
) {}

