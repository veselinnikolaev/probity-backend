package me.veselin.probity.bff.dto.portfolio;

import java.math.BigDecimal;

public record PositionCreatedDto(
        String id,
        String ticker,
        BigDecimal quantity
) {}
