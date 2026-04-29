package me.veselin.probity.marketdata.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.veselin.probity.marketdata.domain.PriceBar;
import me.veselin.probity.marketdata.enumeration.ZoneIdEnumeration;
import me.veselin.probity.marketdata.port.MarketDataPort;
import me.veselin.probity.marketdata.repository.PriceBarRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Service
@Slf4j
@RequiredArgsConstructor
public class MarketDataService implements MarketDataPort {

    // How far back to look when seeding the DB for a latest-price query.
    // 14 days covers weekends, US holidays, and short market closures.
    @Value("${probity.marketdata.latest-price-lookback-days:14}")
    private int latest_price_lookback_days;

    // US Eastern — markets close at 16:00 ET. Yesterday's bar is complete once
    // the NYSE closing bell rings, so we anchor "last completed day" to ET, not UTC.
    private static final ZoneId MARKET_ZONE = ZoneId.of(ZoneIdEnumeration.NEW_YORK.getZoneId());

    private final PriceBarRepository priceBarRepository;
    private final MarketDataSyncService syncService;

    // One monitor per ticker keeps concurrent requests for the same symbol serialized
    // without blocking unrelated tickers against each other.
    private final ConcurrentHashMap<String, Object> tickerLocks = new ConcurrentHashMap<>();

    @Override
    public BigDecimal getLatestPrice(String ticker) {
        String upper = ticker.toUpperCase();
        log.trace("Fetching latest price for ticker={}", upper);

        // Sync a recent window first so the DB is warm. ensureDataExists handles
        // the last-completed-trading-day clamp internally, so passing LocalDate.now()
        // as `to` is safe — it will not request data Yahoo hasn't published yet.
        LocalDate from = LocalDate.now().minusDays(latest_price_lookback_days);
        LocalDate to   = LocalDate.now();

        try {
            ensureDataExists(upper, from, to);
        } catch (Exception e) {
            log.error("Sync failed for ticker={} during latest-price lookup", upper, e);
            // Fall through: if the DB already has a recent bar we can still serve it.
        }

        return priceBarRepository
                .findTopByTickerOrderByBarDateDesc(upper)
                .map(bar -> {
                    log.trace("Latest price ticker={} date={} price={}", upper, bar.getBarDate(), bar.getAdjClose());
                    return bar.getAdjClose();
                })
                .orElseGet(() -> {
                    // At this point the sync ran (or failed) and the DB still has nothing.
                    // Returning ZERO signals a data-unavailable condition — callers must
                    // treat ZERO as "unknown", not as a legitimate market price.
                    log.error("No price data available for ticker={} after sync — returning ZERO sentinel", upper);
                    return BigDecimal.ZERO;
                });
    }

    @Override
    public List<PriceBar> getHistoricalBars(String ticker, LocalDate from, LocalDate to) {
        String upper = ticker.toUpperCase();
        log.trace("Fetching historical bars ticker={} range={} - {}", upper, from, to);

        try {
            ensureDataExists(upper, from, to);
        } catch (Exception e) {
            log.error("Failed to ensure data for ticker={} range={} - {}", upper, from, to, e);
            // Fall through: return whatever the DB already has rather than failing
            // the entire request because of a transient adapter error.
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
     * <p>The method deliberately does NOT use @Transactional at the method level.
     * Each call to fetchAndPersist opens its own REQUIRES_NEW transaction
     * so that partial successes are committed immediately rather than rolled back
     * together. The per-ticker lock ensures that concurrent callers for the same
     * symbol are serialized at the application layer.
     */
    private void ensureDataExists(String ticker, LocalDate from, LocalDate to) {
        // Hard ceiling: never request data Yahoo Finance hasn't published yet.
        log.info("ensureDataExists ENTER ticker={} from={} to={}", ticker, from, to); // ← INFO so it always prints
        LocalDate safeTo = clampToLastCompletedTradingDay(to);
        log.info("ensureDataExists ticker={} safeTo={} safeFrom-raw={}", ticker, safeTo, from);
        if (from.isAfter(safeTo)) {
            log.info("Skipping: from {} is after safeTo {}", from, safeTo);
            return;
        }
        LocalDate safeFrom = isWeekend(from) ? nextTradingDay(from) : from;
        log.info("ensureDataExists ticker={} safeFrom={}", ticker, safeFrom);
        if (safeFrom.isAfter(safeTo)) {
            log.info("Skipping: safeFrom {} is after safeTo {}", safeFrom, safeTo); // ← make this visible
            return;
        }

        Object lock = tickerLocks.computeIfAbsent(ticker, k -> new Object());

        synchronized (lock) {
            synchronized (lock) {
                try {
                    Optional<LocalDate> minOpt = priceBarRepository.findMinBarDate(ticker, safeFrom, safeTo);
                    Optional<LocalDate> maxOpt = priceBarRepository.findMaxBarDate(ticker, safeFrom, safeTo);

                    log.info("gap check ticker={} minOpt={} maxOpt={}", ticker, minOpt, maxOpt);

                    if (minOpt.isEmpty()) {
                        log.info("No data → full fetch ticker={} {} - {}", ticker, safeFrom, safeTo);
                        syncService.fetchAndPersist(ticker, safeFrom, safeTo);
                        return;
                    }

                    LocalDate min = minOpt.get();
                    LocalDate max = maxOpt.get();

                    if (min.isAfter(safeFrom)) {
                        LocalDate leftTo = prevTradingDay(min);
                        log.info("Left gap ticker={} leftFrom={} leftTo={}", ticker, safeFrom, leftTo);
                        if (!leftTo.isBefore(safeFrom)) {
                            syncService.fetchAndPersist(ticker, safeFrom, leftTo);
                        }
                    }

                    if (max.isBefore(safeTo)) {
                        LocalDate rightFrom = nextTradingDay(max);
                        log.info("Right gap ticker={} rightFrom={} rightTo={}", ticker, rightFrom, safeTo);
                        if (!rightFrom.isAfter(safeTo)) {
                            syncService.fetchAndPersist(ticker, rightFrom, safeTo);
                        }
                    }
                } catch (Exception e) {
                    log.error("Exception inside synchronized block ticker={}", ticker, e);
                }
            }
        }
    }

    /**
     * Returns the most recent trading day whose closing bar is definitively
     * available. Anchored to US Eastern Time because that is when NYSE closes.
     * Skips backwards over weekends (holidays are not enumerated here — Yahoo
     * simply returns no bars for them, which is handled gracefully by callers).
     */
    private LocalDate clampToLastCompletedTradingDay(LocalDate requested) {
        LocalDate candidate = LocalDate.now(MARKET_ZONE).minusDays(1);
        while (isWeekend(candidate)) {
            candidate = candidate.minusDays(1);
        }
        return requested.isBefore(candidate) ? requested : candidate;
    }

    private LocalDate nextTradingDay(LocalDate date) {
        LocalDate next = date.plusDays(1);
        while (isWeekend(next)) {
            next = next.plusDays(1);
        }
        return next;
    }

    private LocalDate prevTradingDay(LocalDate date) {
        LocalDate prev = date.minusDays(1);
        while (isWeekend(prev)) {
            prev = prev.minusDays(1);
        }
        return prev;
    }

    private boolean isWeekend(LocalDate date) {
        return date.getDayOfWeek() == DayOfWeek.SATURDAY
                || date.getDayOfWeek() == DayOfWeek.SUNDAY;
    }
}