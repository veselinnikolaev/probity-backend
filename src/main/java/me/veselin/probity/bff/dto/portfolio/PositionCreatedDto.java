package me.veselin.probity.bff.dto.portfolio;

import java.math.BigDecimal;

/**
 * BFF response DTO returned after adding a portfolio position.
 */
public record PositionCreatedDto(
        String id,
        String ticker,
        BigDecimal quantity
) {}
