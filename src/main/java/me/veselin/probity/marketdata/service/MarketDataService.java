package me.veselin.probity.marketdata.service;

import lombok.RequiredArgsConstructor;
import me.veselin.probity.marketdata.adapter.YahooFinanceAdapter;
import me.veselin.probity.marketdata.domain.PriceBar;
import me.veselin.probity.marketdata.dto.PriceBarDto;
import me.veselin.probity.marketdata.repository.PriceBarRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class MarketDataService {

    private final PriceBarRepository priceBarRepository;
    private final YahooFinanceAdapter yahooAdapter;

    public List<PriceBar> getBars(String symbol, int daysBack) {
        LocalDate requiredFrom = LocalDate.now().minusDays(daysBack);

        Optional<PriceBar> latestBar =
                priceBarRepository.findTopBySymbolOrderByBarDateDesc(symbol);

        boolean isStale = latestBar
                .map(bar -> bar.getBarDate().isBefore(LocalDate.now().minusDays(1)))
                .orElse(true);

        if (isStale) {
            LocalDate fetchFrom = latestBar
                    .map(bar -> bar.getBarDate().plusDays(1))
                    .orElse(requiredFrom);

            refreshFromYahoo(symbol, fetchFrom);
        }

        return priceBarRepository.findBySymbolAndBarDateBetweenOrderByBarDateAsc(
                symbol, requiredFrom, LocalDate.now()
        );
    }

    private void refreshFromYahoo(String symbol, LocalDate from) {
        List<PriceBarDto> fetched = yahooAdapter.fetchDailyBars(symbol, from);

        List<PriceBar> entities = fetched.stream()
                .map(PriceBar::from)
                .toList();

        priceBarRepository.saveAll(entities);
    }
}