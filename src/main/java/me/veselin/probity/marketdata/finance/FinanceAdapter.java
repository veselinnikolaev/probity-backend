package me.veselin.probity.marketdata.finance;

import me.veselin.probity.marketdata.dto.PriceBarDto;

import java.time.LocalDate;
import java.util.List;

public interface FinanceAdapter {
    List<PriceBarDto> fetchDailyBars(String symbol, LocalDate from, LocalDate to);
}
