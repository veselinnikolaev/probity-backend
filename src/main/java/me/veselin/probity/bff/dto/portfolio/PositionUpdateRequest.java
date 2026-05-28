package me.veselin.probity.bff.dto.portfolio;

import java.math.BigDecimal;

/**
 * BFF request DTO for changing the quantity of an existing position.
 */
public record PositionUpdateRequest(
        BigDecimal quantity
) {}
