package me.veselin.probity.risk.service;

import me.veselin.probity.common.util.DataUtil;
import me.veselin.probity.marketdata.domain.PriceBar;
import me.veselin.probity.portfolio.domain.PortfolioPosition;
import me.veselin.probity.portfolio.enumeration.AssetType;
import me.veselin.probity.risk.dto.DistributionStatistics;
import me.veselin.probity.risk.enumeration.RiskLevel;
import me.veselin.probity.risk.port.RiskPort;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class RiskCalculator implements RiskPort {

    @Value("${probity.risk.risk-free-rate:0.045}")
    private double riskFreeRate;

    // Risk calculation constants
    private static final double TRADING_DAYS = 252.0;
    private static final double Z_95 = 1.645; // 95% confidence level z-score
    private static final int VOLATILITY_THRESHOLD = 15; // Volatility threshold for risk score addon (%)
    private static final int VOL_ADDON_FACTOR = 10; // Volatility band width for risk score calculation
    private static final int RISK_SCORE_INCREMENT = 5; // Points added per volatility band

    @Override
    public double annualisedVolatility(List<Double> dailyReturns) {
        if (dailyReturns.size() < 2) return 0.0;
        double mean = DataUtil.mean(dailyReturns);
        double variance = DataUtil.variance(dailyReturns, mean);
        return Math.sqrt(variance) * Math.sqrt(TRADING_DAYS) * 100;
    }

    @Override
    public double sharpeRatio(List<Double> dailyReturns) {
        if (dailyReturns.size() < 2) return 0.0;
        double mean = DataUtil.mean(dailyReturns);
        double stdDev = Math.sqrt(DataUtil.variance(dailyReturns, mean));
        if (stdDev == 0) return 0.0;
        double riskFreeDaily = riskFreeRate / TRADING_DAYS;
        return ((mean - riskFreeDaily) / stdDev) * Math.sqrt(TRADING_DAYS);
    }

    @Override
    public double var95(double portfolioValue, List<Double> dailyReturns) {
        if (dailyReturns.size() < 2) return 0.0;
        double mean = DataUtil.mean(dailyReturns);
        double stdDev = Math.sqrt(DataUtil.variance(dailyReturns, mean));
        return portfolioValue * Z_95 * stdDev;
    }

    @Override
    public List<Double> rollingVolatility(List<Double> priceSeries, int windowDays) {
        List<Double> returns = toDailyReturns(priceSeries);
        return calculateRollingVolatility(returns, windowDays);
    }

    @Override
    public List<Double> rollingVolatilityFromReturns(List<Double> dailyReturns, int windowDays) {
        return calculateRollingVolatility(dailyReturns, windowDays);
    }

    @Override
    public List<Double> toDailyReturns(List<Double> priceSeries) {
        if (priceSeries.size() < 2) return List.of();
        var result = new ArrayList<Double>(priceSeries.size() - 1);
        for (int i = 1; i < priceSeries.size(); i++) {
            double prev = priceSeries.get(i - 1);
            if (prev == 0) continue;
            result.add((priceSeries.get(i) - prev) / prev);
        }
        return result;
    }

    @Override
    public int riskScore(String assetType, double annualisedVol) {
        int base = AssetType.valueOf(assetType.toUpperCase()).getBaseRiskScore();
        int volAddon = annualisedVol <= VOLATILITY_THRESHOLD
                ? 0
                : (int) ((annualisedVol - VOLATILITY_THRESHOLD) / VOL_ADDON_FACTOR * RISK_SCORE_INCREMENT);
        return Math.min(100, base + volAddon);
    }

    @Override
    public String riskLevel(int score) {
        return RiskLevel.fromScore(score).name();
    }

    @Override
    public double maxDrawdown(List<Double> values) {
        if (values.size() < 2) return 0.0;
        double peak = values.getFirst();
        double maxDd = 0.0;
        for (double v : values) {
            if (v > peak) peak = v;
            double dd = (peak - v) / peak;
            if (dd > maxDd) maxDd = dd;
        }
        return maxDd * 100;
    }

    @Override
    public double computeHHI(List<PortfolioPosition> positions,
                             Map<String, List<PriceBar>> barsByTicker) {
        Map<String, Double> latestPrices = barsByTicker.entrySet().stream()
                .filter(e -> !e.getValue().isEmpty())
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        e -> e.getValue().getLast().getAdjClose().doubleValue()
                ));

        double total = positions.stream()
                .mapToDouble(p -> latestPrices.getOrDefault(p.getAsset().getTicker(), 0.0)
                        * p.getQuantity().doubleValue())
                .sum();

        if (total == 0.0) return 0.0;

        return positions.stream()
                .mapToDouble(p -> {
                    double val = latestPrices.getOrDefault(p.getAsset().getTicker(), 0.0)
                            * p.getQuantity().doubleValue();
                    double w = val / total;
                    return w * w;
                })
                .sum();
    }

    @Override
    public double pearsonCorrelation(List<Double> x, List<Double> y) {
        int n = Math.min(x.size(), y.size());
        if (n < 2) return 0.0;
        double meanX = x.subList(0, n).stream().mapToDouble(Double::doubleValue).average().orElse(0);
        double meanY = y.subList(0, n).stream().mapToDouble(Double::doubleValue).average().orElse(0);
        double cov = 0, varX = 0, varY = 0;
        for (int i = 0; i < n; i++) {
            double dx = x.get(i) - meanX;
            double dy = y.get(i) - meanY;
            cov += dx * dy;
            varX += dx * dx;
            varY += dy * dy;
        }
        double denom = Math.sqrt(varX * varY);
        return denom == 0.0 ? 0.0 : cov / denom;
    }

    /**
     * Descriptive statistics over a pre-sorted ascending array of simulated values.
     * Single-pass mean + variance to avoid iterating the array twice.
     */
    @Override
    public DistributionStatistics distributionStatistics(double[] sortedValues) {
        int n = sortedValues.length;
        if (n == 0) return new DistributionStatistics(0, 0, 0, 0, 0);

        // Single-pass Welford online mean + M2 accumulator (numerically stable)
        double mean = 0.0;
        double m2   = 0.0;
        for (int i = 0; i < n; i++) {
            double delta = sortedValues[i] - mean;
            mean += delta / (i + 1);
            m2   += delta * (sortedValues[i] - mean);
        }
        double stdDev = n > 1 ? Math.sqrt(m2 / n) : 0.0;

        // Median: already sorted — midpoint for odd N, average of two midpoints for even
        double median = (n % 2 == 1)
                ? sortedValues[n / 2]
                : (sortedValues[n / 2 - 1] + sortedValues[n / 2]) / 2.0;

        return new DistributionStatistics(mean, median, stdDev, sortedValues[0], sortedValues[n - 1]);
    }

    /**
     * Empirical VaR from a sorted simulation distribution.
     * Reads the value at the (1 - confidenceLevel) quantile of the sorted array.
     */
    @Override
    public double simulationVaR(double[] sortedValues, double confidenceLevel) {
        if (sortedValues.length == 0) return 0.0;
        int n = sortedValues.length;
        int index = (int) Math.floor((1.0 - confidenceLevel) * n + 1e-9);
        index = Math.max(0, Math.min(index, n - 1));
        return sortedValues[index];
    }

    /**
     * Conditional VaR (Expected Shortfall) — average of all values at or below the VaR threshold.
     * Requires sortedValues to be ascending (same precondition as simulationVaR).
     */
    @Override
    public double conditionalValueAtRisk(double[] sortedValues, double confidenceLevel) {
        if (sortedValues.length == 0) return 0.0;
        int n = sortedValues.length;
        int cutoff = Math.max(1, (int) Math.floor((1.0 - confidenceLevel) * n + 1e-9));
        double sum = 0.0;
        for (int i = 0; i < cutoff; i++) sum += sortedValues[i];
        return sum / cutoff;
    }

    protected List<Double> calculateRollingVolatility(List<Double> dailyReturns, int windowDays) {
        var result = new ArrayList<Double>(dailyReturns.size());
        for (int i = 0; i < dailyReturns.size(); i++) {
            int from = Math.max(0, i - windowDays + 1);
            List<Double> window = dailyReturns.subList(from, i + 1);
            result.add(window.size() < 2 ? null : annualisedVolatility(window));
        }
        return result;
    }
}
