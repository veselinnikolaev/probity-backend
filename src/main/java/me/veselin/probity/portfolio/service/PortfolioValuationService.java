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
import java.util.stream.Collectors;

@Service
@Slf4j
@RequiredArgsConstructor
public class PortfolioValuationService {

    private final MarketDataPort marketDataPort;

    // ── Price fetching ───────────────────────────────────────────────────────

    /**
     * Fetches the latest price for every distinct ticker in the portfolio.
     * Returns a map of ticker → price. Tickers that fail return 0.0 and are
     * logged — callers must treat 0.0 as "price unavailable", not a real value.
     */
    public Map<String, Double> fetchLatestPrices(List<PortfolioPosition> positions) {
        return positions.stream()
                .map(pos -> pos.getAsset().getTicker())
                .distinct()
                .collect(Collectors.toMap(
                        ticker -> ticker,
                        ticker -> {
                            try {
                                return marketDataPort.getLatestPrice(ticker).doubleValue();
                            } catch (Exception e) {
                                log.error("Failed to fetch latest price ticker={}", ticker, e);
                                return 0.0;
                            }
                        }
                ));
    }

    /**
     * Fetches historical bars for every distinct ticker over [from, to].
     * Tickers with no data are omitted from the result map entirely rather
     * than included with an empty list — callers should use getOrDefault.
     */
    public Map<String, List<PriceBar>> fetchBars(
            List<PortfolioPosition> positions, LocalDate from, LocalDate to) {

        Map<String, List<PriceBar>> result = new LinkedHashMap<>();

        for (PortfolioPosition pos : positions) {
            String ticker = pos.getAsset().getTicker();
            if (result.containsKey(ticker)) continue; // deduplicate

            try {
                List<PriceBar> bars = marketDataPort.getHistoricalBars(ticker, from, to);
                if (bars != null && !bars.isEmpty()) {
                    result.put(ticker, bars);
                } else {
                    log.warn("No bars returned for ticker={} range={} - {}", ticker, from, to);
                }
            } catch (Exception e) {
                log.error("Failed to fetch bars ticker={} range={} - {}", ticker, from, to, e);
            }
        }

        return result;
    }

    // ── Total Value ──────────────────────────────────────────────────────────

    /**
     * Computes total portfolio value from a pre-fetched price map.
     * Accepts the map rather than fetching internally so callers can reuse
     * a single fetch pass across multiple valuation calls.
     */
    public double computeTotalValue(
            List<PortfolioPosition> positions, Map<String, Double> priceByTicker) {

        return positions.stream().mapToDouble(pos -> {
            double price = priceByTicker.getOrDefault(pos.getAsset().getTicker(), 0.0);
            return price * pos.getQuantity().doubleValue();
        }).sum();
    }

    /**
     * Convenience overload for callers that don't have a pre-fetched price map.
     * Fetches prices internally — avoid in hot paths where multiple calls share
     * the same portfolio (e.g. getPortfolios iterating many portfolios).
     */
    public double computeTotalValue(Portfolio portfolio) {
        Map<String, Double> prices = fetchLatestPrices(portfolio.getPositions());
        return computeTotalValue(portfolio.getPositions(), prices);
    }

    // ── Value Series ─────────────────────────────────────────────────────────

