package me.veselin.probity.bff.dto.portfolio;

import java.math.BigDecimal;

/**
 * BFF request DTO for adding a position to a portfolio.
 */
public record PositionCreateRequest(
        String ticker,
        BigDecimal quantity
) {}

