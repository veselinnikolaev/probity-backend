package me.veselin.probity.marketdata.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.veselin.probity.marketdata.domain.PriceBar;
import me.veselin.probity.marketdata.dto.PriceBarDto;
import me.veselin.probity.marketdata.finance.YahooFinanceAdapter;
import me.veselin.probity.marketdata.repository.PriceBarRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Service
@Slf4j
@RequiredArgsConstructor
public class MarketDataSyncService {
    private final PriceBarRepository repository;
    private final YahooFinanceAdapter yahooAdapter;

    @Transactional
    public void syncData(String ticker, LocalDate from, LocalDate to) {
        Optional<LocalDate> latestInDb = repository.findMaxBarDate(ticker, from, to);

        LocalDate fetchFrom = latestInDb
                .map(latest -> latest.plusDays(1))
                .orElse(from);

        if (fetchFrom.isBefore(to) || fetchFrom.equals(to)) {
            fetchAndPersist(ticker, fetchFrom, to);
        }
    }

    public void ensureDataExists(String ticker, LocalDate from, LocalDate to) {
        Optional<LocalDate> latestInDb = repository.findMaxBarDate(ticker, from, to);

        LocalDate fetchFrom = latestInDb
                .map(latest -> latest.plusDays(1))
                .orElse(from);

        if (!fetchFrom.isAfter(to)) {
            log.debug("Gap-filling {} from {} to {}", ticker, fetchFrom, to);
            fetchAndPersist(ticker, fetchFrom, to);
        }
    }

    private void fetchAndPersist(String symbol, LocalDate from, LocalDate to) {
        List<PriceBarDto> bars = yahooAdapter.fetchDailyBars(symbol, from, to);

        List<PriceBar> toSave = bars.stream()
                .filter(dto -> !dto.barDate().isBefore(from) && !dto.barDate().isAfter(to))
                .filter(dto -> !repository.existsByTickerAndBarDate(symbol, dto.barDate()))
                .map(PriceBar::from)
                .toList();

        if (!toSave.isEmpty()) {
            repository.saveAll(toSave);
            log.info("Persisted {} bars for {}", toSave.size(), symbol);
        }
    }
}