    /**
     * Builds a date-ordered map of total portfolio value per trading day.
     * Accepts pre-fetched bars — call fetchBars first and pass the result here.
     */
    public Map<LocalDate, Double> buildValueSeries(
            List<PortfolioPosition> positions,
            Map<String, List<PriceBar>> barsByTicker) {

        Map<LocalDate, Double> valueByDate = new TreeMap<>();

        for (PortfolioPosition pos : positions) {
            String ticker = pos.getAsset().getTicker();
            List<PriceBar> bars = barsByTicker.get(ticker);

            if (bars == null) continue;

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
     * Convenience overload that fetches bars internally.
     * Prefer the map-accepting overload when bars are already available.
     */
    public Map<LocalDate, Double> buildValueSeries(
            List<PortfolioPosition> positions, LocalDate from, LocalDate to) {

        return buildValueSeries(positions, fetchBars(positions, from, to));
    }

    // ── Daily Returns ────────────────────────────────────────────────────────

    /**
     * Computes daily portfolio returns aligned by date.
     *
     * <p>Aligns series by date rather than by index to avoid cross-ticker
     * misalignment when tickers have different holiday gaps. Only dates
     * present in ALL included tickers contribute to the return calculation —
     * this ensures each day's weighted return is computed on a consistent
     * cross-section of the portfolio.
     *
     * <p>Accepts pre-fetched bars — call fetchBars first and pass the result here.
     */
    public List<Double> buildDailyReturns(
            List<PortfolioPosition> positions,
            Map<String, List<PriceBar>> barsByTicker) {

        if (barsByTicker.isEmpty()) {
            log.warn("No bar data available for return calculation");
            return List.of();
        }

        // Build per-ticker date→bar lookup for O(1) date access.
        Map<String, Map<LocalDate, PriceBar>> barMapByTicker = new LinkedHashMap<>();

        for (Map.Entry<String, List<PriceBar>> entry : barsByTicker.entrySet()) {
            if (entry.getValue().size() < 2) {
                log.warn("Insufficient bars for ticker={} ({}), excluding from returns",
                        entry.getKey(), entry.getValue().size());
                continue;
            }
            Map<LocalDate, PriceBar> dateMap = new LinkedHashMap<>();
            for (PriceBar bar : entry.getValue()) {
                dateMap.put(bar.getBarDate(), bar);
            }
            barMapByTicker.put(entry.getKey(), dateMap);
        }

        if (barMapByTicker.isEmpty()) return List.of();

        // Intersection of all dates so every return is computed on the same day
        // across all tickers — eliminates cross-ticker date misalignment.
        Set<LocalDate> commonDates = null;
        for (Map<LocalDate, PriceBar> dateMap : barMapByTicker.values()) {
            if (commonDates == null) {
                commonDates = new TreeSet<>(dateMap.keySet());
            } else {
                commonDates.retainAll(dateMap.keySet());
            }
        }

        if (commonDates == null || commonDates.size() < 2) {
            log.warn("Fewer than 2 common trading days across tickers — cannot compute returns");
            return List.of();
        }

        List<LocalDate> sortedDates = new ArrayList<>(commonDates);
        List<Double> portfolioReturns = new ArrayList<>(sortedDates.size() - 1);

        for (int i = 1; i < sortedDates.size(); i++) {
            LocalDate prevDate = sortedDates.get(i - 1);
            LocalDate currDate = sortedDates.get(i);

            double totalPrevValue = 0.0;

            // First pass: total portfolio value at t-1 for weight computation.
            for (PortfolioPosition pos : positions) {
                Map<LocalDate, PriceBar> dateMap =
                        barMapByTicker.get(pos.getAsset().getTicker());
                if (dateMap == null) continue;

                PriceBar prev = dateMap.get(prevDate);
                if (prev == null) continue;

                totalPrevValue += prev.getAdjClose().doubleValue()
                        * pos.getQuantity().doubleValue();
            }

            if (totalPrevValue == 0.0) {
                log.warn("Zero portfolio value on date={}, skipping", prevDate);
                portfolioReturns.add(0.0);
                continue;
            }

            // Second pass: weighted return for this day.
            double weightedReturn = 0.0;

            for (PortfolioPosition pos : positions) {
                Map<LocalDate, PriceBar> dateMap =
                        barMapByTicker.get(pos.getAsset().getTicker());
                if (dateMap == null) continue;

                PriceBar prev = dateMap.get(prevDate);
                PriceBar curr = dateMap.get(currDate);
                if (prev == null || curr == null) continue;

                double posWeight = (prev.getAdjClose().doubleValue()
                        * pos.getQuantity().doubleValue()) / totalPrevValue;

                weightedReturn += posWeight * curr.dailyReturn(prev);
            }

            portfolioReturns.add(weightedReturn);
        }

        return portfolioReturns;
    }

    /**
     * Convenience overload that fetches bars internally.
     */
    public List<Double> buildDailyReturns(
            List<PortfolioPosition> positions, LocalDate from, LocalDate to) {

        return buildDailyReturns(positions, fetchBars(positions, from, to));
    }
}