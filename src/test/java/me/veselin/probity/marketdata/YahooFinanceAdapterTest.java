package me.veselin.probity.marketdata;

import me.veselin.probity.marketdata.dto.PriceBarDto;
import me.veselin.probity.marketdata.exception.MarketDataException;
import me.veselin.probity.marketdata.finance.YahooFinanceAdapter;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import yahoofinance.Stock;
import yahoofinance.YahooFinance;
import yahoofinance.histquotes.HistoricalQuote;
import yahoofinance.histquotes.Interval;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Calendar;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit test for YahooFinanceAdapter.
 * Uses MockedStatic to intercept YahooFinance.get() — the adapter's only external dependency.
 */
class YahooFinanceAdapterTest {

    private final YahooFinanceAdapter adapter = new YahooFinanceAdapter();

    private static final LocalDate FROM = LocalDate.of(2024, 1, 1);
    private static final LocalDate TO   = LocalDate.of(2024, 1, 31);

    @Test
    void fetchDailyBars_returnsMappedBars_whenYahooRespondsSuccessfully() throws IOException {
        HistoricalQuote quote = stubQuote(
                LocalDate.of(2024, 1, 15),
                new BigDecimal("180.00"),
                new BigDecimal("182.00"),
                new BigDecimal("178.50"),
                new BigDecimal("181.00"),
                new BigDecimal("181.00"),
                1_500_000L
        );
        Stock stock = mockStock(List.of(quote));

        try (MockedStatic<YahooFinance> yahoo = mockStatic(YahooFinance.class)) {
            yahoo.when(() -> YahooFinance.get(eq("AAPL"), any(Calendar.class), any(Calendar.class), eq(Interval.DAILY)))
                    .thenReturn(stock);

            List<PriceBarDto> bars = adapter.fetchDailyBars("AAPL", FROM, TO);

            assertThat(bars).hasSize(1);
            PriceBarDto bar = bars.getFirst();
            assertThat(bar.ticker()).isEqualTo("AAPL");
            assertThat(bar.close()).isEqualByComparingTo("181.00");
            assertThat(bar.adjClose()).isEqualByComparingTo("181.00");
            assertThat(bar.volume()).isEqualTo(1_500_000L);
        }
    }

    @Test
    void fetchDailyBars_filtersOutBarsWithNullAdjClose() throws IOException {
        HistoricalQuote good = stubQuote(LocalDate.of(2024, 1, 15),
                new BigDecimal("180"), new BigDecimal("182"),
                new BigDecimal("178"), new BigDecimal("181"),
                new BigDecimal("181"), 100_000L);

        HistoricalQuote bad = stubQuote(LocalDate.of(2024, 1, 16),
                new BigDecimal("180"), new BigDecimal("182"),
                new BigDecimal("178"), new BigDecimal("181"),
                null,   // adjClose = null → should be filtered out
                100_000L);

        Stock stock = mockStock(List.of(good, bad));

        try (MockedStatic<YahooFinance> yahoo = mockStatic(YahooFinance.class)) {
            yahoo.when(() -> YahooFinance.get(eq("MSFT"), any(), any(), eq(Interval.DAILY)))
                    .thenReturn(stock);

            List<PriceBarDto> bars = adapter.fetchDailyBars("MSFT", FROM, TO);

            assertThat(bars).hasSize(1);
        }
    }

    @Test
    void fetchDailyBars_sortsBarsByDateAscending() throws IOException {
        // Yahoo returns bars in descending order — adapter must sort ascending
        HistoricalQuote later  = stubQuoteOnDate(LocalDate.of(2024, 1, 20));
        HistoricalQuote earlier = stubQuoteOnDate(LocalDate.of(2024, 1, 10));

        Stock stock = mockStock(List.of(later, earlier));

        try (MockedStatic<YahooFinance> yahoo = mockStatic(YahooFinance.class)) {
            yahoo.when(() -> YahooFinance.get(eq("TSLA"), any(), any(), eq(Interval.DAILY)))
                    .thenReturn(stock);

            List<PriceBarDto> bars = adapter.fetchDailyBars("TSLA", FROM, TO);

            assertThat(bars.get(0).barDate()).isBefore(bars.get(1).barDate());
        }
    }

    @Test
    void fetchDailyBars_throwsMarketDataException_whenYahooReturnsNull() {
        try (MockedStatic<YahooFinance> yahoo = mockStatic(YahooFinance.class)) {
            yahoo.when(() -> YahooFinance.get(eq("UNKNOWN"), any(), any(), eq(Interval.DAILY)))
                    .thenReturn(null);

            assertThatThrownBy(() -> adapter.fetchDailyBars("UNKNOWN", FROM, TO))
                    .isInstanceOf(MarketDataException.class)
                    .hasMessageContaining("No data returned for: UNKNOWN");
        }
    }

    @Test
    void fetchDailyBars_throwsMarketDataException_onIOException() {
        try (MockedStatic<YahooFinance> yahoo = mockStatic(YahooFinance.class)) {
            yahoo.when(() -> YahooFinance.get(eq("ERR"), any(), any(), eq(Interval.DAILY)))
                    .thenThrow(new IOException("network timeout"));

            assertThatThrownBy(() -> adapter.fetchDailyBars("ERR", FROM, TO))
                    .isInstanceOf(MarketDataException.class)
                    .hasMessageContaining("Failed to fetch data for: ERR")
                    .hasCauseInstanceOf(IOException.class);
        }
    }

    // ── Fixture helpers ──────────────────────────────────────────────────────

    private Stock mockStock(List<HistoricalQuote> quotes) throws IOException {
        Stock stock = mock(Stock.class);
        when(stock.getHistory()).thenReturn(
                new java.util.Vector<>(quotes)   // YahooFinance returns a Vector
        );
        return stock;
    }

    private HistoricalQuote stubQuote(
            LocalDate date, BigDecimal open, BigDecimal high,
            BigDecimal low, BigDecimal close, BigDecimal adjClose, Long volume) {

        HistoricalQuote quote = mock(HistoricalQuote.class);
        Calendar cal = Calendar.getInstance();
        cal.set(date.getYear(), date.getMonthValue() - 1, date.getDayOfMonth(), 12, 0, 0);
        when(quote.getDate()).thenReturn(cal);
        when(quote.getOpen()).thenReturn(open);
        when(quote.getHigh()).thenReturn(high);
        when(quote.getLow()).thenReturn(low);
        when(quote.getClose()).thenReturn(close);
        when(quote.getAdjClose()).thenReturn(adjClose);
        when(quote.getVolume()).thenReturn(volume);
        return quote;
    }

    /** Convenience overload for date-only stubs (sorting tests). */
    private HistoricalQuote stubQuoteOnDate(LocalDate date) {
        return stubQuote(date,
                new BigDecimal("100"), new BigDecimal("105"),
                new BigDecimal("98"),  new BigDecimal("102"),
                new BigDecimal("102"), 500_000L);
    }
}