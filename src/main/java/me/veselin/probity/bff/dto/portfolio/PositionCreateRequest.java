package me.veselin.probity.bff.dto.portfolio;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;

/**
 * BFF request DTO for adding a position to a portfolio.
 */
public record PositionCreateRequest(
        @Schema(description = "Asset ticker symbol", example = "AAPL")
        String ticker,

        @Schema(description = "Position quantity (positive for long, negative for short)", example = "100.5")
        BigDecimal quantity
) {}

