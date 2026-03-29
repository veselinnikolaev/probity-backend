package me.veselin.probity.marketdata.port;

import me.veselin.probity.marketdata.domain.PriceBar;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public interface MarketDataPort {

    BigDecimal getLatestPrice(String ticker);

    /**
     * Daily bars ordered oldest → newest, gap-filled from Yahoo on demand.
     * Returns PriceBar domain objects so callers can use domain methods
     * like dailyReturn(previousBar) directly.
     */
    List<PriceBar> getHistoricalBars(
            String ticker, LocalDate from, LocalDate to);
}