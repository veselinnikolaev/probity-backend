package me.veselin.probity.portfolio.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.veselin.probity.marketdata.domain.PriceBar;
import me.veselin.probity.marketdata.port.MarketDataPort;
import me.veselin.probity.portfolio.domain.Portfolio;
import me.veselin.probity.portfolio.domain.PortfolioPosition;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.*;

@Service
@Slf4j
@RequiredArgsConstructor
public class PortfolioValuationService {
    private final MarketDataPort marketDataPort;

    // ── Total Value ──────────────────────────────────────────────────────────

    public double computeTotalValue(Portfolio portfolio) {
        log.debug("Computing total portfolio value, positions={}", portfolio.getPositions().size());

        return portfolio.getPositions().stream().mapToDouble(pos -> {
            String ticker = pos.getAsset().getTicker();

            try {
                double price = marketDataPort.getLatestPrice(ticker).doubleValue();
                return price * pos.getQuantity().doubleValue();
            } catch (Exception e) {
                log.error("Failed to fetch latest price for ticker={}", ticker, e);
                return 0.0;
            }

        }).sum();
    }

    // ── Value Series ─────────────────────────────────────────────────────────

    public Map<LocalDate, Double> buildValueSeries(
            List<PortfolioPosition> positions, LocalDate from, LocalDate to) {

        log.debug("Building value series for {} positions, range={} - {}", positions.size(), from, to);

        Map<LocalDate, Double> valueByDate = new TreeMap<>();

        for (PortfolioPosition pos : positions) {
            String ticker = pos.getAsset().getTicker();

            List<PriceBar> bars;
            try {
                bars = marketDataPort.getHistoricalBars(ticker, from, to);
            } catch (Exception e) {
                log.error("Failed to fetch historical bars for ticker={}", ticker, e);
                continue;
            }

            if (bars == null || bars.isEmpty()) {
                log.warn("No historical data for ticker={} in range {} - {}", ticker, from, to);
                continue;
            }

            log.trace("Bars fetched for ticker={} count={}", ticker, bars.size());

            double qty = pos.getQuantity().doubleValue();

            for (PriceBar bar : bars) {
                valueByDate.merge(
                        bar.getBarDate(),
                        bar.getAdjClose().doubleValue() * qty,
                        Double::sum
                );
            }
        }

        return valueByDate;
    }

    // ── Daily Returns ────────────────────────────────────────────────────────

    public List<Double> buildDailyReturns(
            List<PortfolioPosition> positions, LocalDate from, LocalDate to) {

        log.debug("Building daily returns for {} positions, range={} - {}", positions.size(), from, to);

        Map<String, List<PriceBar>> barsByTicker = new LinkedHashMap<>();

        // Fetch bars
        for (PortfolioPosition pos : positions) {
            String ticker = pos.getAsset().getTicker();

            try {
                List<PriceBar> bars = marketDataPort.getHistoricalBars(ticker, from, to);

                if (bars == null || bars.size() < 2) {
                    log.warn("Insufficient bars for ticker={} (size={})", ticker,
                            bars == null ? 0 : bars.size());
                    continue;
                }

                barsByTicker.put(ticker, bars);

            } catch (Exception e) {
                log.error("Failed to fetch bars for ticker={}", ticker, e);
            }
        }

        if (barsByTicker.isEmpty()) {
            log.warn("No valid bar data available for return calculation");
            return List.of();
        }

        int len = barsByTicker.values().stream()
                .mapToInt(List::size)
                .min()
                .orElse(0);

        if (len < 2) {
            log.warn("Not enough aligned data points for return calculation");
            return List.of();
        }

        List<Double> portfolioReturns = new ArrayList<>(len - 1);

        for (int i = 1; i < len; i++) {
            double weightedReturn = 0;
            double totalValue = 0;

            // total portfolio value at t-1
            for (PortfolioPosition pos : positions) {
                List<PriceBar> bars = barsByTicker.get(pos.getAsset().getTicker());
                if (bars == null) continue;

                double qty = pos.getQuantity().doubleValue();
                double prev = bars.get(i - 1).getAdjClose().doubleValue();
                totalValue += prev * qty;
            }

            if (totalValue == 0) {
                log.warn("Total portfolio value is zero at index={}", i);
                portfolioReturns.add(0.0);
                continue;
            }

            // weighted returns
            for (PortfolioPosition pos : positions) {
                List<PriceBar> bars = barsByTicker.get(pos.getAsset().getTicker());
                if (bars == null) continue;

                double qty = pos.getQuantity().doubleValue();

                double weight =
                        (bars.get(i - 1).getAdjClose().doubleValue() * qty) / totalValue;

                double ret = bars.get(i).dailyReturn(bars.get(i - 1));

                weightedReturn += weight * ret;
            }

            portfolioReturns.add(weightedReturn);
        }

        return portfolioReturns;
    }
}