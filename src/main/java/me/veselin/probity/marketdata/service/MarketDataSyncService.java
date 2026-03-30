package me.veselin.probity.marketdata.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.veselin.probity.marketdata.domain.PriceBar;
import me.veselin.probity.marketdata.dto.PriceBarDto;
import me.veselin.probity.marketdata.finance.FinanceAdapter;
import me.veselin.probity.marketdata.repository.PriceBarRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service
@Slf4j
@RequiredArgsConstructor
public class MarketDataSyncService {

    private final PriceBarRepository repository;
    private final FinanceAdapter financeAdapter;

    // One lock object per ticker symbol
    private final ConcurrentHashMap<String, Object> tickerLocks = new ConcurrentHashMap<>();

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void syncData(String ticker, LocalDate from, LocalDate to) {
        log.debug("Syncing data ticker={} range={} - {}", ticker, from, to);

        Object lock = tickerLocks.computeIfAbsent(ticker, k -> new Object());

        synchronized (lock) {
            Optional<LocalDate> latestInDb = repository.findMaxBarDate(ticker, from, to);

            LocalDate fetchFrom = latestInDb
                    .map(latest -> latest.plusDays(1))
                    .orElse(from);

            if (fetchFrom.isAfter(to)) {
                log.debug("No sync needed for ticker={} (already up-to-date)", ticker);
                return;
            }

            log.debug("Fetching missing data for ticker={} from {} to {}", ticker, fetchFrom, to);
            fetchAndPersist(ticker, fetchFrom, to);
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void ensureDataExists(String ticker, LocalDate from, LocalDate to) {
        log.debug("Ensuring data exists ticker={} range={} - {}", ticker, from, to);

        Object lock = tickerLocks.computeIfAbsent(ticker, k -> new Object());

        synchronized (lock) {
            Optional<LocalDate> latestInDb = repository.findMaxBarDate(ticker, from, to);

            LocalDate fetchFrom = latestInDb
                    .map(latest -> latest.plusDays(1))
                    .orElse(from);

            if (fetchFrom.isAfter(to)) {
                log.trace("Data already complete for ticker={}", ticker);
                return;
            }

            log.debug("Gap-filling ticker={} from {} to {}", ticker, fetchFrom, to);
            fetchAndPersist(ticker, fetchFrom, to);
        }
    }

    private void fetchAndPersist(String symbol, LocalDate from, LocalDate to) {
        log.debug("Fetching bars from external API ticker={} range={} - {}", symbol, from, to);

        List<PriceBarDto> bars;

        try {
            bars = financeAdapter.fetchDailyBars(symbol, from, to);
        } catch (Exception e) {
            log.error("Failed to fetch bars from adapter for ticker={}", symbol, e);
            return;
        }

        if (bars == null || bars.isEmpty()) {
            log.warn("No data returned from adapter for ticker={} range={} - {}", symbol, from, to);
            return;
        }

        log.trace("Fetched {} bars from adapter for ticker={}", bars.size(), symbol);

        Set<LocalDate> existingDates;
        try {
            existingDates = new HashSet<>(
                    repository.findBarDatesByTickerAndBarDateBetween(symbol, from, to)
            );
        } catch (Exception e) {
            log.error("Failed to fetch existing dates for ticker={}", symbol, e);
            return;
        }

        List<PriceBar> toSave = bars.stream()
                .filter(dto -> dto.barDate() != null)
                .filter(dto -> !dto.barDate().isBefore(from) && !dto.barDate().isAfter(to))
                .filter(dto -> !existingDates.contains(dto.barDate()))
                .map(PriceBar::from)
                .toList();

        if (toSave.isEmpty()) {
            log.debug("No new bars to persist for ticker={}", symbol);
            return;
        }

        try {
            repository.saveAllAndFlush(toSave);
            log.info("Persisted {} new bars for ticker={}", toSave.size(), symbol);
        } catch (Exception e) {
            log.error("Failed to persist bars for ticker={}", symbol, e);
        }
    }
}