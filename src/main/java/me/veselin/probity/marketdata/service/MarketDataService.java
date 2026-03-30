package me.veselin.probity.marketdata.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.veselin.probity.marketdata.domain.PriceBar;
import me.veselin.probity.marketdata.port.MarketDataPort;
import me.veselin.probity.marketdata.repository.PriceBarRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@Service
@Slf4j
@RequiredArgsConstructor
public class MarketDataService implements MarketDataPort {

    private final PriceBarRepository priceBarRepository;
    private final MarketDataSyncService syncService;

    @Override
    // @Cacheable(value = "latestPrice", key = "#ticker.toUpperCase()")
    public BigDecimal getLatestPrice(String ticker) {
        String upper = ticker.toUpperCase();

        log.trace("Fetching latest price for ticker={}", upper);

        return priceBarRepository
                .findTopByTickerOrderByBarDateDesc(upper)
                .map(bar -> {
                    log.trace("Latest price found in DB ticker={} date={}", upper, bar.getBarDate());
                    return bar.getAdjClose();
                })
                .orElseGet(() -> {
                    log.warn("No latest price in DB for ticker={}, triggering sync", upper);

                    try {
                        syncService.syncData(upper, LocalDate.now().minusDays(7), LocalDate.now());
                    } catch (Exception e) {
                        log.error("Sync failed for ticker={}", upper, e);
                        return BigDecimal.ZERO;
                    }

                    return priceBarRepository
                            .findTopByTickerOrderByBarDateDesc(upper)
                            .map(bar -> {
                                log.debug("Recovered latest price after sync ticker={} date={}", upper, bar.getBarDate());
                                return bar.getAdjClose();
                            })
                            .orElseGet(() -> {
                                log.error("Still no price after sync ticker={}", upper);
                                return BigDecimal.ZERO;
                            });
                });
    }

    @Override
    // @Cacheable(value = "historicalBars", key = "{#ticker.toUpperCase(), #from, #to}")
    public List<PriceBar> getHistoricalBars(String ticker, LocalDate from, LocalDate to) {
        String upper = ticker.toUpperCase();

        log.trace("Fetching historical bars ticker={} range={} - {}", upper, from, to);

        try {
            syncService.ensureDataExists(upper, from, to);
        } catch (Exception e) {
            log.error("Failed to ensure data for ticker={} range={} - {}", upper, from, to, e);
        }

        List<PriceBar> bars = priceBarRepository
                .findByTickerAndBarDateBetweenOrderByBarDateAsc(upper, from, to);

        if (bars.isEmpty()) {
            log.warn("No historical data found ticker={} range={} - {}", upper, from, to);
        } else {
            log.trace("Fetched {} bars from DB ticker={}", bars.size(), upper);
        }

        return bars;
    }
}