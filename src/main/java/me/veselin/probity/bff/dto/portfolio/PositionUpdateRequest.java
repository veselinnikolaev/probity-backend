package me.veselin.probity.bff.dto.portfolio;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;

/**
 * BFF request DTO for changing the quantity of an existing position.
 */
public record PositionUpdateRequest(
        @Schema(description = "New position quantity (positive for long, negative for short)", example = "150.0")
        BigDecimal quantity
) {}
