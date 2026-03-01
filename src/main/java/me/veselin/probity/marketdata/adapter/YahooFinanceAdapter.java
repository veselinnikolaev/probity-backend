package me.veselin.probity.marketdata.adapter;

import me.veselin.probity.marketdata.enumeration.ZoneIdEnumeration;
import me.veselin.probity.marketdata.dto.PriceBarDto;
import me.veselin.probity.marketdata.exception.MarketDataException;
import org.springframework.stereotype.Component;
import yahoofinance.Stock;
import yahoofinance.YahooFinance;
import yahoofinance.histquotes.Interval;

import java.io.IOException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Calendar;
import java.util.Comparator;
import java.util.List;

@Component
public class YahooFinanceAdapter {

    private static final int DEFAULT_LOOKBACK_DAYS = 365;

    public List<PriceBarDto> fetchDailyBars(String symbol, LocalDate from) {
        try {
            Calendar fromCal = Calendar.getInstance();
            fromCal.set(from.getYear(), from.getMonthValue() - 1, from.getDayOfMonth());

            Stock stock = YahooFinance.get(symbol, fromCal, Interval.DAILY);

            if (stock == null || stock.getHistory() == null) {
                throw new MarketDataException("No data returned for: " + symbol);
            }

            return stock.getHistory().stream()
                    .filter(q -> q.getAdjClose() != null && q.getClose() != null)
                    .map(q -> new PriceBarDto(
                            symbol,
                            toLocalDate(q.getDate()),
                            q.getOpen(),
                            q.getHigh(),
                            q.getLow(),
                            q.getClose(),
                            q.getAdjClose(),
                            q.getVolume()
                    ))
                    .sorted(Comparator.comparing(PriceBarDto::barDate))
                    .toList();

        } catch (IOException e) {
            throw new MarketDataException("Failed to fetch data for: " + symbol, e);
        }
    }

    private LocalDate toLocalDate(Calendar cal) {
        return cal.toInstant().atZone(ZoneId.of(ZoneIdEnumeration.PARIS.getZoneId())).toLocalDate();
    }
}
