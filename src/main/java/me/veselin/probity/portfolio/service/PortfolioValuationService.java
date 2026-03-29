package me.veselin.probity.portfolio.service;

import lombok.RequiredArgsConstructor;
import me.veselin.probity.marketdata.domain.PriceBar;
import me.veselin.probity.marketdata.port.MarketDataPort;
import me.veselin.probity.portfolio.domain.Portfolio;
import me.veselin.probity.portfolio.domain.PortfolioPosition;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.*;

@Service
@RequiredArgsConstructor
public class PortfolioValuationService {
    private final MarketDataPort marketDataPort;

    public double computeTotalValue(Portfolio portfolio) {
        return portfolio.getPositions().stream().mapToDouble(pos ->
                marketDataPort.getLatestPrice(pos.getAsset().getTicker()).doubleValue()
                        * pos.getQuantity().doubleValue()
        ).sum();
    }

    /**
     * Builds a date-keyed portfolio value series by summing
     * (adjClose × quantity) across all positions per trading day.
     * Uses TreeMap to keep dates sorted oldest → newest.
     */
    public Map<LocalDate, Double> buildValueSeries(
            List<PortfolioPosition> positions, LocalDate from, LocalDate to) {

        Map<LocalDate, Double> valueByDate = new TreeMap<>();
        for (PortfolioPosition pos : positions) {
            List<PriceBar> bars = marketDataPort.getHistoricalBars(
                    pos.getAsset().getTicker(), from, to);
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

    /**
     * Computes portfolio-level daily returns by weighting each position's
     * bar.dailyReturn(prev) by its value weight on that day.
     * This is more accurate than computing returns on the summed value series.
     */
    public List<Double> buildDailyReturns(
            List<PortfolioPosition> positions, LocalDate from, LocalDate to) {

        // Collect per-ticker bar lists
        Map<String, List<PriceBar>> barsByTicker = new LinkedHashMap<>();
        for (PortfolioPosition pos : positions) {
            barsByTicker.put(
                    pos.getAsset().getTicker(),
                    marketDataPort.getHistoricalBars(pos.getAsset().getTicker(), from, to)
            );
        }

        // Find the shortest bar list length to stay aligned
        int len = barsByTicker.values().stream()
                .mapToInt(List::size).min().orElse(0);
        if (len < 2) return List.of();

        List<Double> portfolioReturns = new ArrayList<>(len - 1);

        for (int i = 1; i < len; i++) {
            double weightedReturn = 0;
            double totalValue = 0;

            // First pass — total portfolio value on previous day
            for (PortfolioPosition pos : positions) {
                List<PriceBar> bars = barsByTicker.get(pos.getAsset().getTicker());
                double qty = pos.getQuantity().doubleValue();
                double prev = bars.get(i - 1).getAdjClose().doubleValue();
                totalValue += prev * qty;
            }

            // Second pass — weight each position's return by its previous-day value
            for (PortfolioPosition pos : positions) {
                List<PriceBar> bars = barsByTicker.get(pos.getAsset().getTicker());
                double qty = pos.getQuantity().doubleValue();
                double weight = totalValue > 0
                        ? (bars.get(i - 1).getAdjClose().doubleValue() * qty) / totalValue
                        : 0;
                // Use the domain method — adjClose-adjusted, null-safe
                double ret = bars.get(i).dailyReturn(bars.get(i - 1));
                weightedReturn += weight * ret;
            }

            portfolioReturns.add(weightedReturn);
        }
        return portfolioReturns;
    }

}