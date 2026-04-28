package me.veselin.probity.bff.dto.portfolio;

import java.math.BigDecimal;

public record PositionCreateRequest(
        String ticker,
        BigDecimal quantity
) {}

