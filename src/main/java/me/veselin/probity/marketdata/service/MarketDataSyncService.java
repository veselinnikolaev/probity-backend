package me.veselin.probity.marketdata.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.veselin.probity.marketdata.domain.PriceBar;
import me.veselin.probity.marketdata.dto.PriceBarDto;
import me.veselin.probity.marketdata.finance.FinanceAdapter;
import me.veselin.probity.marketdata.repository.PriceBarRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Service
@Slf4j
@RequiredArgsConstructor
public class MarketDataSyncService {

    private final PriceBarRepository repository;
    private final FinanceAdapter financeAdapter;

    // One lock object per ticker symbol
    private final ConcurrentHashMap<String, Object> tickerLocks = new ConcurrentHashMap<>();

    @Transactional
    public void syncData(String ticker, LocalDate from, LocalDate to) {
        Object lock = tickerLocks.computeIfAbsent(ticker, k -> new Object());
        synchronized (lock) {
            Optional<LocalDate> latestInDb = repository.findMaxBarDate(ticker, from, to);

            LocalDate fetchFrom = latestInDb
                    .map(latest -> latest.plusDays(1))
                    .orElse(from);

            if (!fetchFrom.isAfter(to)) {
                fetchAndPersist(ticker, fetchFrom, to);
            }
        }
    }

    @Transactional
    public void ensureDataExists(String ticker, LocalDate from, LocalDate to) {
        Object lock = tickerLocks.computeIfAbsent(ticker, k -> new Object());
        synchronized (lock) {
            Optional<LocalDate> latestInDb = repository.findMaxBarDate(ticker, from, to);

            LocalDate fetchFrom = latestInDb
                    .map(latest -> latest.plusDays(1))
                    .orElse(from);

            if (!fetchFrom.isAfter(to)) {
                log.debug("Gap-filling {} from {} to {}", ticker, fetchFrom, to);
                fetchAndPersist(ticker, fetchFrom, to);
            }
        }
    }
    private void fetchAndPersist(String symbol, LocalDate from, LocalDate to) {
        List<PriceBarDto> bars = financeAdapter.fetchDailyBars(symbol, from, to);

        // One query to get all existing dates in range instead of N queries
        Set<LocalDate> existingDates = new HashSet<>(
                repository.findBarDatesByTickerAndBarDateBetween(symbol, from, to)
        );

        List<PriceBar> toSave = bars.stream()
                .filter(dto -> !dto.barDate().isBefore(from) && !dto.barDate().isAfter(to))
                .filter(dto -> !existingDates.contains(dto.barDate()))
                .map(PriceBar::from)
                .toList();

        if (!toSave.isEmpty()) {
            repository.saveAll(toSave);
            log.info("Persisted {} bars for {}", toSave.size(), symbol);
        }
    }
}
