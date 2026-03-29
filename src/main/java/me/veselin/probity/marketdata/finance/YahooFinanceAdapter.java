package me.veselin.probity.marketdata.finance;

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

    public List<PriceBarDto> fetchDailyBars(String ticker, LocalDate from, LocalDate to) {
        try {
            Stock stock = YahooFinance.get(ticker, toCalendar(from), toCalendar(to), Interval.DAILY);

            if (stock == null || stock.getHistory() == null) {
                throw new MarketDataException("No data returned for: " + ticker);
            }

            return stock.getHistory().stream()
                    .filter(q -> q.getAdjClose() != null && q.getClose() != null)
                    .map(q -> new PriceBarDto(
                            ticker,
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
            throw new MarketDataException("Failed to fetch data for: " + ticker, e);
        }
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private Calendar toCalendar(LocalDate date) {
        Calendar cal = Calendar.getInstance();
        cal.set(date.getYear(), date.getMonthValue() - 1, date.getDayOfMonth());
        return cal;
    }

    private LocalDate toLocalDate(Calendar cal) {
        return cal.toInstant()
                .atZone(ZoneId.of(ZoneIdEnumeration.PARIS.getZoneId()))
                .toLocalDate();
    }
}
