package me.veselin.probity.marketdata.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.veselin.probity.marketdata.domain.PriceBar;
import me.veselin.probity.marketdata.port.MarketDataPort;
import me.veselin.probity.marketdata.repository.PriceBarRepository;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@Service
@Slf4j
@RequiredArgsConstructor
public class MarketDataService implements MarketDataPort {

    private final PriceBarRepository  priceBarRepository;
    private final MarketDataSyncService syncService;

    @Override
    @Cacheable(value = "latestPrice", key = "#ticker.toUpperCase()")
    public BigDecimal getLatestPrice(String ticker) {
        String upper = ticker.toUpperCase();
        return priceBarRepository
                .findTopByTickerOrderByBarDateDesc(upper)
                .map(PriceBar::getAdjClose)
                .orElseGet(() -> {
                    // Fetch and save (this internal call won't be cached, which is good)
                    syncService.syncData(upper, LocalDate.now().minusDays(7), LocalDate.now());
                    return priceBarRepository
                            .findTopByTickerOrderByBarDateDesc(upper)
                            .map(PriceBar::getAdjClose)
                            .orElse(BigDecimal.ZERO);
                });
    }

    @Override
    @Cacheable(value = "historicalBars", key = "{#ticker.toUpperCase(), #from, #to}")
    public List<PriceBar> getHistoricalBars(String ticker, LocalDate from, LocalDate to) {
        String upper = ticker.toUpperCase();
        syncService.ensureDataExists(upper, from, to);
        return priceBarRepository
                .findByTickerAndBarDateBetweenOrderByBarDateAsc(upper, from, to);
    }
}