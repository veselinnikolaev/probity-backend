package me.veselin.probity.portfolio.service.portfolio;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.veselin.probity.common.util.DataUtil;
import me.veselin.probity.marketdata.domain.PriceBar;
import me.veselin.probity.marketdata.port.MarketDataPort;
import me.veselin.probity.portfolio.domain.PortfolioPosition;
import me.veselin.probity.risk.port.RiskPort;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@Slf4j
@RequiredArgsConstructor
class PortfolioDataHelper {

    static final int PORTFOLIO_LOOKBACK_DAYS = 30; // Historical price window for standard analysis
    static final int SPARKLINE_POINTS = 7;          // Number of points in sparkline downsampling
    static final double TRADING_DAYS = 252.0;       // Trading days per year for volatility scaling

    private final MarketDataPort marketDataPort;
    private final RiskPort riskPort;

    // ── Time-series assembly ──────────────────────────────────────────────────

    /**
     * Bundles the bars, portfolio value series, dates, and daily returns for a
     * given position list and date window into a single value object.
     * Zero-value dates (holidays / corrupt bars) are stripped before returning.
     */
    record PortfolioTimeSeries(
            Map<String, List<PriceBar>> barsByTicker,
            List<Double> values,
            List<LocalDate> dates,
            List<Double> returns
    ) {
        boolean isEmpty() {
            return values.isEmpty() || returns.isEmpty();
        }
    }

    PortfolioTimeSeries buildTimeSeries(
            List<PortfolioPosition> positions, LocalDate from, LocalDate to) {

        Map<String, List<PriceBar>> barsByTicker = fetchBars(positions, from, to);

        Map<LocalDate, Double> valueByDate = DataUtil.buildValueSeries(positions, barsByTicker);
        // Strip zero-value dates — these are holidays or corrupt bars where
        // no position had valid price data, and would distort return calculations.
        valueByDate.entrySet().removeIf(e -> e.getValue() == 0.0);

        List<Double> values = new ArrayList<>(valueByDate.values());
        List<LocalDate> dates = new ArrayList<>(valueByDate.keySet());
        List<Double> returns = buildDailyReturns(positions, barsByTicker);

        return new PortfolioTimeSeries(barsByTicker, values, dates, returns);
    }

    // ── Risk metric helpers ───────────────────────────────────────────────────

    /** Returns annualised volatility, or 0.0 when there are fewer than 2 return points. */
    double safeVolatility(List<Double> returns) {
        return returns.size() >= 2 ? riskPort.annualisedVolatility(returns) : 0.0;
    }

    /** Returns Sharpe ratio, or 0.0 when there are fewer than 2 return points. */
    double safeSharpe(List<Double> returns) {
        return returns.size() >= 2 ? riskPort.sharpeRatio(returns) : 0.0;
    }

    // ── Rolling metrics ───────────────────────────────────────────────────────

    List<Double> buildRollingMetric(
            List<Double> values, int points, Function<List<Double>, Double> metric) {

        if (values.size() < 2) return Collections.nCopies(points, null);

        List<Double> result = new ArrayList<>(points);
        double step = (double) (values.size() - 1) / (points - 1);

        for (int i = 0; i < points; i++) {
            int end = Math.min((int) Math.round(i * step) + 1, values.size());
            int start = Math.max(0, end - 30);
            List<Double> slice = values.subList(start, end);
            result.add(slice.size() < 2 ? null : metric.apply(slice));
        }
        return result;
    }

    // ── Price fetching ────────────────────────────────────────────────────────

    Map<String, Double> fetchLatestPrices(List<PortfolioPosition> positions) {
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

    Map<String, List<PriceBar>> fetchBars(
            List<PortfolioPosition> positions, LocalDate from, LocalDate to) {

        log.debug("fetchBars tickers={} from={} to={}",
                positions.stream().map(p -> p.getAsset().getTicker()).toList(), from, to);

        Map<String, List<PriceBar>> result = new LinkedHashMap<>();

        for (PortfolioPosition pos : positions) {
            String ticker = pos.getAsset().getTicker();
            if (result.containsKey(ticker)) continue; // deduplicate

            try {
                List<PriceBar> bars = marketDataPort.getHistoricalBars(ticker, from, to);
                log.info("fetchBars ticker={} bars.size()={}", ticker, bars == null ? "null" : bars.size());
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

    // ── Value helpers ─────────────────────────────────────────────────────────

    double computeTotalValue(
            List<PortfolioPosition> positions, Map<String, Double> priceByTicker) {

        return positions.stream().mapToDouble(pos -> {
            double price = priceByTicker.getOrDefault(pos.getAsset().getTicker(), 0.0);
            return price * pos.getQuantity().doubleValue();
        }).sum();
    }

    // ── Daily returns ─────────────────────────────────────────────────────────

    List<Double> buildDailyReturns(
            List<PortfolioPosition> positions,
            Map<String, List<PriceBar>> barsByTicker) {

        log.debug("buildDailyReturns tickers={} barCounts={}",
                barsByTicker.keySet(),
                barsByTicker.entrySet().stream()
                        .collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().size())));

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

        if (commonDates.size() < 2) {
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
                Map<LocalDate, PriceBar> dateMap = barMapByTicker.get(pos.getAsset().getTicker());
                if (dateMap == null) continue;

                PriceBar prev = dateMap.get(prevDate);
                if (prev == null) continue;

                totalPrevValue += prev.getAdjClose().doubleValue() * pos.getQuantity().doubleValue();
            }

            if (totalPrevValue == 0.0) {
                log.warn("Zero portfolio value on date={}, skipping", prevDate);
                portfolioReturns.add(0.0);
                continue;
            }

            // Second pass: weighted return for this day.
            double weightedReturn = 0.0;

            for (PortfolioPosition pos : positions) {
                Map<LocalDate, PriceBar> dateMap = barMapByTicker.get(pos.getAsset().getTicker());
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
}