package me.veselin.probity.marketdata.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.veselin.probity.common.util.TradingUtil;
import me.veselin.probity.marketdata.domain.PriceBar;
import me.veselin.probity.marketdata.port.MarketDataPort;
import me.veselin.probity.marketdata.repository.PriceBarRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Service
@Slf4j
@RequiredArgsConstructor
/**
 * Application service that serves market prices from local storage and
 * backfills missing ranges from the external data adapter on demand.
 */
public class MarketDataService implements MarketDataPort {

    @Value("${probity.marketdata.latest-price-lookback-days:14}")
    private int latestPriceLookbackDays;

    private final PriceBarRepository priceBarRepository;
    private final MarketDataSyncService marketDataSyncService;

    // One monitor per ticker keeps concurrent requests for the same symbol serialized
    // without blocking unrelated tickers against each other.
    private final ConcurrentHashMap<String, Object> tickerLocks = new ConcurrentHashMap<>();

    @Override
    /**
     * Returns the most recent known adjusted close for a ticker.
     * Uses an on-demand sync window so reads stay resilient when bars are stale.
     */
    public BigDecimal getLatestPrice(String ticker) {
        String upper = ticker.toUpperCase();
        log.trace("Fetching latest price for ticker={}", upper);

        LocalDate from = LocalDate.now().minusDays(latestPriceLookbackDays);
        LocalDate to   = LocalDate.now();

        try {
            ensureDataExists(upper, from, to);
        } catch (Exception e) {
            log.error("Sync failed for ticker={} during latest-price lookup", upper, e);
        }

        return priceBarRepository
                .findTopByTickerOrderByBarDateDesc(upper)
                .map(bar -> {
                    log.trace("Latest price ticker={} date={} price={}", upper, bar.getBarDate(), bar.getAdjClose());
                    return bar.getAdjClose();
                })
                .orElseGet(() -> {
                    log.error("No price data available for ticker={} after sync — returning ZERO sentinel", upper);
                    return BigDecimal.ZERO;
                });
    }

    @Override
    /**
     * Returns historical daily bars for a ticker and date range.
     * Missing segments are fetched before read to keep callers stateless.
     */
    public List<PriceBar> getHistoricalBars(String ticker, LocalDate from, LocalDate to) {
        String upper = ticker.toUpperCase();
        log.trace("Fetching historical bars ticker={} range={} - {}", upper, from, to);

        try {
            ensureDataExists(upper, from, to);
        } catch (Exception e) {
            log.error("Failed to ensure data for ticker={} range={} - {}", upper, from, to, e);
        }

        List<PriceBar> bars = priceBarRepository
                .findByTickerAndBarDateBetweenOrderByBarDateAsc(upper, from, to);

        if (bars.isEmpty()) {
            log.warn("No historical bars in DB after sync for ticker={} range={} - {}", upper, from, to);
        } else {
            log.trace("Returning {} bars from DB for ticker={}", bars.size(), upper);
        }

        return bars;
    }

    /**
     * Ensures that all trading bars for {@code ticker} in [from, to] exist in the
     * database, fetching any missing gaps from the remote adapter.
     *
     * <p>Does NOT use @Transactional at the method level — each fetchAndPersist
     * call opens its own REQUIRES_NEW transaction so partial successes are committed
     * immediately. The per-ticker lock serializes concurrent callers for the same symbol.
     */
    private void ensureDataExists(String ticker, LocalDate from, LocalDate to) {
        log.info("ensureDataExists ENTER ticker={} from={} to={}", ticker, from, to);

        LocalDate safeTo = TradingUtil.clampToLastCompletedTradingDay(to);
        log.info("ensureDataExists ticker={} safeTo={}", ticker, safeTo);

        if (from.isAfter(safeTo)) {
            log.info("Skipping: from={} is after safeTo={}", from, safeTo);
            return;
        }

        LocalDate safeFrom = TradingUtil.isWeekend(from) ? TradingUtil.nextTradingDay(from) : from;
        log.info("ensureDataExists ticker={} safeFrom={}", ticker, safeFrom);

        if (safeFrom.isAfter(safeTo)) {
            log.info("Skipping: safeFrom={} is after safeTo={}", safeFrom, safeTo);
            return;
        }

        Object lock = tickerLocks.computeIfAbsent(ticker, k -> new Object());

        synchronized (lock) {
            try {
                Optional<LocalDate> minOpt = priceBarRepository.findMinBarDate(ticker, safeFrom, safeTo);
                Optional<LocalDate> maxOpt = priceBarRepository.findMaxBarDate(ticker, safeFrom, safeTo);

                log.info("gap check ticker={} minOpt={} maxOpt={}", ticker, minOpt, maxOpt);

                if (minOpt.isEmpty()) {
                    log.info("No data → full fetch ticker={} {} - {}", ticker, safeFrom, safeTo);
                    marketDataSyncService.fetchAndPersist(ticker, safeFrom, safeTo);
                    return;
                }

                LocalDate min = minOpt.get();
                LocalDate max = maxOpt.get();

                if (min.isAfter(safeFrom)) {
                    LocalDate leftTo = TradingUtil.prevTradingDay(min);
                    log.info("Left gap ticker={} leftFrom={} leftTo={}", ticker, safeFrom, leftTo);
                    if (!leftTo.isBefore(safeFrom)) {
                        marketDataSyncService.fetchAndPersist(ticker, safeFrom, leftTo);
                    }
                }

                if (max.isBefore(safeTo)) {
                    LocalDate rightFrom = TradingUtil.nextTradingDay(max);
                    log.info("Right gap ticker={} rightFrom={} rightTo={}", ticker, rightFrom, safeTo);
                    if (!rightFrom.isAfter(safeTo)) {
                        marketDataSyncService.fetchAndPersist(ticker, rightFrom, safeTo);
                    }
                }
            } catch (Exception e) {
                log.error("Exception inside synchronized block ticker={}", ticker, e);
            }
        }
    }
}