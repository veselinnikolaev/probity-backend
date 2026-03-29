package me.veselin.probity.marketdata;

import me.veselin.probity.marketdata.domain.PriceBar;
import me.veselin.probity.marketdata.dto.PriceBarDto;
import me.veselin.probity.marketdata.repository.PriceBarRepository;
import me.veselin.probity.marketdata.service.MarketDataService;
import me.veselin.probity.marketdata.service.MarketDataSyncService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MarketDataServiceTest {

    @Mock PriceBarRepository  priceBarRepository;
    @Mock MarketDataSyncService syncService;

    @InjectMocks MarketDataService service;

    // ── getLatestPrice ───────────────────────────────────────────────────────

    @Test
    void getLatestPrice_uppercasesTicker() {
        PriceBar bar = priceBarWithClose("AAPL", new BigDecimal("182.50"));
        when(priceBarRepository.findTopByTickerOrderByBarDateDesc("AAPL"))
                .thenReturn(Optional.of(bar));

        service.getLatestPrice("aapl");   // lowercase input

        verify(priceBarRepository).findTopByTickerOrderByBarDateDesc("AAPL");
    }

    @Test
    void getLatestPrice_fetchesFromYahoo_whenNoDataInDb() {
        PriceBar fetched = priceBarWithClose("TSLA", new BigDecimal("250.00"));

        when(priceBarRepository.findTopByTickerOrderByBarDateDesc("TSLA"))
                .thenReturn(Optional.empty())        // before sync
                .thenReturn(Optional.of(fetched));   // after sync

        // важно: syncService е void → не return-ва нищо
        doNothing().when(syncService)
                .syncData(eq("TSLA"), any(), any());

        BigDecimal price = service.getLatestPrice("TSLA");

        assertThat(price).isEqualByComparingTo("250.00");
        verify(syncService).syncData(eq("TSLA"), any(), any());
    }

    @Test
    void getLatestPrice_returnsZero_whenNoDataAfterSync() {
        when(priceBarRepository.findTopByTickerOrderByBarDateDesc("NVDA"))
                .thenReturn(Optional.empty())  // before
                .thenReturn(Optional.empty()); // after sync

        doNothing().when(syncService)
                .syncData(eq("NVDA"), any(), any());

        BigDecimal price = service.getLatestPrice("NVDA");

        assertThat(price).isEqualByComparingTo(BigDecimal.ZERO);
    }

    // ── getHistoricalBars ────────────────────────────────────────────────────

    @Test
    void getHistoricalBars_returnsDbData_whenNoCacheGap() {
        LocalDate from = LocalDate.now().minusDays(30);
        LocalDate to   = LocalDate.now();
        PriceBar bar   = priceBarWithClose("MSFT", new BigDecimal("400.00"));

        doNothing().when(syncService)
                .ensureDataExists("MSFT", from, to);

        when(priceBarRepository.findByTickerAndBarDateBetweenOrderByBarDateAsc("MSFT", from, to))
                .thenReturn(List.of(bar));

        List<PriceBar> result = service.getHistoricalBars("MSFT", from, to);

        assertThat(result).hasSize(1);
        verify(syncService).ensureDataExists("MSFT", from, to);
    }

    @Test
    void getHistoricalBars_gapFills_whenDbDataPartial() {
        LocalDate from = LocalDate.now().minusDays(10);
        LocalDate to   = LocalDate.now();

        doNothing().when(syncService)
                .ensureDataExists("GOOGL", from, to);

        when(priceBarRepository.findByTickerAndBarDateBetweenOrderByBarDateAsc("GOOGL", from, to))
                .thenReturn(List.of());

        service.getHistoricalBars("GOOGL", from, to);

        verify(syncService).ensureDataExists("GOOGL", from, to);
    }

    // ── Fixtures ─────────────────────────────────────────────────────────────

    private PriceBar priceBarWithClose(String ticker, BigDecimal adjClose) {
        PriceBarDto dto = new PriceBarDto(
                ticker,
                LocalDate.now(),
                adjClose,   // open
                adjClose,   // high
                adjClose,   // low
                adjClose,   // close
                adjClose,   // adjClose
                0L          // volume
        );
        return PriceBar.from(dto);
    }

    private PriceBarDto priceBarDto(String ticker) {
        return new PriceBarDto(
                ticker,
                LocalDate.now(),
                new java.math.BigDecimal("100.00"),
                new java.math.BigDecimal("105.00"),
                new java.math.BigDecimal("99.00"),
                new java.math.BigDecimal("103.00"),
                new java.math.BigDecimal("103.00"),
                1_000_000L
        );
    }
}
