package me.veselin.probity.marketdata.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

public record PriceBarDto(
        String symbol,
        LocalDate barDate,
        BigDecimal open,
        BigDecimal high,
        BigDecimal low,
        BigDecimal close,
        BigDecimal adjClose,
        Long volume
) {}